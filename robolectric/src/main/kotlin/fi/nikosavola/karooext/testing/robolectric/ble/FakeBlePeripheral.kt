package fi.nikosavola.karooext.testing.robolectric.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.os.Build
import android.os.SystemClock
import fi.nikosavola.karooext.testing.AwaitTimeoutException
import fi.nikosavola.karooext.testing.awaitValue
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import org.robolectric.Shadows.shadowOf

private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"
private const val AD_FLAGS = 0x01
private const val AD_SERVICES_16 = 0x03
private const val AD_SERVICES_128 = 0x07
private const val AD_NAME = 0x09
private const val AD_MANUFACTURER = 0xFF
private const val FLAGS_LE_GENERAL = 0x06
private const val UUID_HEX_DIGITS = 8
private const val BYTE_MASK = 0xFF
private const val BITS_PER_BYTE = 8
private const val UUID_BYTES = 16
private const val SHORT_UUID_BYTES = 2
private const val SHORT_UUID_MAX = 0xFFFF
private const val UUID_LSB_BYTES = 8
private val CCCD: UUID = bleUuid(0x2902)

/** The 128-bit UUID for a 16-bit assigned number such as `0x180F` (battery service). */
fun bleUuid(shortUuid: Int): UUID {
  require(shortUuid in 0..SHORT_UUID_MAX) { "not a 16-bit UUID: $shortUuid" }
  val hex = shortUuid.toString(16).padStart(UUID_HEX_DIGITS, '0')
  return UUID.fromString(hex + BASE_UUID_SUFFIX)
}

/**
 * One value the extension wrote to a characteristic.
 *
 * @property characteristic the UUID written to.
 * @property value the bytes written.
 */
class GattWrite(val characteristic: UUID, val value: ByteArray) {
  /** The UUID and the value as hex. */
  override fun toString() = "$characteristic <- ${value.joinToString("") { "%02x".format(it) }}"
}

/**
 * A simulated BLE device. The extension finds it by scanning, connects with `connectGatt` and talks
 * to the GATT services declared in [FakeBle.peripheral]; the test plays the device from here.
 *
 * A connection takes two steps, as on real hardware. The extension asks to connect, which
 * [awaitConnectionRequest] sees, and the device accepts, which [accept] does. [connect] does both.
 * After that the extension's own `discoverServices` finds the declared services, and its
 * notification setup is checked: [notify] refuses to send on a characteristic the extension never
 * subscribed to, which is the mistake a mock would hide.
 *
 * Instances come from [FakeBle.peripheral]. Drive them from the test thread; only [onWrite]
 * handlers run elsewhere.
 */
