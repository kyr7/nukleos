package me.cyber.nukleos.sensors.myosensor

import android.bluetooth.*
import android.util.Log
import io.reactivex.subjects.BehaviorSubject
import me.cyber.nukleos.App
import me.cyber.nukleos.sensors.LastKnownSensorManager
import me.cyber.nukleos.sensors.Sensor
import me.cyber.nukleos.sensors.Status
import me.cyber.nukleos.utils.isStartStreamingCommand
import me.cyber.nukleos.utils.isStopStreamingCommand
import java.util.*


class Myo(private val device: BluetoothDevice) : Sensor, BluetoothGattCallback() {

    companion object {
        val BLUETOOTH_UUID: UUID = UUID.fromString("D5060001-A904-DEB9-4748-2C7F4A124842")

        private val availableFrequencies = listOf(50, 100, 150, 200)

        private const val defaultDelayBetweenCommands = 3000L

        private const val TAG = "Myo"
    }

    override val name: String
        get() = device.name

    override val address: String
        get() = device.address

    private var frequency: Int = MYO_MAX_FREQUENCY

    private var keepAlive = true
    private var lastKeepAlive = 0L

    private val connectionStatusSubject: BehaviorSubject<Status> = BehaviorSubject.createDefault(Status.AVAILABLE)

    private var gatt: BluetoothGatt? = null
    private var byteReader = ByteReader()

    private var serviceControl: BluetoothGattService? = null
    private var characteristicCommand: BluetoothGattCharacteristic? = null
    private var characteristicInfo: BluetoothGattCharacteristic? = null
    private var serviceEmg: BluetoothGattService? = null
    private var characteristicEmg0: BluetoothGattCharacteristic? = null
    private var characteristicEmg1: BluetoothGattCharacteristic? = null
    private var characteristicEmg2: BluetoothGattCharacteristic? = null
    private var characteristicEmg3: BluetoothGattCharacteristic? = null

    private val writeQueue: LinkedList<BluetoothGattDescriptor> = LinkedList()
    private val readQueue: LinkedList<BluetoothGattCharacteristic> = LinkedList()

    override fun connect() {
        connectionStatusSubject.onNext(Status.CONNECTING)
        gatt = device.connectGatt(App.applicationComponent.getAppContext(), false, this)
    }

    override fun disconnect() {
        stopStreaming()
        gatt?.close()
        connectionStatusSubject.onNext(Status.AVAILABLE)
    }

    override fun statusObservable() = connectionStatusSubject

    private fun sendCommand(command: Command): Boolean {
        characteristicCommand?.apply {
            this.value = command
            if (this.properties == BluetoothGattCharacteristic.PROPERTY_WRITE) {
                if (command.isStartStreamingCommand()) {
                    connectionStatusSubject.onNext(Status.STREAMING)
                } else if (command.isStopStreamingCommand()) {
                    connectionStatusSubject.onNext(Status.AVAILABLE)
                }
                gatt?.writeCharacteristic(this)
                return true
            }
        }
        return false
    }

    private fun startStreaming() {
        sendCommand(CommandList.emgFilteredOnly())
        connectionStatusSubject.onNext(Status.STREAMING)
        LastKnownSensorManager.updateLastKnownSensor(this)
    }

