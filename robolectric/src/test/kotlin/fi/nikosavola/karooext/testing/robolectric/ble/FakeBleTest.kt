package fi.nikosavola.karooext.testing.robolectric.ble

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanFilter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.karooext.testing.awaitValue
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val HEART_RATE = bleUuid(0x180D)
private val MEASUREMENT = bleUuid(0x2A37)
private val CONTROL = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
private val CUSTOM_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
private val INDICATED = bleUuid(0x2A35)
private val QUIET_WRITE = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
private const val ADDRESS = "AA:BB:CC:DD:EE:01"

private fun wait(probe: () -> Boolean) {
  awaitValue(2_000, RobolectricPump::pumpMainLooper) { true.takeIf { probe() } }
}

abstract class FakeBleCase(private val newStyle: Boolean) {
  protected val app: Application = ApplicationProvider.getApplicationContext()
  protected lateinit var ble: FakeBle
  protected lateinit var client: BleClient
  protected lateinit var band: FakeBlePeripheral

  @Before
  fun setUp() {
    ble = FakeBle(app)
    ble.grantPermissions()
    client = BleClient(app, newStyle)
    band =
      ble.peripheral(ADDRESS, name = "Test band") {
        service(HEART_RATE, advertised = true) {
          characteristic(MEASUREMENT, notify = true)
          characteristic(INDICATED, indicate = true)
        }
        service(CUSTOM_SERVICE) {
          characteristic(CONTROL, write = true, read = true, value = byteArrayOf(7))
          characteristic(QUIET_WRITE, writeNoResponse = true)
        }
        manufacturerData(0x0059, byteArrayOf(1, 2))
      }
  }

