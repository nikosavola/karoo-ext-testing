package fi.nikosavola.karooext.testing.robolectric.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import fi.nikosavola.karooext.testing.awaitValue
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import java.util.concurrent.ConcurrentHashMap
import org.robolectric.Shadows.shadowOf

/**
 * Entry point for faking Bluetooth LE hardware under Robolectric: the radio an extension scans and
 * connects with. It builds on Robolectric's own Bluetooth shadows, so the extension's real
 * `BluetoothLeScanner` and `BluetoothGatt` code runs unchanged; this only removes the setup that
 * every test would otherwise repeat. Create one per test from the test application.
 *
 * The adapter starts switched on and the device reports BLE support, unlike Robolectric's default;
 * see [setEnabled]. Callbacks are delivered on the calling test thread, like the rest of the
 * library. Pass Robolectric 4.16 or newer; older shadows lack some of the hooks used here.
 *
 * Not covered: scans started with a `PendingIntent`, the legacy `startLeScan`, GATT servers and the
 * `autoConnect` flag, which the shadow drops.
 */
class FakeBle(private val app: Application) {
  private val peripherals = ConcurrentHashMap<String, FakeBlePeripheral>()

  init {
    // Robolectric starts with the adapter off and no Bluetooth features, which makes apps that
    // check isEnabled or hasSystemFeature, such as Kable, bail out before they scan or connect.
    shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
    val packages = shadowOf(app.packageManager)
    packages.setSystemFeature(PackageManager.FEATURE_BLUETOOTH, true)
    packages.setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
  }

  /** The adapter the extension under test gets from `BluetoothManager`. */
  val adapter: BluetoothAdapter
    get() = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

  /** How many scans are running right now. */
  val scanCount: Int
    get() = activeScanCallbacks().size

  /** Whether any scan is currently running. */
  val isScanning: Boolean
    get() = activeScanCallbacks().isNotEmpty()

  /**
   * Grants the runtime permissions a BLE scan and connection need on the running SDK. Tests that
   * cover the missing-permission path simply do not call it.
   */
  fun grantPermissions() {
    val permissions =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
      } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
      }
    shadowOf(app).grantPermissions(*permissions)
  }

  /**
   * Switches the adapter on or off and broadcasts `ACTION_STATE_CHANGED`, which Robolectric does
   * not do by itself. Switching off also ends every running scan and disconnects every connected
   * peripheral.
   */
  @SuppressLint("MissingPermission")
  fun setEnabled(on: Boolean) {
    if (!on) {
      // Switching the radio off ends scans and drops every link, as on a device.
      activeScans().forEach { scan ->
        scan.scanCallback()?.let { adapter.bluetoothLeScanner?.stopScan(it) }
      }
      peripherals.values.filter { it.isConnected }.forEach { it.disconnect() }
    }
    val state = if (on) BluetoothAdapter.STATE_ON else BluetoothAdapter.STATE_OFF
    shadowOf(adapter).setState(state)
    app.sendBroadcast(
      Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
        .setPackage(app.packageName)
        .putExtra(BluetoothAdapter.EXTRA_STATE, state)
    )
    RobolectricPump.pumpMainLooper()
  }

  /**
   * Creates the simulated device at [address], or returns the one made earlier for that address.
   * [configure] runs only on creation and lists its advertisement and GATT services.
   */
  fun peripheral(
    address: String,
    name: String? = null,
    configure: FakeBlePeripheral.Builder.() -> Unit = {},
  ): FakeBlePeripheral {
    val known = peripherals[address]
    if (known != null) {
      require(known.name == name || name == null) {
        "$address already exists as \"${known.name}\"; a second declaration cannot rename it"
      }
      return known
    }
    return FakeBlePeripheral.Builder(address, name).apply(configure).build(this).also {
      peripherals[address] = it
    }
  }

  /**
   * Waits until the extension has started a scan, pumping the main looper. Advertise after this,
   * since an advertisement with nobody scanning is lost, as on real hardware.
   *
   * @throws IllegalStateException if no scan starts within [timeoutMs].
   */
  fun awaitScan(timeoutMs: Long = 20_000) {
    awaitValue(timeoutMs, RobolectricPump::pumpMainLooper) { true.takeIf { isScanning } }
  }

  /**
   * Waits until [count] scans are running at once, for an extension that runs a saved-device scan
   * next to a discovery scan, so an advertisement is not sent before the one you mean has started.
   *
   * @throws IllegalStateException if fewer than [count] scans run within [timeoutMs].
   */
  fun awaitScans(count: Int, timeoutMs: Long = 20_000) {
    awaitValue(timeoutMs, RobolectricPump::pumpMainLooper) { true.takeIf { scanCount >= count } }
  }

  /** Active scan callbacks with the filters each one registered. */
  internal fun activeScans() =
    adapter.bluetoothLeScanner
      ?.let { shadowOf(it).activeScans }
      .orEmpty()
      .filter { it.scanCallback() != null }

  private fun activeScanCallbacks(): List<ScanCallback> =
    activeScans().mapNotNull { it.scanCallback() }

  /**
   * Sends [result] to every running scan whose filters match it, like a radio would; returns how
   * many took it.
   */
  internal fun deliver(result: ScanResult): Int {
    var delivered = 0
    activeScans().forEach { scan ->
      val filters = scan.scanFilters()
      if (filters.isEmpty() || filters.any { it.matches(result) }) {
        scan.scanCallback()?.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
        delivered++
      }
    }
    RobolectricPump.pumpMainLooper()
    return delivered
  }
}
