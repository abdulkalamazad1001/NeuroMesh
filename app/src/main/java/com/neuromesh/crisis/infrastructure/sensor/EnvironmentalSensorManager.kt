package com.neuromesh.crisis.infrastructure.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.neuromesh.crisis.util.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.sqrt

@Singleton
class EnvironmentalSensorManager @Inject constructor(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var latestAcceleration = FloatArray(3)
    private var latestMagnetic = FloatArray(3)
    private var latestPressure = 0f
    private var latestHumidity = 0f

    private val accelerometerHistory = ArrayDeque<Float>(HISTORY_SIZE)

    private val _seismicEvents = Channel<Float>(Channel.BUFFERED)
    val seismicEvents: Flow<Float> = _seismicEvents.receiveAsFlow()

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            latestAcceleration = event.values.clone()
            val magnitude = sqrt(
                event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2]
            )
            if (accelerometerHistory.size >= HISTORY_SIZE) accelerometerHistory.removeFirst()
            accelerometerHistory.addLast(magnitude)

            if (magnitude > SEISMIC_THRESHOLD) {
                _seismicEvents.trySend(magnitude)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    fun startListening() {
        // Switch to SENSOR_DELAY_NORMAL for all sensors on low-end devices.
        // SENSOR_DELAY_GAME causes high interrupt rates that consume CPU/Power
        // and increase the risk of the system killing the process on 2GB RAM.
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(accelListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorManager.registerListener(pressureListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)?.let {
            sensorManager.registerListener(humidityListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        Logger.d(TAG, "Sensor listeners registered (NORMAL delay)")
    }

    private val pressureListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) { latestPressure = event.values[0] }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private val humidityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) { latestHumidity = event.values[0] }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    fun readSensors(): String {
        val accelMag = sqrt(
            latestAcceleration[0] * latestAcceleration[0] +
                    latestAcceleration[1] * latestAcceleration[1] +
                    latestAcceleration[2] * latestAcceleration[2]
        )

        val recentPeak = accelerometerHistory.maxOrNull() ?: accelMag
        val isSeismic = recentPeak > SEISMIC_THRESHOLD

        val description = StringBuilder()
        description.append("Accelerometer: ${accelMag.format(2)} m/s² (peak: ${recentPeak.format(2)}). ")

        if (isSeismic) description.append("SEISMIC ACTIVITY DETECTED (${recentPeak.format(2)} m/s²). ")

        if (latestPressure > 0) {
            description.append("Pressure: ${latestPressure.format(1)} hPa")
            when {
                latestPressure < 950 -> description.append(" (very low - severe weather). ")
                latestPressure < 980 -> description.append(" (low - weather front). ")
                else -> description.append(" (normal). ")
            }
        }

        if (latestHumidity > 0) {
            description.append("Humidity: ${latestHumidity.toInt()}%. ")
            if (latestHumidity > 90) description.append("Very high humidity - possible flooding. ")
        }

        return description.toString().trim()
    }

    fun getCurrentAccelerationMagnitude(): Float {
        return sqrt(
            latestAcceleration[0] * latestAcceleration[0] +
                    latestAcceleration[1] * latestAcceleration[1] +
                    latestAcceleration[2] * latestAcceleration[2]
        )
    }

    fun readSignal(): EnvironmentalSignal {
        val accelMag = getCurrentAccelerationMagnitude()
        val peak = accelerometerHistory.maxOrNull() ?: accelMag
        return EnvironmentalSignal(
            accelMagnitude = accelMag,
            accelPeak = peak,
            isSeismic = peak > SEISMIC_THRESHOLD
        )
    }

    fun stopListening() {
        sensorManager.unregisterListener(accelListener)
        sensorManager.unregisterListener(pressureListener)
        sensorManager.unregisterListener(humidityListener)
    }

    private fun Float.format(decimals: Int) = "%.${decimals}f".format(this)

    companion object {
        private const val TAG = "EnvironmentalSensorManager"
        private const val SEISMIC_THRESHOLD = 15f
        private const val HISTORY_SIZE = 50
    }
}
