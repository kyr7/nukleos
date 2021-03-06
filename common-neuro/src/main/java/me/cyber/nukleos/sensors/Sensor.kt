package me.cyber.nukleos.sensors

import io.reactivex.subjects.BehaviorSubject
import me.cyber.nukleos.sensors.Status.*
import java.lang.Exception
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.collections.HashMap

interface Sensor {

    val name: String

    val address: String

    fun statusObservable(): BehaviorSubject<Status>

    fun connect()

    fun disconnect()

    fun isConnected(): Boolean {
        return if (statusObservable().hasValue()) {
            STREAMING == statusObservable().value
        } else {
            false
        }
    }

    fun isFeedbackSupported(): Boolean

    fun feedback(param: String)

    fun getFrequency(): Int

    fun setFrequency(newFrequency: Int)

    fun getAvailableFrequencies(): List<Int>

    companion object {

        private val sensorDataFeeder: SensorDataFeeder = SensorDataFeeder()

        fun registerSensorListener(listenerName: String, sensorListener: SensorListener, subscriptionParams: SubscriptionParams = SubscriptionParams(1, 1)) = sensorDataFeeder.registerSensorListener(listenerName, sensorListener, subscriptionParams)

        fun listenOnce(sensorListener: SensorListener, window: Int) = sensorDataFeeder.listenOnce(sensorListener, window)

        fun removeSensorListener(listenerName: String) = sensorDataFeeder.removeSensorListener(listenerName)

        fun onData(sensorName: String, data: List<FloatArray>) = sensorDataFeeder.onData(sensorName, data)

    }
}

interface SensorListener {

    fun onSensorData(sensorName: String, data: List<FloatArray>)

}

enum class Status {
    AVAILABLE,
    CONNECTING,
    STREAMING
}

data class SubscriptionParams(
        val window: Int,
        val slide: Int
)

class SensorDataFeeder {

    private var maxWindow: Int = 1
    private val dataQueues: MutableMap<String, ConcurrentLinkedQueue<FloatArray>> = HashMap()
    private val listeners: MutableMap<String, Pair<SensorListener, SubscriptionParams>> = HashMap()
    private val dataExecutor = Executors.newSingleThreadExecutor()

    fun size() = maxWindow

    private fun updateMaxWindow() {
        val maxWindowCurrent = listeners.maxBy { it.value.second.window }?.value?.second?.window
                ?: 1
        maxWindow = maxWindowCurrent
    }

    fun registerSensorListener(listenerName: String, sensorListener: SensorListener, subscriptionParams: SubscriptionParams = SubscriptionParams(1, 1)) {
        synchronized(listeners) {
            listeners[listenerName] = Pair(sensorListener, subscriptionParams)
            dataQueues[listenerName] = ConcurrentLinkedQueue()
            updateMaxWindow()
        }
    }

    fun removeSensorListener(listenerName: String) {
        synchronized(listeners) {
            listeners.remove(listenerName)
            dataQueues.remove(listenerName)
            updateMaxWindow()
        }
    }

    fun listenOnce(sensorListener: SensorListener, window: Int) {
        val listenerName = UUID.randomUUID().toString()
        registerSensorListener(listenerName, object : SensorListener {
            override fun onSensorData(sensorName: String, data: List<FloatArray>) {
                removeSensorListener(listenerName)
                sensorListener.onSensorData(sensorName, data)
            }
        }, SubscriptionParams(window, window))
    }

    //todo multiple sensors names
    fun onData(sensorName: String, data: List<FloatArray>) {
        val queuesToProcess: Map<String, ConcurrentLinkedQueue<FloatArray>> = synchronized(listeners) {
            HashMap(dataQueues)
        }
        queuesToProcess.forEach { (_, q) ->
            data.forEach {
                q.add(it)
            }
        }
        dataExecutor.submit {
            queuesToProcess.forEach { (listenerName, queue) ->
                val params = listeners[listenerName]!!.second
                val queueSize = queue.size
                for (iStart in 0 until queueSize step params.slide) {
                    if (iStart + params.window <= queueSize) {
                        val result = ArrayList<FloatArray>(params.window)
                        val queIter = queue.iterator()
                        for (i in 0 until params.window) {
                            result.add(queIter.next())
                        }
                        for (i in 0 until params.slide) {
                            queue.remove()
                        }
                        try {
                            listeners[listenerName]?.first?.onSensorData(sensorName, result)
                        } catch (e: Exception) {
                            println(e.message)
                        }
                    }
                }
            }
        }
    }

}