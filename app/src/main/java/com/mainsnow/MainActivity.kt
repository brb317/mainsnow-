
package com.mainsnow

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_BACKGROUND_IMAGE = 1201
        private const val SETTINGS_NAME = "main_snow_settings"
        private const val IMAGE_URI_KEY = "background_image_uri"
        private const val FOG_ENABLED_KEY = "fog_enabled"
    }

    private val backgroundColor = Color.rgb(7, 20, 38)
    private val selectedColor = Color.rgb(45, 85, 125)
    private val normalColor = Color.rgb(25, 42, 62)

    private val backgroundButtons = mutableMapOf<SnowBackground, Button>()
    private val perspectiveButtons = mutableMapOf<SnowPerspective, Button>()
    private val modeButtons = mutableMapOf<SnowMode, Button>()
    private val beeTimeButtons = mutableMapOf<BeeTime, Button>()

    private lateinit var wallpaperButton: Button
    private lateinit var beeAutoButton: Button

    private lateinit var customColorInput: EditText
    private lateinit var imageStatus: TextView
    private lateinit var fogSwitch: Switch

    private lateinit var densitySlider: SliderControl
    private lateinit var speedSlider: SliderControl
    private lateinit var angleSlider: SliderControl
    private lateinit var cloudSpeedSlider: SliderControl
    private lateinit var cloudsSwitch: Switch
    private lateinit var cloudCoverageSlider: SliderControl
    private lateinit var cloudSizeSlider: SliderControl
    private lateinit var cloudHeightSlider: SliderControl
    private lateinit var cloudOpacitySlider: SliderControl
    private lateinit var cloudBrightnessSlider: SliderControl
    private lateinit var fogDensitySlider: SliderControl
    private lateinit var glowStrengthSlider: SliderControl
    private lateinit var glowHueSlider: SliderControl
    private lateinit var glowPositionSlider: SliderControl

    private val settings by lazy {
        getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE)
    }

    private class SliderControl(
        private val seekBar: SeekBar,
        private val valueText: TextView,
        private val minValue: Int,
        private val maxValue: Int,
        private val suffix: String,
        private val onValueChanged: (Int) -> Unit
    ) {
        fun setValue(value: Int) {
            val safeValue = value.coerceIn(minValue, maxValue)
            seekBar.progress = safeValue - minValue
            valueText.text = "${valueText.tag}: $safeValue$suffix"
            onValueChanged(safeValue)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(backgroundColor)
        }

        val title = TextView(this).apply {
            text = "mainSnow"
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val description = TextView(this).apply {
            text = getString(R.string.app_description)
            textSize = 16f
            setTextColor(Color.rgb(180, 205, 230))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(24))
        }

        content.addView(title)
        content.addView(description)

        addSectionTitle(content, "Режим обоев")

        SnowMode.values().forEach { mode ->
            val button = createButton(mode.title)

            button.setOnClickListener {
                SnowSettings.setMode(this, mode)
                updateSelections()

                Toast.makeText(
                    this,
                    "Нажмите «Установить живые обои», чтобы применить режим",
                    Toast.LENGTH_SHORT
                ).show()
            }

            modeButtons[mode] = button
            addButton(content, button)
        }

        addSectionTitle(content, "Пчела: время суток")

        beeAutoButton = createButton("Авто (по времени телефона)")

        beeAutoButton.setOnClickListener {
            SnowSettings.setBeeAuto(this, true)
            updateSelections()
        }

        addButton(content, beeAutoButton)

        BeeTime.values().forEach { time ->
            val button = createButton(time.title)

            button.setOnClickListener {
                SnowSettings.setBeeAuto(this, false)
                SnowSettings.setBeeTime(this, time)
                updateSelections()
            }

            beeTimeButtons[time] = button
            addButton(content, button)
        }

        val roomError = SnowSettings.getRoomError(this)

        if (roomError != null) {
            content.addView(
                TextView(this).apply {
                    text = "Последняя ошибка комнаты: $roomError"
                    textSize = 12f
                    setTextColor(Color.rgb(255, 170, 150))
                    setPadding(0, dp(8), 0, dp(8))
                }
            )
        }

        addSectionTitle(content, "Режим снегопада")

        val spatialButton = createButton("Пространственный").apply {
            backgroundTintList = ColorStateList.valueOf(selectedColor)
        }

        spatialButton.setOnClickListener {
            resetSnowSettings()
        }

        addButton(content, spatialButton)

        addSectionTitle(content, "Настройки снегопада")

        densitySlider = createSlider(
            content,
            title = "Плотность снега",
            min = 50,
            max = 500,
            initial = settings.getInt("snow_density", 100),
            suffix = "%"
        ) {
            settings.edit().putInt("snow_density", it).apply()
        }

        speedSlider = createSlider(
            content,
            title = "Скорость снега",
            min = 50,
            max = 500,
            initial = settings.getInt("snow_speed", 100),
            suffix = "%"
        ) {
            settings.edit().putInt("snow_speed", it).apply()
        }

        angleSlider = createSlider(
            content,
            title = "Угол падения снега",
            min = 0,
            max = 90,
            initial = settings.getInt("snow_angle", 8),
            suffix = "°"
        ) {
            settings.edit().putInt("snow_angle", it).apply()
        }

        addSectionTitle(content, "Облака")

        cloudsSwitch = Switch(this).apply {
            text = "Облака в стиле Minecraft"
            textSize = 15f
            setTextColor(Color.WHITE)
            isChecked = settings.getBoolean("clouds_enabled", true)

            setOnCheckedChangeListener { _, checked ->
                settings.edit()
                    .putBoolean("clouds_enabled", checked)
                    .apply()
            }
        }

        content.addView(
            cloudsSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
            )
        )

        cloudSpeedSlider = createSlider(
            content,
            title = "Скорость облаков",
            min = 0,
            max = 200,
            initial = settings.getInt("cloud_speed", 100),
            suffix = "%"
        ) {
            settings.edit().putInt("cloud_speed", it).apply()
        }

        cloudCoverageSlider = createSlider(
            content,
            title = "Количество облаков",
            min = 10,
            max = 90,
            initial = settings.getInt("cloud_coverage", 45),
            suffix = "%"
        ) {
            settings.edit().putInt("cloud_coverage", it).apply()
        }

        cloudSizeSlider = createSlider(
            content,
            title = "Размер облаков",
            min = 50,
            max = 200,
            initial = settings.getInt("cloud_size", 100),
            suffix = "%"
        ) {
            settings.edit().putInt("cloud_size", it).apply()
        }

        cloudHeightSlider = createSlider(
            content,
            title = "Высота облаков",
            min = 30,
            max = 300,
            initial = settings.getInt("cloud_height", 100),
            suffix = "%"
        ) {
            settings.edit().putInt("cloud_height", it).apply()
        }

        cloudOpacitySlider = createSlider(
            content,
            title = "Непрозрачность облаков",
            min = 20,
            max = 100,
            initial = settings.getInt("cloud_opacity", 80),
            suffix = "%"
        ) {
            settings.edit().putInt("cloud_opacity", it).apply()
        }

        cloudBrightnessSlider = createSlider(
            content,
            title = "Яркость облаков",
            min = 40,
            max = 140,
            initial = settings.getInt("cloud_brightness", 100),
            suffix = "%"
        ) {
            settings.edit().putInt("cloud_brightness", it).apply()
        }

        addSectionTitle(content, "Свечение")

        glowStrengthSlider = createSlider(
            content,
            title = "Сила свечения",
            min = 0,
            max = 100,
            initial = settings.getInt("glow_strength", 40),
            suffix = "%"
        ) {
            settings.edit().putInt("glow_strength", it).apply()
        }

        glowHueSlider = createSlider(
            content,
            title = "Оттенок свечения",
            min = 0,
            max = 360,
            initial = settings.getInt("glow_hue", 35),
            suffix = "°"
        ) {
            settings.edit().putInt("glow_hue", it).apply()
        }

        glowPositionSlider = createSlider(
            content,
            title = "Положение свечения",
            min = 0,
            max = 100,
            initial = settings.getInt("glow_position", 50),
            suffix = "%"
        ) {
            settings.edit().putInt("glow_position", it).apply()
        }

        val resetSkyButton = createButton("Сбросить облака, туман и свечение")

        resetSkyButton.setOnClickListener {
            resetSkySettings()
        }

        addButton(content, resetSkyButton)

        addSectionTitle(content, "Цвет неба")

        SnowBackground.values().forEach { background ->
            val button = createButton(background.title)

            button.setOnClickListener {
                SnowSettings.setBackground(this, background)
                updateSelections()
            }

            backgroundButtons[background] = button
            addButton(content, button)
        }

        customColorInput = EditText(this).apply {
            setSingleLine(true)
            hint = "#263B59"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(150, 170, 195))
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(14), 0, dp(14), 0)
            backgroundTintList = ColorStateList.valueOf(
                Color.rgb(110, 140, 175)
            )
        }

        content.addView(
            customColorInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        )

        val applyColorButton = createButton("Применить свой цвет")

        applyColorButton.setOnClickListener {
            val colorHex = customColorInput.text.toString().trim()

            if (SnowSettings.setCustomBackgroundColor(this, colorHex)) {
                SnowSettings.setBackground(this, SnowBackground.CUSTOM)
                settings.edit().remove(IMAGE_URI_KEY).apply()
                updateSelections()

                Toast.makeText(
                    this,
                    "Собственный цвет применён",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                customColorInput.error = "Введи цвет в формате #RRGGBB"
            }
        }

        addButton(content, applyColorButton)

        addSectionTitle(content, "Изображение неба")

        val chooseImageButton = createButton("Выбрать изображение")

        chooseImageButton.setOnClickListener {
            openImagePicker()
        }

        addButton(content, chooseImageButton)

        imageStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(180, 205, 230))
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }

        content.addView(
            imageStatus,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val removeImageButton = createButton("Убрать изображение")

        removeImageButton.setOnClickListener {
            settings.edit().remove(IMAGE_URI_KEY).apply()

            updateImageStatus()

            Toast.makeText(
                this,
                "Изображение удалено",
                Toast.LENGTH_SHORT
            ).show()
        }

        addButton(content, removeImageButton)

        addSectionTitle(content, "Эффект тумана")

        fogSwitch = Switch(this).apply {
            text = "Туман поверх всего экрана"
            textSize = 15f
            setTextColor(Color.WHITE)
            isChecked = settings.getBoolean(FOG_ENABLED_KEY, false)

            setOnCheckedChangeListener { _, checked ->
                settings.edit()
                    .putBoolean(FOG_ENABLED_KEY, checked)
                    .apply()
            }
        }

        content.addView(
            fogSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
            )
        )

        fogDensitySlider = createSlider(
            content,
            title = "Густота тумана",
            min = 0,
            max = 100,
            initial = settings.getInt("fog_density", 50),
            suffix = "%"
        ) {
            settings.edit().putInt("fog_density", it).apply()
        }

        addSectionTitle(content, "Перспектива")

        SnowPerspective.values().forEach { perspective ->
            val button = createButton(
                if (perspective == SnowPerspective.SKY_VIEW) {
                    "${perspective.title} · ${perspective.angle.toInt()}°"
                } else {
                    perspective.title
                }
            )

            button.setOnClickListener {
                SnowSettings.setPerspective(this, perspective)
                updateSelections()
            }

            perspectiveButtons[perspective] = button
            addButton(content, button)
        }

        wallpaperButton = createButton(
            getString(R.string.open_wallpaper_settings)
        ).apply {
            backgroundTintList = ColorStateList.valueOf(selectedColor)
        }

        wallpaperButton.setOnClickListener {
            openWallpaperPicker()
        }

        content.addView(
            wallpaperButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
            ).apply {
                topMargin = dp(20)
                bottomMargin = dp(16)
            }
        )

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(backgroundColor)
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        setContentView(scrollView)

        updateSelections()
        updateImageStatus()
    }

    private fun openImagePicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }

            startActivityForResult(
                intent,
                REQUEST_BACKGROUND_IMAGE
            )
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Не удалось открыть галерею",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    @Deprecated("Deprecated in Android API, retained for compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (
            requestCode != REQUEST_BACKGROUND_IMAGE ||
            resultCode != RESULT_OK
        ) {
            return
        }

        val uri: Uri = data?.data ?: return

        try {
            val flags = data.flags and
                Intent.FLAG_GRANT_READ_URI_PERMISSION

            contentResolver.takePersistableUriPermission(
                uri,
                flags
            )

            settings.edit()
                .putString(IMAGE_URI_KEY, uri.toString())
                .apply()

            updateImageStatus()

            Toast.makeText(
                this,
                "Изображение выбрано",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Не удалось сохранить доступ к изображению",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateImageStatus() {
        val imageUri = settings.getString(IMAGE_URI_KEY, null)

        imageStatus.text = if (imageUri.isNullOrEmpty()) {
            "Изображение не выбрано"
        } else {
            "Собственное изображение выбрано"
        }
    }

    private fun createSlider(
        layout: LinearLayout,
        title: String,
        min: Int,
        max: Int,
        initial: Int,
        suffix: String,
        onValueChanged: (Int) -> Unit
    ): SliderControl {

        val safeInitial = initial.coerceIn(min, max)

        val valueText = TextView(this).apply {
            tag = title
            text = "$title: $safeInitial$suffix"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)
        }

        layout.addView(
            valueText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val seekBar = SeekBar(this).apply {
            this.max = max - min
            progress = safeInitial - min
        }

        layout.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val control = SliderControl(
            seekBar,
            valueText,
            min,
            max,
            suffix,
            onValueChanged
        )

        seekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    bar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    val value = progress + min
                    valueText.text = "$title: $value$suffix"

                    if (fromUser) {
                        onValueChanged(value)
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {}

                override fun onStopTrackingTouch(bar: SeekBar?) {}
            }
        )

        return control
    }

    private fun resetSnowSettings() {
        densitySlider.setValue(100)
        speedSlider.setValue(100)
        angleSlider.setValue(8)
        cloudSpeedSlider.setValue(100)

        Toast.makeText(
            this,
            "Исходные настройки снегопада восстановлены",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun resetSkySettings() {
        cloudsSwitch.isChecked = true
        cloudSpeedSlider.setValue(100)
        cloudCoverageSlider.setValue(45)
        cloudSizeSlider.setValue(100)
        cloudHeightSlider.setValue(100)
        cloudOpacitySlider.setValue(80)
        cloudBrightnessSlider.setValue(100)
        fogDensitySlider.setValue(50)
        glowStrengthSlider.setValue(40)
        glowHueSlider.setValue(35)
        glowPositionSlider.setValue(50)

        Toast.makeText(
            this,
            "Настройки неба восстановлены",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun addSectionTitle(
        layout: LinearLayout,
        text: String
    ) {
        val title = TextView(this).apply {
            this.text = text
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(10))
        }

        layout.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun createButton(text: String): Button {
        return Button(this).apply {
            this.text = text
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(normalColor)
        }
    }

    private fun addButton(
        layout: LinearLayout,
        button: Button
    ) {
        layout.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
            ).apply {
                bottomMargin = dp(6)
            }
        )
    }

    private fun updateSelections() {
        val currentBackground = SnowSettings.getBackground(this)
        val currentPerspective = SnowSettings.getPerspective(this)

        backgroundButtons.forEach { (background, button) ->
            val selected = background == currentBackground

            button.text = if (selected) {
                "✓  ${background.title}"
            } else {
                background.title
            }

            button.backgroundTintList = ColorStateList.valueOf(
                if (selected) selectedColor else normalColor
            )
        }

        perspectiveButtons.forEach { (perspective, button) ->
            val selected = perspective == currentPerspective

            val title = if (perspective == SnowPerspective.SKY_VIEW) {
                "${perspective.title} · ${perspective.angle.toInt()}°"
            } else {
                perspective.title
            }

            button.text = if (selected) {
                "✓  $title"
            } else {
                title
            }

            button.backgroundTintList = ColorStateList.valueOf(
                if (selected) selectedColor else normalColor
            )
        }

        val currentMode = SnowSettings.getMode(this)
        val currentBeeTime = SnowSettings.getBeeTime(this)

        modeButtons.forEach { (mode, button) ->
            val selected = mode == currentMode

            button.text = if (selected) "✓  ${mode.title}" else mode.title
            button.backgroundTintList = ColorStateList.valueOf(
                if (selected) selectedColor else normalColor
            )
        }

        val beeAuto = SnowSettings.getBeeAuto(this)

        if (::beeAutoButton.isInitialized) {
            beeAutoButton.text = if (beeAuto) "✓  Авто (по времени телефона)" else "Авто (по времени телефона)"
            beeAutoButton.backgroundTintList = ColorStateList.valueOf(
                if (beeAuto) selectedColor else normalColor
            )
        }

        beeTimeButtons.forEach { (time, button) ->
            val selected = !beeAuto && time == currentBeeTime

            button.text = if (selected) "✓  ${time.title}" else time.title
            button.backgroundTintList = ColorStateList.valueOf(
                if (selected) selectedColor else normalColor
            )
        }

        if (::wallpaperButton.isInitialized) {
            wallpaperButton.text = when (currentMode) {
                SnowMode.BEE -> "Установить обои: Пчела"
                SnowMode.ROOM -> "Установить обои: Рождественская комната"
                else -> getString(R.string.open_wallpaper_settings)
            }
        }
    }

    private fun openWallpaperPicker() {
        try {
            val intent = Intent(
                WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER
            ).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(
                        this@MainActivity,
                        when (SnowSettings.getMode(this@MainActivity)) {
                            SnowMode.BEE -> BeeWallpaperService::class.java
                            SnowMode.ROOM -> RoomWallpaperService::class.java
                            else -> MainSnowWallpaperService::class.java
                        }
                    )
                )
            }

            startActivity(intent)

        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                )
            } catch (secondException: Exception) {
                Toast.makeText(
                    this,
                    getString(R.string.wallpaper_unavailable),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
