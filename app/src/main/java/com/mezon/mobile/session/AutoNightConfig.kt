package com.mezon.mobile.session

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.ui.theme.ThemeMode
import java.util.Calendar

class AutoNightConfig(private val context: Context) : SensorEventListener {

    companion object {
        const val TYPE_NONE = 0
        const val TYPE_SCHEDULED = 1
        const val TYPE_AUTOMATIC = 2
        const val TYPE_SYSTEM = 3

        private const val PREFS_NAME = "auto_night_config"
        private const val SENSOR_SWITCH_DELAY = 1800L
    }

    var autoNightType = TYPE_SYSTEM
        private set
    var scheduleFromHour = 22
        private set
    var scheduleFromMinute = 0
        private set
    var scheduleToHour = 8
        private set
    var scheduleToMinute = 0
        private set
    var brightnessThreshold = 0.25f
        private set
    var nightThemeMode: ThemeMode = ThemeMode.DARK
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var lastBrightness = 1.0f
    @Volatile private var lastAutoNightResult = false

    private var switchDayRunnable: Runnable? = null
    private var switchNightRunnable: Runnable? = null

    private var prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        load()
    }

    private fun load() {
        autoNightType = prefs.getInt("type", TYPE_SYSTEM)
        scheduleFromHour = prefs.getInt("from_hour", 22)
        scheduleFromMinute = prefs.getInt("from_minute", 0)
        scheduleToHour = prefs.getInt("to_hour", 8)
        scheduleToMinute = prefs.getInt("to_minute", 0)
        brightnessThreshold = prefs.getFloat("brightness", 0.25f)
        nightThemeMode = when (prefs.getString("night_theme", "dark")) {
            "abyss" -> ThemeMode.ABYSS
            else -> ThemeMode.DARK
        }
    }

    fun save(
        type: Int,
        fromHour: Int = scheduleFromHour,
        fromMinute: Int = scheduleFromMinute,
        toHour: Int = scheduleToHour,
        toMinute: Int = scheduleToMinute,
        brightness: Float = brightnessThreshold,
        nightMode: ThemeMode = nightThemeMode
    ) {
        autoNightType = type
        scheduleFromHour = fromHour
        scheduleFromMinute = fromMinute
        scheduleToHour = toHour
        scheduleToMinute = toMinute
        brightnessThreshold = brightness
        nightThemeMode = nightMode

        prefs.edit()
            .putInt("type", type)
            .putInt("from_hour", fromHour)
            .putInt("from_minute", fromMinute)
            .putInt("to_hour", toHour)
            .putInt("to_minute", toMinute)
            .putFloat("brightness", brightness)
            .putString("night_theme", if (nightMode == ThemeMode.ABYSS) "abyss" else "dark")
            .apply()

        updateSensorState()
        checkAutoNightConditions()
    }

    fun shouldUseDarkTheme(systemIsDark: Boolean): Boolean {
        return when (autoNightType) {
            TYPE_NONE -> false
            TYPE_SCHEDULED -> isInNightSchedule()
            TYPE_AUTOMATIC -> lastAutoNightResult
            TYPE_SYSTEM -> systemIsDark
            else -> false
        }
    }

    private fun isInNightSchedule(): Boolean {
        val cal = Calendar.getInstance()
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val fromMinutes = scheduleFromHour * 60 + scheduleFromMinute
        val toMinutes = scheduleToHour * 60 + scheduleToMinute

        return if (fromMinutes > toMinutes) {
            nowMinutes >= fromMinutes || nowMinutes < toMinutes
        } else {
            nowMinutes in fromMinutes until toMinutes
        }
    }

    fun startSensorListening() {
        if (autoNightType != TYPE_AUTOMATIC) return
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (lightSensor != null) {
            sensorManager?.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stopSensorListening() {
        sensorManager?.unregisterListener(this)
        switchDayRunnable?.let { mainHandler.removeCallbacks(it) }
        switchNightRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    private fun updateSensorState() {
        if (autoNightType == TYPE_AUTOMATIC) {
            startSensorListening()
        } else {
            stopSensorListening()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LIGHT) return
        val lux = event.values[0]
        val max = event.sensor.maximumRange.coerceAtLeast(1f)
        lastBrightness = lux / max

        val shouldBeNight = lastBrightness <= brightnessThreshold
        if (shouldBeNight != lastAutoNightResult) {
            if (shouldBeNight) {
                if (switchNightRunnable == null) {
                    switchNightRunnable = Runnable {
                        switchNightRunnable = null
                        if (lastBrightness <= brightnessThreshold) {
                            lastAutoNightResult = true
                            checkAutoNightConditions()
                        }
                    }
                    mainHandler.postDelayed(switchNightRunnable!!, SENSOR_SWITCH_DELAY)
                }
                switchDayRunnable?.let {
                    mainHandler.removeCallbacks(it)
                    switchDayRunnable = null
                }
            } else {
                if (switchDayRunnable == null) {
                    switchDayRunnable = Runnable {
                        switchDayRunnable = null
                        if (lastBrightness > brightnessThreshold) {
                            lastAutoNightResult = false
                            checkAutoNightConditions()
                        }
                    }
                    mainHandler.postDelayed(switchDayRunnable!!, SENSOR_SWITCH_DELAY)
                }
                switchNightRunnable?.let {
                    mainHandler.removeCallbacks(it)
                    switchNightRunnable = null
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun checkAutoNightConditions() {
        NotificationCenter.getGlobalInstance()
            .postNotificationName(NotificationCenter.autoNightModeChanged)
    }
}
