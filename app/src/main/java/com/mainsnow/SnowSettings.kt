
package com.mainsnow

import android.content.Context
import android.graphics.Color

enum class SnowMode(
    val key: String,
    val title: String
) {
    SPATIAL("spatial", "Снегопад"),
    BEE("bee", "Пчела"),
    ROOM("room", "Рождественская комната")
}

enum class BeeTime(
    val key: String,
    val title: String
) {
    MORNING("morning", "Утро"),
    DAY("day", "День"),
    EVENING("evening", "Вечер"),
    NIGHT("night", "Ночь")
}

enum class SnowBackground(
    val key: String,
    val title: String,
    val color: Int
) {
    NIGHT("night", "Ночная синь", Color.rgb(7, 20, 38)),
    TWILIGHT("twilight", "Сумерки", Color.rgb(38, 59, 89)),
    DAWN("dawn", "Предрассветное небо", Color.rgb(82, 100, 125)),
    OVERCAST("overcast", "Пасмурное небо", Color.rgb(57, 73, 88)),
    CUSTOM("custom", "Свой вариант", Color.rgb(38, 59, 89))
}

enum class SnowPerspective(
    val key: String,
    val title: String,
    val angle: Float
) {
    EYE_LEVEL("eye_level", "На уровне глаз", 0f),
    SKY_VIEW("sky_view", "Взгляд в небо", 30f)
}

object SnowSettings {

    private const val PREFS_NAME = "main_snow_settings"

    private const val MODE_KEY = "snow_mode"
    private const val BACKGROUND_KEY = "snow_background"
    private const val CUSTOM_COLOR_KEY = "custom_background_color"
    private const val PERSPECTIVE_KEY = "snow_perspective"

    private const val SNOW_DENSITY_KEY = "snow_density"
    private const val SNOW_SPEED_KEY = "snow_speed"
    private const val SNOW_ANGLE_KEY = "snow_angle"
    private const val CLOUD_SPEED_KEY = "cloud_speed"

    private const val BACKGROUND_IMAGE_KEY = "background_image_uri"
    private const val FOG_ENABLED_KEY = "fog_enabled"

    private const val CLOUDS_ENABLED_KEY = "clouds_enabled"
    private const val CLOUD_COVERAGE_KEY = "cloud_coverage"
    private const val CLOUD_SIZE_KEY = "cloud_size"
    private const val CLOUD_HEIGHT_KEY = "cloud_height"
    private const val CLOUD_OPACITY_KEY = "cloud_opacity"
    private const val CLOUD_BRIGHTNESS_KEY = "cloud_brightness"
    private const val FOG_DENSITY_KEY = "fog_density"
    private const val GLOW_STRENGTH_KEY = "glow_strength"
    private const val GLOW_HUE_KEY = "glow_hue"
    private const val GLOW_POSITION_KEY = "glow_position"

    private const val DEFAULT_DENSITY = 100
    private const val DEFAULT_SNOW_SPEED = 100
    private const val DEFAULT_SNOW_ANGLE = 8
    private const val DEFAULT_CLOUD_SPEED = 100

    private fun getPreferences(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    fun getMode(context: Context): SnowMode {
        val savedKey = getPreferences(context).getString(
            MODE_KEY,
            SnowMode.SPATIAL.key
        )

        return SnowMode.values().firstOrNull {
            it.key == savedKey
        } ?: SnowMode.SPATIAL
    }

    fun setMode(context: Context, mode: SnowMode) {
        getPreferences(context).edit()
            .putString(MODE_KEY, mode.key)
            .apply()
    }

    fun getBeeTime(context: Context): BeeTime {
        val savedKey = getPreferences(context).getString("bee_time", BeeTime.DAY.key)

        return BeeTime.values().firstOrNull {
            it.key == savedKey
        } ?: BeeTime.DAY
    }

    fun setBeeTime(context: Context, time: BeeTime) {
        getPreferences(context).edit()
            .putString("bee_time", time.key)
            .apply()
    }

    // Автоматический режим: время суток берётся из часов телефона. Ручной выбор не зависит от часов.
    fun getBeeAuto(context: Context): Boolean =
        getPreferences(context).getBoolean("bee_auto", false)

    fun setBeeAuto(context: Context, auto: Boolean) {
        getPreferences(context).edit()
            .putBoolean("bee_auto", auto)
            .apply()
    }

    // 05:00-09:00 утро, 09:00-17:00 день, 17:00-21:00 вечер, 21:00-05:00 ночь.
    fun beeTimeForNow(): BeeTime {
        val c = java.util.Calendar.getInstance()
        val minutes = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)

        return when {
            minutes in 300 until 540 -> BeeTime.MORNING
            minutes in 540 until 1020 -> BeeTime.DAY
            minutes in 1020 until 1260 -> BeeTime.EVENING
            else -> BeeTime.NIGHT
        }
    }

    fun resolveBeeTime(context: Context): BeeTime =
        if (getBeeAuto(context)) beeTimeForNow() else getBeeTime(context)

    fun getBackground(context: Context): SnowBackground {
        val savedKey = getPreferences(context).getString(
            BACKGROUND_KEY,
            SnowBackground.NIGHT.key
        )

        return SnowBackground.values().firstOrNull {
            it.key == savedKey
        } ?: SnowBackground.NIGHT
    }

    fun setBackground(
        context: Context,
        background: SnowBackground
    ) {
        getPreferences(context).edit()
            .putString(BACKGROUND_KEY, background.key)
            .apply()
    }

    fun getCustomBackgroundColor(context: Context): Int {
        val savedColor = getPreferences(context).getString(
            CUSTOM_COLOR_KEY,
            "#263B59"
        ) ?: "#263B59"

        return try {
            Color.parseColor(savedColor)
        } catch (e: IllegalArgumentException) {
            Color.rgb(38, 59, 89)
        }
    }

