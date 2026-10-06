package fi.nikosavola.karooext.testing.robolectric.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor

/**
 * Sits between Robolectric's gatt shadow and the extension's callback to log writes and
 * subscriptions, then forwards every call unchanged. Both the pre-33 and the API 33 variants are
 * forwarded as themselves, because the framework defaults chain them in one direction only. This
 * runs on the JVM and Robolectric picks the variant for the emulated SDK, so a call to a newer
 * variant only happens when that SDK has it.
 */
@SuppressLint("NewApi")
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "TooManyFunctions")
internal class GattCallbackTap(
  private val delegate: BluetoothGattCallback?,
  private val peripheral: FakeBlePeripheral,
) : BluetoothGattCallback() {
  override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
    delegate?.onConnectionStateChange(gatt, status, newState)
  }

  override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
    delegate?.onServicesDiscovered(gatt, status)
  }

  override fun onCharacteristicRead(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    status: Int,
  ) {
    delegate?.onCharacteristicRead(gatt, characteristic, status)
  }

  override fun onCharacteristicRead(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
    status: Int,
  ) {
    delegate?.onCharacteristicRead(gatt, characteristic, value, status)
  }

  override fun onCharacteristicWrite(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    status: Int,
  ) {
    peripheral.recordWrite(characteristic)
    delegate?.onCharacteristicWrite(gatt, characteristic, status)
  }

  override fun onCharacteristicChanged(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
  ) {
    delegate?.onCharacteristicChanged(gatt, characteristic)
  }

  override fun onCharacteristicChanged(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
  ) {
    delegate?.onCharacteristicChanged(gatt, characteristic, value)
  }

  override fun onDescriptorRead(
    gatt: BluetoothGatt,
    descriptor: BluetoothGattDescriptor,
    status: Int,
  ) {
    delegate?.onDescriptorRead(gatt, descriptor, status)
  }

  override fun onDescriptorRead(
    gatt: BluetoothGatt,
    descriptor: BluetoothGattDescriptor,
    status: Int,
    value: ByteArray,
  ) {
    delegate?.onDescriptorRead(gatt, descriptor, status, value)
  }

  override fun onDescriptorWrite(
    gatt: BluetoothGatt,
    descriptor: BluetoothGattDescriptor,
    status: Int,
  ) {
    peripheral.recordSubscription(descriptor)
    delegate?.onDescriptorWrite(gatt, descriptor, status)
  }

  override fun onReliableWriteCompleted(gatt: BluetoothGatt, status: Int) {
    delegate?.onReliableWriteCompleted(gatt, status)
  }

  override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
    delegate?.onReadRemoteRssi(gatt, rssi, status)
  }

  override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
    delegate?.onMtuChanged(gatt, mtu, status)
  }

  override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
    delegate?.onPhyUpdate(gatt, txPhy, rxPhy, status)
  }

  override fun onPhyRead(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
    delegate?.onPhyRead(gatt, txPhy, rxPhy, status)
  }

  override fun onServiceChanged(gatt: BluetoothGatt) {
    delegate?.onServiceChanged(gatt)
  }
}
