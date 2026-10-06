package fi.nikosavola.karooext.testing.robolectric.ble

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** A minimal BLE client like an extension would write, in one of the two callback styles. */
@Suppress("DEPRECATION")
class BleClient(private val app: Application, newStyle: Boolean) {
  val results = CopyOnWriteArrayList<ScanResult>()
  val states = CopyOnWriteArrayList<Int>()
  val statuses = CopyOnWriteArrayList<Int>()
  val notifications = CopyOnWriteArrayList<List<Byte>>()
  val reads = CopyOnWriteArrayList<List<Byte>>()
  val discovered = CopyOnWriteArrayList<UUID>()

  @Volatile var gatt: BluetoothGatt? = null

  private val adapter: BluetoothAdapter
    get() = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

  private val scanCallback =
    object : ScanCallback() {
      override fun onScanResult(callbackType: Int, result: ScanResult) {
        results += result
      }
    }

  private val callback: BluetoothGattCallback = if (newStyle) NewStyle() else OldStyle()

  fun scan(vararg filters: ScanFilter) {
    adapter.bluetoothLeScanner!!.startScan(
      filters.toList(),
      ScanSettings.Builder().build(),
      scanCallback,
    )
  }

  fun connect(address: String) {
    gatt = adapter.getRemoteDevice(address).connectGatt(app, false, callback)
  }

  fun subscribe(uuid: UUID, indicate: Boolean = false) {
    val characteristic = characteristic(uuid)
    gatt!!.setCharacteristicNotification(characteristic, true)
    val cccd = characteristic.getDescriptor(CCCD)
    val value =
      if (indicate) {
        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
      } else {
        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
      }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      gatt!!.writeDescriptor(cccd, value)
    } else {
      cccd.value = value
      gatt!!.writeDescriptor(cccd)
    }
  }

  fun write(uuid: UUID, value: ByteArray) {
    val characteristic = characteristic(uuid)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      gatt!!.writeCharacteristic(
        characteristic,
        value,
        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
      )
    } else {
      characteristic.value = value
      gatt!!.writeCharacteristic(characteristic)
    }
  }

  fun read(uuid: UUID) {
    gatt!!.readCharacteristic(characteristic(uuid))
  }

  private fun characteristic(uuid: UUID): BluetoothGattCharacteristic =
    gatt!!.services.flatMap { it.characteristics }.first { it.uuid == uuid }

  private inner class OldStyle : BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
      states += newState
      statuses += status
      if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
      gatt.services.forEach { discovered += it.uuid }
    }

    override fun onCharacteristicChanged(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
    ) {
      notifications += characteristic.value.toList()
    }

    override fun onCharacteristicRead(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      status: Int,
    ) {
      reads += characteristic.value.toList()
    }
  }

  private inner class NewStyle : BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
      states += newState
      statuses += status
      if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
      gatt.services.forEach { discovered += it.uuid }
    }

    override fun onCharacteristicChanged(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      value: ByteArray,
    ) {
      notifications += value.toList()
    }

    override fun onCharacteristicRead(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      value: ByteArray,
      status: Int,
    ) {
      reads += value.toList()
    }
  }

  private companion object {
    val CCCD: UUID = bleUuid(0x2902)
  }
}
