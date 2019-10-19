package me.cyber.nukleos.motors

import android.bluetooth.*
import android.util.Log
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import me.cyber.nukleos.IMotors
import me.cyber.nukleos.bluetooth.BluetoothConnector
import me.cyber.nukleos.dagger.PeripheryManager
import java.util.concurrent.TimeUnit

class MotorsBlueTooth(val peripheryManager: PeripheryManager, val bluetoothConnector: BluetoothConnector)
    : IMotors, BluetoothGattCallback() {

    private var gatt: BluetoothGatt? = null
    private var servicesDiscovered = false
    private var speeds: ByteArray = ByteArray(IMotors.MOTORS_COUNT)
    private var findMotorsSubscription: Disposable? = null
    private var connStatus : IMotors.Status = IMotors.Status.DISCONNECTED

    override fun getSpeeds() = speeds

    override fun getName(): String = "BT MOTORS"

    override fun connect() {
        if (connStatus == IMotors.Status.DISCONNECTED) {
            peripheryManager.motors = this
            connStatus = IMotors.Status.CONNECTING
            peripheryManager.notifyMotorsChanged()

            bluetoothConnector.startBluetoothScan(10, TimeUnit.SECONDS, IMotors.SERVICE_UUID)
                    .also {
                        findMotorsSubscription = it.subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe({
                                    gatt = it.connectGatt(bluetoothConnector.context, false, this)
                                    findMotorsSubscription?.dispose()
                                }, {
                                    Log.e(TAG, it.message, it)
                                    disconnect()
                                }, {
                                    disconnect()
                                })
                    }
        }
    }

    override fun getConnectionStatus(): IMotors.Status = connStatus

    override fun disconnect() {
        connStatus = IMotors.Status.DISCONNECTED
        speeds = ByteArray(IMotors.MOTORS_COUNT)
        gatt?.close()
        gatt = null
        servicesDiscovered = false
        findMotorsSubscription?.dispose()
        peripheryManager.notifyMotorsChanged()
    }

    private fun sendSpinCommand() {
        if (isConnected()) {
            try {
                val characteristic = gatt
                        ?.getService(IMotors.SERVICE_UUID)
                        ?.getCharacteristic(IMotors.CHAR_MOTOR_CONTROL_UUID)
                characteristic?.value = speeds
                gatt?.writeCharacteristic(characteristic)
            } catch (t: Throwable) {
                Log.e(TAG, t.message, t)
            }
        }
    }

    override fun spinMotor(iMotor: Byte, speed: Byte) {
        speeds[iMotor - 1] = speed
        sendSpinCommand()
    }

    override fun spinMotors(speeds: ByteArray) {
        this.speeds = speeds
        sendSpinCommand()
    }

    override fun stopMotors() = spinMotor(0, 0)

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        super.onConnectionStateChange(gatt, status, newState)
        Log.d(TAG, "onConnectionStateChange: $status -> $newState")
        if (newState != status && newState == BluetoothProfile.STATE_CONNECTED) {
            Log.d(TAG, "MotorsBlueTooth device connected. Discovering services.")
            gatt.discoverServices()
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            // Calling onDisconnected() here will cause to release the GATT resources.
            disconnect()
            Log.d(TAG, "Bluetooth Disconnected")
        }
    }

    @Synchronized
    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS && !servicesDiscovered) {
            servicesDiscovered = true
            gatt.getService(IMotors.SERVICE_UUID)?.let {
                val characteristic = it.getCharacteristic(IMotors.CHAR_MOTOR_STATE_UUID)
                if (characteristic != null) {
                    Log.d(TAG, "Subscribing to motors state...")
                    gatt.setCharacteristicNotification(characteristic, true)
                    val subscribeDescriptor = characteristic.getDescriptor(IMotors.CLIENT_CONFIG_DESCRIPTOR)
                    if (subscribeDescriptor == null) {
                        Log.w(TAG, "Failed to resolve motor state subscription descriptor.")
                    } else {
                        subscribeDescriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        val subs = gatt.writeDescriptor(subscribeDescriptor)
                        connStatus = IMotors.Status.CONNECTED
                        Log.d(TAG, "Subscribed to motors state : $subs")
                        peripheryManager.notifyMotorsChanged()
                    }
                } else {
                    Log.e(TAG, "Failed to subscribe to motor state.")
                }
            }
            Log.d(TAG, "MotorsBlueTooth connected : $servicesDiscovered")
        } else {
            Log.d(TAG, "onServicesDiscovered received: $status")
        }
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        super.onCharacteristicChanged(gatt, characteristic)
        speeds = characteristic.value
        peripheryManager.notifyMotorsChanged()
    }

    companion object {
        const val TAG = "MotorsBluetooth"
    }

}