    private fun stopStreaming() {
        sendCommand(CommandList.stopStreaming())
        connectionStatusSubject.onNext(Status.AVAILABLE)
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        super.onConnectionStateChange(gatt, status, newState)
        Log.d(TAG, "onConnectionStateChange: $status -> $newState")
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            Log.d(TAG, "Bluetooth Connected")
            gatt.discoverServices()
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            // Calling onDisconnected() here will cause to release the GATT resources.
            disconnect()
            Log.d(TAG, "Bluetooth Disconnected")
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        super.onServicesDiscovered(gatt, status)
        Log.d(TAG, "onServicesDiscovered received: $status")

        if (status != BluetoothGatt.GATT_SUCCESS) {
            return
        }
        serviceEmg = gatt.getService(SERVICE_EMG_DATA_ID)
        serviceEmg?.apply {
            characteristicEmg0 = serviceEmg?.getCharacteristic(CHAR_EMG_0_ID)
            characteristicEmg1 = serviceEmg?.getCharacteristic(CHAR_EMG_1_ID)
            characteristicEmg2 = serviceEmg?.getCharacteristic(CHAR_EMG_2_ID)
            characteristicEmg3 = serviceEmg?.getCharacteristic(CHAR_EMG_3_ID)

            listOf(characteristicEmg0,
                    characteristicEmg1,
                    characteristicEmg2,
                    characteristicEmg3).forEach { emgCharacteristic ->
                emgCharacteristic?.apply {
                    if (gatt.setCharacteristicNotification(emgCharacteristic, true)) {
                        val descriptor = emgCharacteristic.getDescriptor(CHAR_CLIENT_CONFIG)
                        descriptor?.apply {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            writeDescriptor(gatt, descriptor)
                        }
                    }
                }
            }
        }

        // Find GATT Service Control
        serviceControl = gatt.getService(SERVICE_CONTROL_ID)
        serviceControl?.apply {
            characteristicInfo = this.getCharacteristic(CHAR_INFO_ID)
            characteristicInfo?.apply {
                // if there is only 1 item in the queue, then read it.  If more than 1, we handle asynchronously in the
                // callback. GIVE PRECEDENCE to descriptor writes. They must all finish first.
                readQueue.add(this)
                if (readQueue.size == 1 && writeQueue.size == 0) {
                    gatt.readCharacteristic(this)
                }
            }
            characteristicCommand = this.getCharacteristic(CHAR_COMMAND_ID)
            characteristicCommand?.apply {
                lastKeepAlive = System.currentTimeMillis()
                sendCommand(CommandList.unSleep())
                Thread {
                    Thread.sleep(defaultDelayBetweenCommands)
                    startStreaming()
                }.start()
                // We send the ready event as soon as the characteristicCommand is ready.
            }
        }
    }

    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor) {
        writeQueue.add(descriptor)
        // When writing, if the queue is empty, write immediately.
        if (writeQueue.size == 1) {
            gatt.writeDescriptor(descriptor)
        }
    }


    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        super.onDescriptorWrite(gatt, descriptor, status)
        Log.d(TAG, "onDescriptorWrite status: $status")
        writeQueue.remove()
        //if there is more to write, do it!
        if (writeQueue.size > 0)
            gatt.writeDescriptor(writeQueue.element())
        else if (readQueue.size > 0)
            gatt.readCharacteristic(readQueue.element())
    }

    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        super.onCharacteristicRead(gatt, characteristic, status)
        readQueue.remove()
        Log.d(TAG, "onCharacteristicRead status: $status ${characteristic.uuid}")

        if (CHAR_INFO_ID == characteristic.uuid) {
            // Myo Device Information
            val data = characteristic.value
            if (data != null && data.isNotEmpty()) {
                val byteReader = ByteReader()
                byteReader.byteData = data
                // TODO We might expose these to the public
                val callbackMsg = String.format("Serial Number     : %02x:%02x:%02x:%02x:%02x:%02x",
                        byteReader.byte, byteReader.byte, byteReader.byte,
                        byteReader.byte, byteReader.byte, byteReader.byte) +
                        '\n'.toString() + String.format("Unlock            : %d", byteReader.short) +
                        '\n'.toString() + String.format("Classifier builtin:%d active:%d (have:%d)",
                        byteReader.byte, byteReader.byte, byteReader.byte) +
                        '\n'.toString() + String.format("Stream Type       : %d", byteReader.byte)
                Log.d(TAG, "MYO info string: $callbackMsg")
            }
        }

        if (readQueue.size > 0)
            gatt.readCharacteristic(readQueue.element())
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        super.onCharacteristicChanged(gatt, characteristic)

        if (characteristic.uuid.toString().endsWith(CHAR_EMG_POSTFIX)) {
            val emgData = characteristic.value
            if (emgData.size == EMG_ARRAY_SIZE) {
                byteReader.byteData = emgData
                try {
                    // We receive 16 bytes of data. Let's cut them in 2 and deliver both of them.
                    val bytes0 = byteReader.getBytes(EMG_ARRAY_SIZE / 2)!!
                    val bytes1 = byteReader.getBytes(EMG_ARRAY_SIZE / 2)!!
                    Sensor.onData(name, listOf(bytes0, bytes1))
                } catch (t: Throwable) {
                    Log.w(TAG, "Myo data handling problem", t)
                }
            } else {
                Log.w(TAG, "Got ${emgData.size} bytes from Myo : $emgData")
            }
        }

        // Finally check if keep alive makes sense.
        val currentTimeMillis = System.currentTimeMillis()
        if (keepAlive && currentTimeMillis > lastKeepAlive + KEEP_ALIVE_INTERVAL_MS) {
            lastKeepAlive = currentTimeMillis
            sendCommand(CommandList.unSleep())
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Myo) return false
        return other.address == address
    }

    override fun hashCode(): Int = address.hashCode()

    override fun isFeedbackSupported(): Boolean = true

    override fun feedback(param: String) {
        sendCommand(when (Integer.parseInt(param)) {
            1 -> CommandList.vibration1()
            2 -> CommandList.vibration2()
            else -> CommandList.vibration3()
        })
    }

    override fun getFrequency(): Int = frequency

    override fun setFrequency(newFrequency: Int) {
        frequency = if (newFrequency >= MYO_MAX_FREQUENCY) 0 else newFrequency
    }

    override fun getAvailableFrequencies(): List<Int> = availableFrequencies
}