@Suppress("TooManyFunctions")
class FakeBlePeripheral
private constructor(
  private val ble: FakeBle,
  /** The device address the extension scans for and connects to. */
  val address: String,
  /** Advertised local name, or null when the device does not advertise one. */
  val name: String?,
  private val advertisedServices: List<UUID>,
  private val manufacturerData: Map<Int, ByteArray>,
  private val services: List<BluetoothGattService>,
) {
  private val device: BluetoothDevice = ble.adapter.getRemoteDevice(address)
  private val writeLog = CopyOnWriteArrayList<GattWrite>()
  private val subscriptions = Collections.newSetFromMap(ConcurrentHashMap<UUID, Boolean>())
  private val pending = CopyOnWriteArrayList<BluetoothGatt>()
  private val writeHandlers = ConcurrentHashMap<UUID, CopyOnWriteArrayList<(ByteArray) -> Unit>>()
  private val handlerErrors = CopyOnWriteArrayList<Throwable>()
  private val replies by lazy {
    Executors.newSingleThreadExecutor { task ->
      Thread(task, "fake-ble-$address").apply { isDaemon = true }
    }
  }

  init {
    shadowOf(device).setGattConnectionInterceptor(::attach)
    name?.let { shadowOf(device).setName(it) }
  }

  /** Every write the extension has made, in order, across connections. */
  val writes: List<GattWrite>
    get() = writeLog.toList()

  /** The latest connection attempt the extension has not closed, or null. */
  val gatt: BluetoothGatt?
    get() = shadowOf(device).bluetoothGatts.lastOrNull { !shadowOf(it).isClosed }

  /** Whether the extension asked to connect and the device accepted. */
  val isConnected: Boolean
    get() = gatt?.let { shadowOf(it).isConnected } ?: false

  /** Exceptions thrown by [onWrite] handlers, oldest first. */
  val handlerFailures: List<Throwable>
    get() = handlerErrors.toList()

  /** Characteristics the extension currently has notifications or indications enabled on. */
  val subscribedCharacteristics: Set<UUID>
    get() = subscriptions.toSet()

  /**
   * Sends one advertisement to every running scan whose filters match, once a scan is running. The
   * extension's scan callback sees the result with this device's name, services and manufacturer
   * data.
   *
   * @throws IllegalStateException if no scan starts within [timeoutMs].
   */
  fun advertise(rssi: Int = DEFAULT_RSSI, timeoutMs: Long = 20_000) {
    ble.awaitScan(timeoutMs)
    ble.deliver(scanResult(rssi))
  }

  /**
   * Waits for the extension to call `connectGatt` on this device and returns the resulting gatt.
   *
   * @throws IllegalStateException if it does not within [timeoutMs].
   */
  fun awaitConnectionRequest(timeoutMs: Long = 20_000): BluetoothGatt =
    await("a connection request to $address", timeoutMs) { nextRequest() }

  /**
   * Accepts the oldest connection request that has not been accepted, so the extension's callback
   * sees `STATE_CONNECTED`. A client that reconnects through `BluetoothGatt.connect()` on a gatt it
   * kept is connected by Robolectric at once and makes no new request.
   */
  fun accept() {
    val request = checkNotNull(nextRequest()) { "No connection request to $address to accept" }
    pending -= request
    shadowOf(request).notifyConnection(address)
  }

  /** [awaitConnectionRequest] followed by [accept]. */
  fun connect(timeoutMs: Long = 20_000): BluetoothGatt =
    awaitConnectionRequest(timeoutMs).also { accept() }

  /**
   * Drops the link from the device side; the extension's callback sees `STATE_DISCONNECTED` with
   * [status], `GATT_SUCCESS` for a clean disconnect or for example 8 for a supervision timeout.
   */
  fun disconnect(status: Int = BluetoothGatt.GATT_SUCCESS) {
    val current = checkNotNull(gatt) { notConnected() }
    val shadow = shadowOf(current)
    if (status == BluetoothGatt.GATT_SUCCESS) {
      shadow.notifyDisconnection(address)
      return
    }
    // notifyDisconnection reports success only, so mute it and deliver the real status ourselves.
    val callback = shadow.gattCallback
    shadow.gattCallback = null
    shadow.notifyDisconnection(address)
    shadow.gattCallback = callback
    callback.onConnectionStateChange(current, status, BluetoothProfile.STATE_DISCONNECTED)
  }

  /**
   * Fails the oldest pending connection request with [status], the way a connection that cannot be
   * established does; 133 is the generic error Android reports. The extension's callback sees
   * `STATE_DISCONNECTED` with that status and the request is consumed.
   */
  fun failConnection(status: Int = GATT_ERROR) {
    val request = checkNotNull(nextRequest()) { "No connection request to $address to fail" }
    pending -= request
    shadowOf(request)
      .gattCallback
      .onConnectionStateChange(
        request,
        status,
        BluetoothProfile.STATE_DISCONNECTED,
      )
  }

  /**
   * Waits until the extension has written the enable value to the notification descriptor of
   * [characteristic].
   *
   * @throws IllegalStateException if it does not within [timeoutMs].
   */
  fun awaitSubscribed(characteristic: UUID, timeoutMs: Long = 20_000) {
    await("a subscription to $characteristic", timeoutMs) {
      true.takeIf { characteristic in subscriptions }
    }
  }

  /**
   * Pushes [value] to the extension as a notification or indication on [characteristic].
   *
   * @throws IllegalStateException if the device is not connected or the extension has not
   *   subscribed to [characteristic], which a real device would also not deliver.
   */
  fun notify(characteristic: UUID, value: ByteArray) {
    val current = checkNotNull(gatt) { notConnected() }
    check(shadowOf(current).isConnected) { notConnected() }
    check(characteristic in subscriptions) {
      "The extension has not enabled notifications on $characteristic; subscribed: $subscriptions"
    }
    val target = find(characteristic)
    @Suppress("DEPRECATION") target.setValue(value)
    val callback = shadowOf(current).gattCallback
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      callback.onCharacteristicChanged(current, target, value)
    } else {
      @Suppress("DEPRECATION") callback.onCharacteristicChanged(current, target)
    }
  }

  /** Sets what the extension reads from [characteristic] until it is changed again. */
  fun setValue(characteristic: UUID, value: ByteArray) {
    @Suppress("DEPRECATION") find(characteristic).setValue(value)
  }

  /**
   * Plays the device's side of a request and response protocol: [handler] runs for every value the
   * extension writes to [characteristic], on a separate thread after the write has returned, as a
   * real device answers later. Typically it calls [notify] with the reply. An exception in
   * [handler] is kept in [handlerFailures] and shown in timeout messages.
   */
  fun onWrite(characteristic: UUID, handler: (ByteArray) -> Unit) {
    writeHandlers.getOrPut(characteristic) { CopyOnWriteArrayList() } += handler
  }

  /** Values written to [characteristic], oldest first. */
  fun writesTo(characteristic: UUID): List<ByteArray> =
    writeLog.filter { it.characteristic == characteristic }.map { it.value }

  /**
   * Waits for a write to [characteristic] that matches [predicate], looking at writes already made
   * too, from index [after] of [writes] on.
   *
   * @throws IllegalStateException if none arrives within [timeoutMs].
   */
  fun awaitWrite(
    characteristic: UUID,
    timeoutMs: Long = 20_000,
    after: Int = 0,
    predicate: (ByteArray) -> Boolean = { true },
  ): ByteArray =
    await("a write to $characteristic", timeoutMs) {
      writeLog
        .drop(after)
        .filter { it.characteristic == characteristic }
        .map { it.value }
        .firstOrNull(predicate)
    }

  /** The number of writes so far, to pass as `after` to wait only for later writes. */
  fun mark(): Int = writeLog.size

  /** A one-line summary for failure messages. */
  fun describe(): String =
    "FakeBlePeripheral($address, name=$name, connected=$isConnected, subscribed=$subscriptions, " +
      "writes=${writeLog.toList()}, handlerFailures=${handlerErrors.toList()})"

  internal fun recordWrite(characteristic: BluetoothGattCharacteristic) {
    @Suppress("DEPRECATION") val value = characteristic.value ?: ByteArray(0)
    val written = value.copyOf()
    writeLog += GattWrite(characteristic.uuid, written)
    writeHandlers[characteristic.uuid]?.forEach { handler ->
      replies.execute {
        @Suppress("TooGenericExceptionCaught")
        try {
          handler(written)
        } catch (t: Throwable) {
          handlerErrors += t
        }
      }
    }
  }

  internal fun recordSubscription(descriptor: BluetoothGattDescriptor) {
    if (descriptor.uuid != CCCD) return
    @Suppress("DEPRECATION") val value = descriptor.value ?: return
    val owner = descriptor.characteristic.uuid
    if (value.isNotEmpty() && value[0].toInt() != 0) {
      subscriptions += owner
    } else {
      subscriptions -= owner
    }
  }

  private fun nextRequest(): BluetoothGatt? = pending.firstOrNull { !shadowOf(it).isClosed }

  private fun attach(gatt: BluetoothGatt) {
    subscriptions.clear()
    pending += gatt
    val shadow = shadowOf(gatt)
    services.forEach { service ->
      shadow.addDiscoverableService(service)
      service.characteristics
        .filter { it.properties and NOTIFYING != 0 }
        .forEach(shadow::allowCharacteristicNotification)
    }
    shadow.gattCallback = GattCallbackTap(shadow.gattCallback, this)
  }

  private fun notConnected() = "$address is not connected"

  private fun find(characteristic: UUID): BluetoothGattCharacteristic {
    val matches = services.mapNotNull { it.getCharacteristic(characteristic) }
    check(matches.isNotEmpty()) { "$address declares no characteristic $characteristic" }
    check(matches.size == 1) { "$characteristic is declared in more than one service" }
    return matches.single()
  }

  private fun <T : Any> await(what: String, timeoutMs: Long, probe: () -> T?): T {
    failIfHandlerBroke()
    return try {
      awaitValue(timeoutMs, RobolectricPump::pumpMainLooper) {
        failIfHandlerBroke()
        probe()
      }
    } catch (e: AwaitTimeoutException) {
      throw AwaitTimeoutException("Timed out waiting for $what; ${describe()}").apply {
        initCause(e)
      }
    }
  }

  private fun failIfHandlerBroke() {
    handlerErrors.firstOrNull()?.let {
      throw IllegalStateException("An onWrite handler failed", it)
    }
  }

  private fun scanResult(rssi: Int): ScanResult {
    val parse = ScanRecord::class.java.getMethod("parseFromBytes", ByteArray::class.java)
    val record = parse.invoke(null, advertisementBytes()) as ScanRecord
    @Suppress("DEPRECATION")
    return ScanResult(device, record, rssi, SystemClock.elapsedRealtimeNanos())
  }

  private fun advertisementBytes(): ByteArray {
    val out = ByteArrayOutputStream()

    fun structure(type: Int, payload: ByteArray) {
      out.write(payload.size + 1)
      out.write(type)
      out.write(payload)
    }
    structure(AD_FLAGS, byteArrayOf(FLAGS_LE_GENERAL.toByte()))
    name?.let { structure(AD_NAME, it.toByteArray()) }
    val (short, long) = advertisedServices.partition { it.isShort() }
    if (short.isNotEmpty()) {
      structure(AD_SERVICES_16, short.flatMap { it.shortBytes() }.toByteArray())
    }
    if (long.isNotEmpty()) {
      structure(AD_SERVICES_128, long.flatMap { it.fullBytes() }.toByteArray())
    }
    manufacturerData.forEach { (company, data) ->
      structure(
        AD_MANUFACTURER,
        byteArrayOf(company.toByte(), (company shr BITS_PER_BYTE).toByte()) + data,
      )
    }
    return out.toByteArray()
  }

  private fun UUID.isShort() =
    toString().endsWith(BASE_UUID_SUFFIX) && toString().startsWith("0000")

  private fun UUID.shortBytes(): List<Byte> {
    val value = (mostSignificantBits ushr 32).toInt()
    return List(SHORT_UUID_BYTES) { value.shr(BITS_PER_BYTE * it).and(BYTE_MASK).toByte() }
  }

  // Advertisements carry 128-bit UUIDs little endian.
  private fun UUID.fullBytes(): List<Byte> =
    List(UUID_BYTES) { index ->
      val word = if (index < UUID_LSB_BYTES) leastSignificantBits else mostSignificantBits
      word.ushr(BITS_PER_BYTE * (index % UUID_LSB_BYTES)).and(BYTE_MASK.toLong()).toByte()
    }

  /** Collects what a [FakeBlePeripheral] advertises and which GATT services it hosts. */
  class Builder internal constructor(private val address: String, private val name: String?) {
    private val advertised = mutableListOf<UUID>()
    private val manufacturer = mutableMapOf<Int, ByteArray>()
    private val services = mutableListOf<BluetoothGattService>()

    /** Adds [uuid] to the advertised service list, which scan filters match on. */
    fun advertiseService(uuid: UUID) {
      advertised += uuid
    }

    /** Adds manufacturer-specific advertisement data for the 16-bit [companyId]. */
    fun manufacturerData(companyId: Int, data: ByteArray) {
      manufacturer[companyId] = data
    }

    /**
     * Declares a primary GATT service. With [advertised] set the UUID also appears in the
     * advertisement.
     */
    fun service(uuid: UUID, advertised: Boolean = false, block: ServiceBuilder.() -> Unit) {
      val service = BluetoothGattService(uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
      ServiceBuilder(service).apply(block)
      services += service
      if (advertised) advertiseService(uuid)
    }

    internal fun build(ble: FakeBle) =
      FakeBlePeripheral(
        ble,
        address,
        name,
        advertised.toList(),
        manufacturer.toMap(),
        services.toList(),
      )
  }

  /** Declares the characteristics of one GATT service. */
  class ServiceBuilder internal constructor(private val service: BluetoothGattService) {
    /**
     * Adds a characteristic. Notify and indicate characteristics get the standard client
     * configuration descriptor, which the extension must write to subscribe. [value] is what a read
     * returns until [FakeBlePeripheral.setValue] changes it.
     */
    @Suppress("LongParameterList")
    fun characteristic(
      uuid: UUID,
      read: Boolean = false,
      write: Boolean = false,
      writeNoResponse: Boolean = false,
      notify: Boolean = false,
      indicate: Boolean = false,
      value: ByteArray? = null,
    ) {
      var properties = 0
      if (read) properties = properties or BluetoothGattCharacteristic.PROPERTY_READ
      if (write) properties = properties or BluetoothGattCharacteristic.PROPERTY_WRITE
      if (writeNoResponse) {
        properties = properties or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
      }
      if (notify) properties = properties or BluetoothGattCharacteristic.PROPERTY_NOTIFY
      if (indicate) properties = properties or BluetoothGattCharacteristic.PROPERTY_INDICATE
      val characteristic = BluetoothGattCharacteristic(uuid, properties, 0)
      if (notify || indicate) {
        val permissions =
          BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        characteristic.addDescriptor(BluetoothGattDescriptor(CCCD, permissions))
      }
      @Suppress("DEPRECATION") value?.let(characteristic::setValue)
      service.addCharacteristic(characteristic)
    }
  }

  private companion object {
    const val DEFAULT_RSSI = -60
    const val GATT_ERROR = 133
    const val NOTIFYING =
      BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE
  }
}