    fun setCustomBackgroundColor(
        context: Context,
        colorHex: String
    ): Boolean {
        val color = try {
            Color.parseColor(colorHex)
        } catch (e: IllegalArgumentException) {
            return false
        }

        val formattedColor = String.format(
            "#%06X",
            0xFFFFFF and color
        )

        getPreferences(context).edit()
            .putString(CUSTOM_COLOR_KEY, formattedColor)
            .apply()

        return true
    }

    fun getBackgroundImageUri(context: Context): String? {
        return getPreferences(context).getString(
            BACKGROUND_IMAGE_KEY,
            null
        )
    }

    fun setBackgroundImageUri(
        context: Context,
        uri: String?
    ) {
        getPreferences(context).edit().apply {
            if (uri.isNullOrBlank()) {
                remove(BACKGROUND_IMAGE_KEY)
            } else {
                putString(BACKGROUND_IMAGE_KEY, uri)
            }
        }.apply()
    }

    fun isFogEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(
            FOG_ENABLED_KEY,
            false
        )
    }

    fun setFogEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit()
            .putBoolean(FOG_ENABLED_KEY, enabled)
            .apply()
    }

    fun getPerspective(context: Context): SnowPerspective {
        val savedKey = getPreferences(context).getString(
            PERSPECTIVE_KEY,
            SnowPerspective.EYE_LEVEL.key
        )

        return SnowPerspective.values().firstOrNull {
            it.key == savedKey
        } ?: SnowPerspective.EYE_LEVEL
    }

    fun setPerspective(
        context: Context,
        perspective: SnowPerspective
    ) {
        getPreferences(context).edit()
            .putString(PERSPECTIVE_KEY, perspective.key)
            .apply()
    }

    fun getSnowDensity(context: Context): Int {
        return getPreferences(context).getInt(
            SNOW_DENSITY_KEY,
            DEFAULT_DENSITY
        ).coerceIn(50, 500)
    }

    fun setSnowDensity(context: Context, value: Int) {
        getPreferences(context).edit()
            .putInt(SNOW_DENSITY_KEY, value.coerceIn(50, 500))
            .apply()
    }

    fun getSnowSpeed(context: Context): Int {
        return getPreferences(context).getInt(
            SNOW_SPEED_KEY,
            DEFAULT_SNOW_SPEED
        ).coerceIn(50, 500)
    }

    fun setSnowSpeed(context: Context, value: Int) {
        getPreferences(context).edit()
            .putInt(SNOW_SPEED_KEY, value.coerceIn(50, 500))
            .apply()
    }

    fun getSnowAngle(context: Context): Int {
        return getPreferences(context).getInt(
            SNOW_ANGLE_KEY,
            DEFAULT_SNOW_ANGLE
        ).coerceIn(0, 90)
    }

    fun setSnowAngle(context: Context, value: Int) {
        getPreferences(context).edit()
            .putInt(SNOW_ANGLE_KEY, value.coerceIn(0, 90))
            .apply()
    }

    fun getCloudSpeed(context: Context): Int {
        return getPreferences(context).getInt(
            CLOUD_SPEED_KEY,
            DEFAULT_CLOUD_SPEED
        ).coerceIn(0, 200)
    }

    fun setCloudSpeed(context: Context, value: Int) {
        getPreferences(context).edit()
            .putInt(CLOUD_SPEED_KEY, value.coerceIn(0, 200))
            .apply()
    }

    fun areCloudsEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(CLOUDS_ENABLED_KEY, true)
    }

    fun getCloudCoverage(context: Context): Int {
        return getPreferences(context)
            .getInt(CLOUD_COVERAGE_KEY, 45)
            .coerceIn(10, 90)
    }

    fun getCloudSize(context: Context): Int {
        return getPreferences(context)
            .getInt(CLOUD_SIZE_KEY, 100)
            .coerceIn(50, 200)
    }

    fun getCloudHeight(context: Context): Int {
        return getPreferences(context)
            .getInt(CLOUD_HEIGHT_KEY, 100)
            .coerceIn(30, 300)
    }

    fun getCloudOpacity(context: Context): Int {
        return getPreferences(context)
            .getInt(CLOUD_OPACITY_KEY, 80)
            .coerceIn(20, 100)
    }

    fun getCloudBrightness(context: Context): Int {
        return getPreferences(context)
            .getInt(CLOUD_BRIGHTNESS_KEY, 100)
            .coerceIn(40, 140)
    }

    fun getFogDensity(context: Context): Int {
        return getPreferences(context)
            .getInt(FOG_DENSITY_KEY, 50)
            .coerceIn(0, 100)
    }

    fun getGlowStrength(context: Context): Int {
        return getPreferences(context)
            .getInt(GLOW_STRENGTH_KEY, 40)
            .coerceIn(0, 100)
    }

    fun getGlowHue(context: Context): Int {
        return getPreferences(context)
            .getInt(GLOW_HUE_KEY, 35)
            .coerceIn(0, 360)
    }

    fun getGlowPosition(context: Context): Int {
        return getPreferences(context)
            .getInt(GLOW_POSITION_KEY, 50)
            .coerceIn(0, 100)
    }

    // Последняя ошибка комнаты: пишется при сбое запуска OpenGL и показывается в меню.
    fun getRoomError(context: Context): String? {
        return getPreferences(context).getString("room_last_error", null)
    }

    fun setRoomError(context: Context, text: String?) {
        val editor = getPreferences(context).edit()

        if (text == null) editor.remove("room_last_error") else editor.putString("room_last_error", text)

        editor.apply()
    }
}
