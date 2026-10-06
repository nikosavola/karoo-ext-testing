package fi.nikosavola.karooext.testing.robolectric.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.os.Handler
import android.os.Looper

/**
 * Sits between Robolectric's gatt shadow and the extension's callback to log writes and
 * subscriptions, then forwards every call unchanged. Both the pre-33 and the API 33 variants are
 * forwarded as themselves, because the framework defaults chain them in one direction only, and
 * posted to the main looper, so they arrive after the extension's call returned, as from a device.
 * Writes and subscriptions are recorded before the post. This runs on the JVM and Robolectric picks
 * the variant for the emulated SDK, so a call to a newer variant only happens when that SDK has it.
 */
@SuppressLint("NewApi")
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "TooManyFunctions")
internal class GattCallbackTap(
  private val delegate: BluetoothGattCallback?,
  private val peripheral: FakeBlePeripheral,
) : BluetoothGattCallback() {
  private val main = Handler(Looper.getMainLooper())

  // The shadow calls back inside the extension's own GATT call; a device answers later, and
  // clients such as Nordic register what they wait for only after that call returns.
  private fun post(call: () -> Unit) {
    main.post(call)
  }

  override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
    post { delegate?.onConnectionStateChange(gatt, status, newState) }
  }

  override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
    post { delegate?.onServicesDiscovered(gatt, status) }
  }

  override fun onCharacteristicRead(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    status: Int,
  ) {
    post { delegate?.onCharacteristicRead(gatt, characteristic, status) }
  }

  override fun onCharacteristicRead(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
    status: Int,
  ) {
    post { delegate?.onCharacteristicRead(gatt, characteristic, value, status) }
  }

  override fun onCharacteristicWrite(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    status: Int,
  ) {
    peripheral.recordWrite(characteristic)
    post { delegate?.onCharacteristicWrite(gatt, characteristic, status) }
  }

  override fun onCharacteristicChanged(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
  ) {
    post { delegate?.onCharacteristicChanged(gatt, characteristic) }
  }

  override fun onCharacteristicChanged(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
  ) {
    post { delegate?.onCharacteristicChanged(gatt, characteristic, value) }
  }

  override fun onDescriptorRead(
    gatt: BluetoothGatt,
    descriptor: BluetoothGattDescriptor,
    status: Int,
  ) {
    post { delegate?.onDescriptorRead(gatt, descriptor, status) }
  }

  override fun onDescriptorRead(
    gatt: BluetoothGatt,
    descriptor: BluetoothGattDescriptor,
    status: Int,
    value: ByteArray,
  ) {
    post { delegate?.onDescriptorRead(gatt, descriptor, status, value) }
  }

  override fun onDescriptorWrite(
    gatt: BluetoothGatt,
    descriptor: BluetoothGattDescriptor,
    status: Int,
  ) {
    peripheral.recordSubscription(descriptor)
    post { delegate?.onDescriptorWrite(gatt, descriptor, status) }
  }

  override fun onReliableWriteCompleted(gatt: BluetoothGatt, status: Int) {
    post { delegate?.onReliableWriteCompleted(gatt, status) }
  }

  override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
    post { delegate?.onReadRemoteRssi(gatt, rssi, status) }
  }

  override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
    post { delegate?.onMtuChanged(gatt, mtu, status) }
  }

  override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
    post { delegate?.onPhyUpdate(gatt, txPhy, rxPhy, status) }
  }

  override fun onPhyRead(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
    post { delegate?.onPhyRead(gatt, txPhy, rxPhy, status) }
  }

  override fun onServiceChanged(gatt: BluetoothGatt) {
    post { delegate?.onServiceChanged(gatt) }
  }
}