  @Test
  fun `advertisement reaches scans whose filters match`() {
    client.scan(ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE)).build())
    val other = BleClient(app, newStyle)
    other.scan(ScanFilter.Builder().setDeviceName("Something else").build())

    band.advertise(rssi = -42)

    val result = client.results.single()
    assertEquals(ADDRESS, result.device.address)
    assertEquals(-42, result.rssi)
    assertEquals("Test band", result.scanRecord!!.deviceName)
    assertEquals(listOf(ParcelUuid(HEART_RATE)), result.scanRecord!!.serviceUuids)
    assertEquals(
      listOf<Byte>(1, 2),
      result.scanRecord!!.getManufacturerSpecificData(0x0059)!!.toList(),
    )
    assertTrue(other.results.isEmpty())
  }

  @Test
  fun `advertise waits for a scan like the radio would`() {
    assertThrows(IllegalStateException::class.java) { band.advertise(timeoutMs = 100) }
  }

  @Test
  fun `connection, discovery, subscription and notifications reach the client`() {
    client.connect(ADDRESS)
    band.connect()
    wait { client.discovered.isNotEmpty() }
    assertTrue(client.discovered.containsAll(listOf(HEART_RATE, CUSTOM_SERVICE)))
    assertEquals(listOf(BluetoothProfile.STATE_CONNECTED), client.states)

    client.subscribe(MEASUREMENT)
    band.awaitSubscribed(MEASUREMENT)
    band.notify(MEASUREMENT, byteArrayOf(0, 72))

    assertEquals(listOf(listOf<Byte>(0, 72)), client.notifications)
  }

  @Test
  fun `notifying before the client subscribes is an error`() {
    client.connect(ADDRESS)
    band.connect()
    wait { client.discovered.isNotEmpty() }

    val error =
      assertThrows(IllegalStateException::class.java) { band.notify(MEASUREMENT, byteArrayOf(1)) }

    assertTrue(error.message!!.contains("not enabled notifications"))
  }

  @Test
  fun `writes and reads are logged and answered`() {
    client.connect(ADDRESS)
    band.connect()
    wait { client.discovered.isNotEmpty() }

    client.write(CONTROL, byteArrayOf(1, 2, 3))
    client.write(CONTROL, byteArrayOf(4))
    client.read(CONTROL)

    assertEquals(listOf<Byte>(1, 2, 3), band.awaitWrite(CONTROL).toList())
    assertEquals(
      listOf(listOf<Byte>(1, 2, 3), listOf<Byte>(4)),
      band.writesTo(CONTROL).map { it.toList() },
    )
    assertEquals(listOf<Byte>(4), band.awaitWrite(CONTROL) { it.size == 1 }.toList())
    assertEquals(1, client.reads.size)
  }

  @Test
  fun `a write handler answers like a request and response device`() {
    client.connect(ADDRESS)
    band.connect()
    wait { client.discovered.isNotEmpty() }
    client.subscribe(MEASUREMENT)
    band.awaitSubscribed(MEASUREMENT)
    band.onWrite(CONTROL) { request -> band.notify(MEASUREMENT, byteArrayOf(request[0], 99)) }

    client.write(CONTROL, byteArrayOf(5))

    wait { client.notifications.isNotEmpty() }
    assertEquals(listOf(listOf<Byte>(5, 99)), client.notifications)
  }

  @Test
  fun `a failing write handler is kept and shown`() {
    client.connect(ADDRESS)
    band.connect()
    wait { client.discovered.isNotEmpty() }
    band.onWrite(CONTROL) { error("nobody subscribed") }

    client.write(CONTROL, byteArrayOf(1))

    wait { band.handlerFailures.isNotEmpty() }
    assertTrue(band.describe().contains("nobody subscribed"))
  }

  @Test
  fun `disconnect reaches the client and notify then refuses`() {
    client.connect(ADDRESS)
    band.connect()
    assertTrue(band.isConnected)

    band.disconnect()

    assertFalse(band.isConnected)
    assertThrows(IllegalStateException::class.java) { band.notify(MEASUREMENT, byteArrayOf(1)) }
    assertEquals(
      listOf(BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTED),
      client.states,
    )
  }

  @Test
  fun `the device reports bluetooth le support`() {
    assertTrue(app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
  }

  @Test
  fun `the adapter starts on and setEnabled broadcasts the change`() {
    assertTrue(ble.adapter.isEnabled)
    val states = CopyOnWriteArrayList<Int>()
    val receiver =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          states += intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
        }
      }
    val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      app.registerReceiver(receiver, filter)
    }

    ble.setEnabled(false)

    assertFalse(ble.adapter.isEnabled)
    assertEquals(listOf(BluetoothAdapter.STATE_OFF), states)
  }

  @Test
  fun `a reconnect needs a new connection request from the client`() {
    client.connect(ADDRESS)
    band.connect()
    band.disconnect()

    assertThrows(IllegalStateException::class.java) { band.awaitConnectionRequest(timeoutMs = 100) }
    assertThrows(IllegalStateException::class.java) { band.accept() }

    client.connect(ADDRESS)
    band.connect()
    assertEquals(
      listOf(
        BluetoothProfile.STATE_CONNECTED,
        BluetoothProfile.STATE_DISCONNECTED,
        BluetoothProfile.STATE_CONNECTED,
      ),
      client.states,
    )
  }

  @Test
  fun `a scan without filters sees every advertisement`() {
    client.scan()

    band.advertise()

    assertEquals(1, client.results.size)
  }

  @Test
  fun `a 128-bit service advertised by the peripheral matches a service filter`() {
    val custom =
      ble.peripheral("AA:BB:CC:DD:EE:02", name = "Custom") {
        service(CUSTOM_SERVICE, advertised = true) { characteristic(CONTROL, write = true) }
      }
    client.scan(ScanFilter.Builder().setServiceUuid(ParcelUuid(CUSTOM_SERVICE)).build())

    band.advertise()
    custom.advertise()

    assertEquals(listOf("AA:BB:CC:DD:EE:02"), client.results.map { it.device.address })
    assertEquals(
      listOf(ParcelUuid(CUSTOM_SERVICE)),
      client.results.single().scanRecord!!.serviceUuids,
    )
  }

  @Test
  fun `a device address filter matches only that device`() {
    ble.peripheral("AA:BB:CC:DD:EE:02", name = "Other")
    client.scan(ScanFilter.Builder().setDeviceAddress(ADDRESS).build())

    ble.peripheral("AA:BB:CC:DD:EE:02").advertise()
    band.advertise()

    assertEquals(listOf(ADDRESS), client.results.map { it.device.address })
  }

  @Test
  fun `indications are subscribed and delivered like notifications`() {
    client.connect(ADDRESS)
    band.connect()
    wait { client.discovered.isNotEmpty() }

    client.subscribe(INDICATED, indicate = true)
    band.awaitSubscribed(INDICATED)
    band.notify(INDICATED, byteArrayOf(9))

    assertEquals(listOf(listOf<Byte>(9)), client.notifications)
  }

  @Test
  fun `writes without response are logged`() {
    client.connect(ADDRESS)
    band.connect()
    wait { client.discovered.isNotEmpty() }

    client.write(QUIET_WRITE, byteArrayOf(4, 2))

    assertEquals(listOf<Byte>(4, 2), band.awaitWrite(QUIET_WRITE).toList())
  }

  @Test
  fun `awaitWrite can wait for writes after a mark`() {
    client.connect(ADDRESS)
    band.connect()
    wait { client.discovered.isNotEmpty() }
    client.write(CONTROL, byteArrayOf(1))
    val mark = band.mark()
    client.write(CONTROL, byteArrayOf(2))

    assertEquals(listOf<Byte>(2), band.awaitWrite(CONTROL, after = mark).toList())
    assertThrows(IllegalStateException::class.java) {
      band.awaitWrite(CONTROL, timeoutMs = 100, after = band.mark())
    }
  }

  @Test
  fun `a failing write handler surfaces in the next wait`() {
    client.connect(ADDRESS)
    band.connect()
    wait { client.discovered.isNotEmpty() }
    band.onWrite(CONTROL) { error("handler broke") }

    client.write(CONTROL, byteArrayOf(1))

    val error =
      assertThrows(IllegalStateException::class.java) {
        band.awaitWrite(CONTROL, timeoutMs = 5_000) { false }
      }
    assertEquals("handler broke", error.cause!!.message)
  }

  @Test
  fun `switching the adapter off ends scans and drops links`() {
    client.scan()
    client.connect(ADDRESS)
    band.connect()
    assertTrue(ble.isScanning)

    ble.setEnabled(false)

    assertFalse(ble.isScanning)
    assertFalse(band.isConnected)
    assertEquals(BluetoothProfile.STATE_DISCONNECTED, client.states.last())
  }

  @Test
  fun `redeclaring a peripheral under another name is refused`() {
    assertThrows(IllegalArgumentException::class.java) { ble.peripheral(ADDRESS, name = "Renamed") }
    assertEquals(band, ble.peripheral(ADDRESS))
  }

  @Test
  fun `a failed connection and a disconnect with a status reach the client`() {
    client.connect(ADDRESS)
    band.failConnection()
    client.connect(ADDRESS)
    band.connect()

    band.disconnect(status = 8)

    assertFalse(band.isConnected)
    assertEquals(
      listOf(
        BluetoothProfile.STATE_DISCONNECTED,
        BluetoothProfile.STATE_CONNECTED,
        BluetoothProfile.STATE_DISCONNECTED,
      ),
      client.states,
    )
    assertEquals(listOf(133, 0, 8), client.statuses)
  }

  @Test
  fun `a connection request that never comes times out with the peripheral state`() {
    val error =
      assertThrows(IllegalStateException::class.java) {
        band.awaitConnectionRequest(timeoutMs = 100)
      }

    assertTrue(error.message!!.contains(ADDRESS))
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33, 34, 35])
class FakeBleOldCallbacksTest : FakeBleCase(newStyle = false)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 34, 35])
class FakeBleNewCallbacksTest : FakeBleCase(newStyle = true)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FakeBlePermissionTest {
  private val app = ApplicationProvider.getApplicationContext<Application>()

  @Test
  fun `permissions are denied until granted, as the extension's own checks see them`() {
    val ble = FakeBle(app)
    val scan = android.Manifest.permission.BLUETOOTH_SCAN
    val connect = android.Manifest.permission.BLUETOOTH_CONNECT
    assertEquals(PackageManager.PERMISSION_DENIED, app.checkSelfPermission(scan))

    ble.grantPermissions()

    assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(scan))
    assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(connect))
  }
}
