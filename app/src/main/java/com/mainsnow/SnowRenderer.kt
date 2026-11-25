
package com.mainsnow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

internal class SnowRenderer {

    private data class Flake(
        var x: Float,
        var y: Float,
        val size: Float,
        val speed: Float,
        val sway: Float,
        var phase: Float,
        val phaseSpeed: Float,
        val pattern: Int,
        val alpha: Int,
        var rotation: Float,
        val rotationSpeed: Float,
        var depth: Float,
        var worldX: Float
    )

    private data class FogBlob(
        var x: Float,
        val yOffset: Float,
        val radiusX: Float,
        val radiusY: Float,
        val speed: Float,
        val alpha: Float
    )

    private val handler = Handler(Looper.getMainLooper())
    private val flakes = mutableListOf<Flake>()
    private val fogBlobs = mutableListOf<FogBlob>()

    private val paint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.FILL
    }

    private val bitmapPaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
        isDither = true
    }

    private val fogPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val glowPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }

    private val cloudPath = Path()
    private val imageDestination = RectF()

    private var backgroundBitmap: Bitmap? = null
    private var scaledBackground: Bitmap? = null
    private var imageContext: Context? = null
    private var windTime = 0f
    private var backgroundImageUri: String? = null
    private var fogEnabled = false

    private var width = 0
    private var height = 0
    private var density = 1f
    private var lastFrameNanos = 0L

    private var currentMode = SnowMode.SPATIAL
    private var currentBackground = SnowBackground.NIGHT
    private var customBackgroundColor = Color.rgb(38, 59, 89)
    private var currentPerspective = SnowPerspective.EYE_LEVEL

    private var snowDensityPercent = 100
    private var snowSpeedPercent = 100
    private var snowAngle = 8
    private var cloudSpeedPercent = 100

    private var cloudsEnabled = true
    private var cloudCoveragePercent = 45
    private var cloudSizePercent = 100
    private var cloudHeightPercent = 100
    private var cloudOpacityPercent = 80
    private var cloudBrightnessPercent = 100
    private var fogDensityPercent = 50
    private var glowStrengthPercent = 40
    private var glowHue = 35
    private var glowPositionPercent = 50

    private var cloudScroll = 0.0
    private val noiseSeed = Random.nextInt(1, 1_000_000)

    private val cloudRows = 56
    private val cloudThickness = 0.4f
    private val cloudScrollCellsPerSecond = 0.3

    private var glowShader: Shader? = null
    private var glowCoreShader: Shader? = null
    private var hazeShader: Shader? = null
    private var groundShader: Shader? = null
    private var blobShader: Shader? = null
    private var effectsKey = 0
    private var glowRadius = 0f
    private var glowCoreRadius = 0f

    private val spatialSpeedMultiplier = 1.6f

    private val patterns = arrayOf(
        intArrayOf(
            0, 1, 0,
            1, 0, 1,
            0, 1, 0
        ),
        intArrayOf(
            0, 1, 0,
            1, 1, 1,
            0, 1, 0
        ),
        intArrayOf(
            1, 0, 1,
            0, 1, 0,
            1, 0, 1
        )
    )

    private val layerCounts = intArrayOf(
        10, 20, 30, 40, 50, 60, 70, 90
    )

    fun setMode(mode: SnowMode) {
        if (currentMode == mode) return

        currentMode = mode
        createFlakes()
        lastFrameNanos = 0L
    }

    fun setSnowDensity(percent: Int) {
        val value = percent.coerceIn(50, 500)

        if (snowDensityPercent == value) return

        snowDensityPercent = value
        createFlakes()
        lastFrameNanos = 0L
    }

    fun setSnowSpeed(percent: Int) {
        snowSpeedPercent = percent.coerceIn(50, 500)
    }

    fun setSnowAngle(degrees: Int) {
        snowAngle = degrees.coerceIn(0, 90)
    }

    fun setCloudSpeed(percent: Int) {
        cloudSpeedPercent = percent.coerceIn(0, 200)
    }

    fun setCloudsEnabled(enabled: Boolean) {
        cloudsEnabled = enabled
    }

    fun setCloudCoverage(percent: Int) {
        cloudCoveragePercent = percent.coerceIn(10, 90)
    }

    fun setCloudSize(percent: Int) {
        cloudSizePercent = percent.coerceIn(50, 200)
    }

    fun setCloudHeight(percent: Int) {
        cloudHeightPercent = percent.coerceIn(30, 300)
    }

    fun setCloudOpacity(percent: Int) {
        cloudOpacityPercent = percent.coerceIn(20, 100)
    }

    fun setCloudBrightness(percent: Int) {
        cloudBrightnessPercent = percent.coerceIn(40, 140)
    }

    fun setFogDensity(percent: Int) {
        fogDensityPercent = percent.coerceIn(0, 100)
    }

    fun setGlowStrength(percent: Int) {
        glowStrengthPercent = percent.coerceIn(0, 100)
    }

    fun setGlowHue(degrees: Int) {
        glowHue = degrees.coerceIn(0, 360)
    }

    fun setGlowPosition(percent: Int) {
        glowPositionPercent = percent.coerceIn(0, 100)
    }

    fun setBackground(
        background: SnowBackground,
        customColor: Int
    ) {
        currentBackground = background
        customBackgroundColor = customColor
    }

    fun setPerspective(perspective: SnowPerspective) {
        if (currentPerspective == perspective) return

        currentPerspective = perspective
    }

    fun setFogEnabled(enabled: Boolean) {
        fogEnabled = enabled
    }

    fun setBackgroundImage(
        context: Context,
        imageUri: String?
    ) {
        if (backgroundImageUri == imageUri) return

        imageContext = context.applicationContext
        backgroundImageUri = imageUri

        scaledBackground?.recycle()
        scaledBackground = null

        backgroundBitmap?.recycle()
        backgroundBitmap = null

        if (imageUri.isNullOrBlank()) return

        try {
            val uri = Uri.parse(imageUri)

            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return
            }

            val targetWidth = max(width, 1)
            val targetHeight = max(height, 1)

            val sampleOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(
                    bounds.outWidth,
                    bounds.outHeight,
                    targetWidth,
                    targetHeight
                )
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            val decoded = context.contentResolver
                .openInputStream(uri)
                ?.use {
                    BitmapFactory.decodeStream(
                        it,
                        null,
                        sampleOptions
                    )
                }

            backgroundBitmap = decoded

        } catch (e: Exception) {
            backgroundBitmap = null
        }
    }

    private fun calculateSampleSize(
        imageWidth: Int,
        imageHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        var sampleSize = 1

        while (
            imageWidth / (sampleSize * 2) >= targetWidth &&
            imageHeight / (sampleSize * 2) >= targetHeight
        ) {
            sampleSize *= 2
        }

        return sampleSize.coerceAtLeast(1)
    }

    fun isAnimationEnabled(): Boolean {
        return true
    }

    fun resize(
        newWidth: Int,
        newHeight: Int,
        newDensity: Float
    ) {
        width = newWidth
        height = newHeight
        density = newDensity.coerceAtLeast(1f)

        val reloadContext = imageContext
        val reloadUri = backgroundImageUri

        if (reloadContext != null && !reloadUri.isNullOrBlank()) {
            backgroundImageUri = null
            setBackgroundImage(reloadContext, reloadUri)
        }

        createFlakes()
        createFogBlobs()
        lastFrameNanos = 0L
    }

    private fun createFlakes() {
        flakes.clear()

        if (width <= 0 || height <= 0) return

        for (layer in 0 until 8) {
            val count = (
                layerCounts[layer] * snowDensityPercent / 100f
            ).roundToInt().coerceAtLeast(1)

            repeat(count) {
                flakes.add(createFlake(layer))
            }
        }
    }

    private fun createFlake(layer: Int): Flake {
        val size: Float
        val speed: Float
        val sway: Float
        val alpha: Int

        when (layer) {
            0 -> {
                size = randomFloat(13f, 18f) * density
                speed = randomFloat(45f, 60f) * density
                sway = randomFloat(8f, 16f) * density
                alpha = Random.nextInt(200, 246)
            }

            1 -> {
                size = randomFloat(11f, 15f) * density
                speed = randomFloat(40f, 55f) * density
                sway = randomFloat(7f, 14f) * density
                alpha = Random.nextInt(185, 236)
            }

            2 -> {
                size = randomFloat(9f, 13f) * density
                speed = randomFloat(38f, 58f) * density
                sway = randomFloat(8f, 16f) * density
                alpha = Random.nextInt(190, 241)
            }

            3 -> {
                size = randomFloat(4.5f, 7f) * density
                speed = randomFloat(22f, 34f) * density
                sway = randomFloat(4f, 10f) * density
                alpha = Random.nextInt(135, 195)
            }

            4 -> {
                size = randomFloat(2f, 3.5f) * density
                speed = randomFloat(10f, 18f) * density
                sway = randomFloat(2f, 5f) * density
                alpha = Random.nextInt(65, 115)
            }

            5 -> {
                size = randomFloat(1.7f, 2.4f) * density
                speed = randomFloat(12f, 17f) * density
                sway = randomFloat(2f, 4f) * density
                alpha = Random.nextInt(45, 75)
            }

            6 -> {
                size = randomFloat(1.2f, 1.8f) * density
                speed = randomFloat(9f, 14f) * density
                sway = randomFloat(1.5f, 3f) * density
                alpha = Random.nextInt(25, 51)
            }

            else -> {
                size = randomFloat(0.8f, 1.3f) * density
                speed = randomFloat(7f, 11f) * density
                sway = randomFloat(1f, 2f) * density
                alpha = Random.nextInt(12, 31)
            }
        }

        return Flake(
            x = Random.nextFloat() * width,
            y = Random.nextFloat() * height,
            size = size,
            speed = speed,
            sway = sway,
            phase = Random.nextFloat() * 6.28f,
            phaseSpeed = randomFloat(0.45f, 1.25f),
            pattern = Random.nextInt(patterns.size),
            alpha = alpha,
            rotation = Random.nextFloat() * 360f,
            rotationSpeed = randomFloat(0.4f, 1.2f) *
                if (Random.nextBoolean()) 1f else -1f,
            depth = Random.nextFloat(),
            worldX = randomFloat(-1.2f, 1.2f)
        )
    }

    private fun createFogBlobs() {
        fogBlobs.clear()

        if (width <= 0 || height <= 0) return

        repeat(7) {
            fogBlobs.add(
                FogBlob(
                    x = randomFloat(-0.4f, 1.4f),
                    yOffset = randomFloat(-0.2f, 0.32f),
                    radiusX = randomFloat(0.45f, 0.9f),
                    radiusY = randomFloat(0.05f, 0.12f),
                    speed = randomFloat(0.006f, 0.02f),
                    alpha = randomFloat(0.18f, 0.38f)
                )
            )
        }
    }

    fun draw(canvas: Canvas) {
        canvas.drawColor(currentSkyColor())

        if (width <= 0 || height <= 0) return

        drawBackgroundImage(canvas)

        val now = System.nanoTime()

        val delta = if (lastFrameNanos == 0L) {
            0.033f
        } else {
            ((now - lastFrameNanos) / 1_000_000_000f)
                .coerceIn(0f, 0.05f)
        }

        lastFrameNanos = now

        drawGlowBase(canvas)

        if (cloudsEnabled) {
            drawClouds(canvas, delta)
        }

        if (fogEnabled) {
            drawFog(canvas, delta)
        }

        drawGlowBloom(canvas)
        drawSpatialSnow(canvas, delta)
    }

    // Картинка один раз масштабируется под экран, дальше рисуется без пересчёта.
    private fun drawBackgroundImage(canvas: Canvas) {
        val bitmap = backgroundBitmap ?: return

        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return
        }

        var scaled = scaledBackground

        if (scaled == null || scaled.isRecycled ||
            scaled.width != width || scaled.height != height
        ) {
            scaled?.recycle()

            scaled = try {
                buildScaledBackground(bitmap)
            } catch (e: Throwable) {
                null
            }

            scaledBackground = scaled
        }

        if (scaled == null) return

        canvas.drawBitmap(scaled, 0f, 0f, null)
    }

    private fun buildScaledBackground(bitmap: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.RGB_565
        )

        val imageRatio = bitmap.width.toFloat() / bitmap.height
        val screenRatio = width.toFloat() / height

        val drawWidth: Float
        val drawHeight: Float

        if (imageRatio > screenRatio) {
            drawHeight = height.toFloat()
            drawWidth = drawHeight * imageRatio
        } else {
            drawWidth = width.toFloat()
            drawHeight = drawWidth / imageRatio
        }

        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f

        imageDestination.set(
            left,
            top,
            left + drawWidth,
            top + drawHeight
        )

        Canvas(result).drawBitmap(
            bitmap,
            null,
            imageDestination,
            bitmapPaint
        )

        return result
    }

    private fun currentSkyColor(): Int {
        return if (currentBackground == SnowBackground.CUSTOM) {
            customBackgroundColor
        } else {
            currentBackground.color
        }
    }

    private fun horizonY(): Float {
        return if (currentPerspective == SnowPerspective.SKY_VIEW) {
            height * 0.88f
        } else {
            height * 0.56f
        }
    }

    private fun glowCenterX(): Float {
        return width * (0.15f + glowPositionPercent / 100f * 0.7f)
    }

    private fun glowCenterY(): Float {
        return horizonY() - height * 0.10f
    }

    private fun glowColor(): Int {
        return Color.HSVToColor(
            floatArrayOf((glowHue % 360).toFloat(), 0.5f, 1f)
        )
    }

    private fun fogColor(): Int {
        return mixColor(
            Color.rgb(222, 233, 243),
            glowColor(),
            glowStrengthPercent / 100f * 0.3f
        )
    }

    private fun mixColor(a: Int, b: Int, amount: Float): Int {
        val k = amount.coerceIn(0f, 1f)

        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
        )
    }

    private fun scaleColor(color: Int, factor: Float): Int {
        return Color.rgb(
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun smooth(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // Шейдеры свечения и тумана пересоздаются только при смене настроек или размера.
    private fun ensureEffectShaders() {
        val key = intArrayOf(
            width,
            height,
            currentSkyColor(),
            glowHue,
            glowPositionPercent,
            glowStrengthPercent,
            fogDensityPercent,
            if (currentPerspective == SnowPerspective.SKY_VIEW) 1 else 0
        ).contentHashCode()

        if (key == effectsKey && glowShader != null) return

        effectsKey = key

        val w = width.toFloat()
        val h = height.toFloat()
        val horizon = horizonY()
        val glow = glowColor()
        val gx = glowCenterX()
        val gy = glowCenterY()
        val d = fogDensityPercent / 100f
        val fog = fogColor()

        glowRadius = w * 1.05f
        glowCoreRadius = w * 0.22f

        glowShader = RadialGradient(
            gx,
            gy,
            glowRadius,
            intArrayOf(
                withAlpha(glow, 255),
                withAlpha(glow, 110),
                withAlpha(glow, 0)
            ),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP
        )

        val core = mixColor(Color.WHITE, glow, 0.35f)

        glowCoreShader = RadialGradient(
            gx,
            gy,
            glowCoreRadius,
            intArrayOf(
                withAlpha(core, 255),
                withAlpha(core, 120),
                withAlpha(core, 0)
            ),
            floatArrayOf(0f, 0.25f, 1f),
            Shader.TileMode.CLAMP
        )

        hazeShader = LinearGradient(
            0f,
            horizon - h * 0.22f,
            0f,
            horizon + h * 0.22f,
            intArrayOf(
                withAlpha(fog, 0),
                withAlpha(fog, (95 * d).toInt()),
                withAlpha(fog, 0)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        groundShader = LinearGradient(
            0f,
            horizon,
            0f,
            h,
            intArrayOf(
                withAlpha(fog, 0),
                withAlpha(fog, (60 * d).toInt()),
                withAlpha(fog, (45 * d).toInt()),
                withAlpha(fog, 0)
            ),
            floatArrayOf(0f, 0.35f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )

        blobShader = RadialGradient(
            0f,
            0f,
            1f,
            intArrayOf(
                withAlpha(fog, 255),
                withAlpha(fog, 110),
                withAlpha(fog, 0)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun drawGlowBase(canvas: Canvas) {
        val strength = glowStrengthPercent / 100f

        if (strength <= 0f) return

        ensureEffectShaders()

        val gx = glowCenterX()
        val gy = glowCenterY()

        glowPaint.shader = glowShader
        glowPaint.alpha = (255 * strength * 0.6f).toInt().coerceIn(0, 255)

        canvas.drawRect(
            gx - glowRadius,
            gy - glowRadius,
            gx + glowRadius,
            gy + glowRadius,
            glowPaint
        )

        glowPaint.shader = glowCoreShader
        glowPaint.alpha = (255 * strength).toInt().coerceIn(0, 255)

        canvas.drawRect(
            gx - glowCoreRadius,
            gy - glowCoreRadius,
            gx + glowCoreRadius,
            gy + glowCoreRadius,
            glowPaint
        )

        glowPaint.shader = null
    }

    // Лёгкое свечение поверх облаков и тумана — как bloom в шейдерах.
    private fun drawGlowBloom(canvas: Canvas) {
        val strength = glowStrengthPercent / 100f

        if (strength <= 0f) return

        ensureEffectShaders()

        val gx = glowCenterX()
        val gy = glowCenterY()

        glowPaint.shader = glowShader
        glowPaint.alpha = (255 * strength * 0.25f).toInt().coerceIn(0, 255)

        canvas.drawRect(
            gx - glowRadius,
            gy - glowRadius,
            gx + glowRadius,
            gy + glowRadius,
            glowPaint
        )

        glowPaint.shader = null
    }

    private fun drawFog(canvas: Canvas, delta: Float) {
        val density = fogDensityPercent / 100f

        if (density <= 0f) return

        ensureEffectShaders()

        val w = width.toFloat()
        val h = height.toFloat()
        val horizon = horizonY()
        val fog = fogColor()

        // Общая дымка на весь экран.
        fogPaint.shader = null
        fogPaint.color = withAlpha(fog, (80 * density).toInt())
        canvas.drawRect(0f, 0f, w, h, fogPaint)

        // Слои тумана у горизонта и у земли.
        fogPaint.shader = hazeShader
        fogPaint.alpha = 255
        canvas.drawRect(0f, horizon - h * 0.22f, w, horizon + h * 0.22f, fogPaint)

        fogPaint.shader = groundShader
        fogPaint.alpha = 255
        canvas.drawRect(0f, horizon, w, h, fogPaint)

        // Плывущие облака тумана.
        fogPaint.shader = blobShader

        val drift = delta * cloudSpeedPercent / 100f

        fogBlobs.forEach { blob ->
            blob.x += blob.speed * drift

            if (blob.x > 1.6f) {
                blob.x = -0.6f
            }

            canvas.save()
            canvas.translate(blob.x * w, horizon + blob.yOffset * h)
            canvas.scale(blob.radiusX * w, blob.radiusY * h)

            fogPaint.alpha = (blob.alpha * density * 255f)
                .toInt()
                .coerceIn(0, 255)

            canvas.drawCircle(0f, 0f, 1f, fogPaint)
            canvas.restore()
        }

        fogPaint.shader = null
    }

    private fun drawSpatialSnow(canvas: Canvas, delta: Float) {
        val speedMultiplier =
            spatialSpeedMultiplier * snowSpeedPercent / 100f

        val angleRadians = Math.toRadians(
            snowAngle.toDouble()
        ).toFloat()

        val horizontalRatio = tan(angleRadians).coerceAtMost(10f)

        // Плавные порывы ветра: общий для всех слоёв, у дальних слабее.
        windTime += delta
        val gust = sin(windTime * 0.40f) * sin(windTime * 0.13f + 1f)

        flakes.forEach { flake ->
            flake.phase += flake.phaseSpeed * delta

            flake.rotation += flake.rotationSpeed * delta

            if (flake.rotation >= 360f) {
                flake.rotation -= 360f
            } else if (flake.rotation <= -360f) {
                flake.rotation += 360f
            }

            if (currentPerspective == SnowPerspective.SKY_VIEW) {
                drawSkyViewFlake(
                    canvas,
                    flake,
                    delta,
                    speedMultiplier,
                    horizontalRatio,
                    gust
                )
            } else {
                val verticalMovement =
                    flake.speed * speedMultiplier * delta

                flake.y += verticalMovement
                flake.x += verticalMovement * horizontalRatio
                flake.x += gust * flake.speed * 0.3f * delta

                flake.x +=
                    sin(flake.phase.toDouble()).toFloat() *
                    flake.sway * delta

                if (flake.x < -flake.size) {
                    flake.x = width + flake.size
                } else if (flake.x > width + flake.size) {
                    flake.x = -flake.size
                }

                if (flake.y > height + flake.size) {
                    flake.y = -height * 0.35f -
                        Random.nextFloat() * height * 0.25f

                    flake.x = Random.nextFloat() * width
                }

                drawFlake(canvas, flake, 1f, 1f)
            }
        }
    }

    private fun drawSkyViewFlake(
        canvas: Canvas,
        flake: Flake,
        delta: Float,
        speedMultiplier: Float,
        horizontalRatio: Float,
        gust: Float
    ) {
        val verticalMovement =
            flake.speed * speedMultiplier * delta

        flake.depth += verticalMovement / (height * 1.45f)

        if (flake.depth > 1f) {
            flake.depth = 0f
            flake.worldX = randomFloat(-1.2f, 1.2f)
        }

        val depth = flake.depth.coerceIn(0f, 1f)
        val scale = 0.18f + depth * 1.4f
        val spaceWidth = 0.9f + depth * 0.7f

        val projectedX =
            width * 0.5f +
            flake.worldX * width * 0.5f * spaceWidth +
            horizontalRatio * depth * height * 1.45f +
            gust * depth * width * 0.06f

        val projectedY =
            -height * 0.35f + depth * height * 1.45f

        val oldX = flake.x
        val oldY = flake.y

        flake.x = projectedX
        flake.y = projectedY

        if (
            projectedY > -flake.size * scale &&
            projectedY < height + flake.size * scale &&
            projectedX > -flake.size * scale &&
            projectedX < width + flake.size * scale
        ) {
            drawFlake(canvas, flake, scale, smooth(0f, 0.15f, depth))
        }

        flake.x = oldX
        flake.y = oldY
    }

    private fun hash2(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263 + noiseSeed * 144665
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)

        return (h and 0xFFFF) / 65535f
    }

    private fun valueNoise(x: Float, y: Float): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val fx = x - xi
        val fy = y - yi

        val sx = fx * fx * (3f - 2f * fx)
        val sy = fy * fy * (3f - 2f * fy)

        val a = hash2(xi, yi)
        val b = hash2(xi + 1, yi)
        val c = hash2(xi, yi + 1)
        val d = hash2(xi + 1, yi + 1)

        val top = a + (b - a) * sx
        val bottom = c + (d - c) * sx

        return top + (bottom - top) * sy
    }

    // Клетка облачного слоя (как текстура облаков в Minecraft): есть облако или нет.
    private fun cloudAt(k: Int, j: Int): Boolean {
        if (j < 1) return false

        val blob = (4f * cloudSizePercent / 100f).coerceAtLeast(1.5f)
        val nx = (k + 0.5f) / blob
        val nz = (j + 0.5f) / blob

        val n = 0.65f * valueNoise(nx, nz) +
            0.35f * valueNoise(nx * 2.1f + 31.7f, nz * 2.1f + 17.3f)

        val threshold = 0.5f + (0.5f - cloudCoveragePercent / 100f) * 0.5f

        return n > threshold
    }

    private fun fillFace(canvas: Canvas, color: Int, alpha: Int) {
        paint.color = color
        paint.alpha = alpha
        canvas.drawPath(cloudPath, paint)
    }

    // Блочные облака: слой объёмных клеток над камерой, вид снизу с перспективой.
    // Грани затенены как в Minecraft: низ 70%, перед 82%, бока 92%.
    private fun drawClouds(canvas: Canvas, delta: Float) {
        cloudScroll += delta *
            (cloudSpeedPercent / 100.0) *
            cloudScrollCellsPerSecond

        val w = width.toFloat()
        val focal = w * 0.8f
        val centerX = w * 0.5f
        val horizon = horizonY()

        val bottomHeight = 8f * cloudHeightPercent / 100f
        val topHeight = bottomHeight + cloudThickness

        val sky = currentSkyColor()

        val luminance = (
            0.299f * Color.red(sky) +
            0.587f * Color.green(sky) +
            0.114f * Color.blue(sky)
        ) / 255f

        val whiteMix = (
            (0.35f + 0.5f * luminance) * cloudBrightnessPercent / 100f
        ).coerceIn(0f, 1f)

        val cloudBase = mixColor(sky, Color.WHITE, whiteMix)
        val haze = mixColor(sky, fogColor(), 0.45f)

        val fogBoost = if (fogEnabled) {
            fogDensityPercent / 100f * 0.4f
        } else {
            0f
        }

        val glowStrength = glowStrengthPercent / 100f
        val glowRgb = glowColor()
        val glowX = glowCenterX()
        val glowY = glowCenterY()

        val baseAlpha = cloudOpacityPercent / 100f * 255f

        val scrollFloor = floor(cloudScroll).toInt()
        val scrollFrac = (cloudScroll - scrollFloor).toFloat()

        for (j in cloudRows downTo 1) {
            val t = j / cloudRows.toFloat()
            val fade = (1f - smooth(0.55f, 1f, t)) * smooth(0f, 5f, j.toFloat())

            if (fade <= 0.01f) continue

            val zNear = j + 1f
            val zFar = j + 2f
            val sNear = focal / zNear
            val sFar = focal / zFar

            val bottomNearY = horizon - bottomHeight * sNear
            val bottomFarY = horizon - bottomHeight * sFar

            // Вся строка выше верхнего края экрана.
            if (bottomFarY < 0f) continue

            val topNearY = horizon - topHeight * sNear
            val topFarY = horizon - topHeight * sFar

            val rowAlpha = (baseAlpha * fade).toInt().coerceIn(0, 255)

            val hazeMix = (
                t.pow(1.5f) * 0.55f + fogBoost * t
            ).coerceIn(0f, 0.9f)

            val rowBase = mixColor(cloudBase, haze, hazeMix)
            val bottomColor = scaleColor(rowBase, 0.70f)
            val frontColor = scaleColor(rowBase, 0.82f)
            val sideColor = scaleColor(rowBase, 0.92f)

            val xMinWorld = -centerX / sFar
            val xMaxWorld = (w - centerX) / sFar

            val kStart = floor(xMinWorld + cloudScroll).toInt() - 1
            val kEnd = floor(xMaxWorld + cloudScroll).toInt() + 1

            for (k in kStart..kEnd) {
                if (!cloudAt(k, j)) continue

                val xl = (k - scrollFloor) - scrollFrac
                val xr = xl + 1f

                val nearLeft = centerX + xl * sNear
                val nearRight = centerX + xr * sNear
                val farLeft = centerX + xl * sFar
                val farRight = centerX + xr * sFar

                if (
                    max(nearRight, farRight) < 0f ||
                    min(nearLeft, farLeft) > w
                ) {
                    continue
                }

                // Подсветка граней рядом с источником свечения.
                var lit = 0f

                if (glowStrength > 0f) {
                    val cellX = (nearLeft + nearRight + farLeft + farRight) / 4f
                    val cellY = (bottomNearY + bottomFarY) / 2f
                    val dx = (cellX - glowX) / w
                    val dy = (cellY - glowY) / w * 1.2f
                    val distance = sqrt(dx * dx + dy * dy)
                    val proximity = (1f - distance / 0.85f).coerceIn(0f, 1f)

                    lit = proximity * proximity * glowStrength * 0.75f
                }

                // Низ клетки.
                cloudPath.reset()
                cloudPath.moveTo(nearLeft, bottomNearY)
                cloudPath.lineTo(nearRight, bottomNearY)
                cloudPath.lineTo(farRight, bottomFarY)
                cloudPath.lineTo(farLeft, bottomFarY)
                cloudPath.close()
                fillFace(canvas, mixColor(bottomColor, glowRgb, lit), rowAlpha)

                // Боковая грань, обращённая к центру экрана.
                if (xr <= 0f) {
                    if (!cloudAt(k + 1, j)) {
                        cloudPath.reset()
                        cloudPath.moveTo(nearRight, topNearY)
                        cloudPath.lineTo(farRight, topFarY)
                        cloudPath.lineTo(farRight, bottomFarY)
                        cloudPath.lineTo(nearRight, bottomNearY)
                        cloudPath.close()
                        fillFace(
                            canvas,
                            mixColor(sideColor, glowRgb, lit * 0.8f),
                            rowAlpha
                        )
                    }
                } else if (xl >= 0f) {
                    if (!cloudAt(k - 1, j)) {
                        cloudPath.reset()
                        cloudPath.moveTo(nearLeft, topNearY)
                        cloudPath.lineTo(farLeft, topFarY)
                        cloudPath.lineTo(farLeft, bottomFarY)
                        cloudPath.lineTo(nearLeft, bottomNearY)
                        cloudPath.close()
                        fillFace(
                            canvas,
                            mixColor(sideColor, glowRgb, lit * 0.8f),
                            rowAlpha
                        )
                    }
                }

                // Передняя грань, если спереди нет соседней клетки.
                if (!cloudAt(k, j - 1)) {
                    cloudPath.reset()
                    cloudPath.moveTo(nearLeft, topNearY)
                    cloudPath.lineTo(nearRight, topNearY)
                    cloudPath.lineTo(nearRight, bottomNearY)
                    cloudPath.lineTo(nearLeft, bottomNearY)
                    cloudPath.close()
                    fillFace(
                        canvas,
                        mixColor(frontColor, glowRgb, lit * 0.8f),
                        rowAlpha
                    )
                }
            }
        }
    }

    private fun drawFlake(
        canvas: Canvas,
        flake: Flake,
        scale: Float,
        fade: Float
    ) {
        val renderedSize = flake.size * scale

        // Мягкое мерцание: крупные снежинки чуть «дышат».
        val twinkle = if (flake.size > 6f * density) {
            0.88f + 0.12f * sin(flake.phase * 3f)
        } else {
            1f
        }

        val alpha = (flake.alpha * fade * twinkle).toInt().coerceIn(0, 255)

        if (alpha <= 2) return

        paint.color = Color.rgb(220, 235, 255)
        paint.alpha = alpha

        // Мелкую снежинку не видно как узор: один квадрат без поворота и save/restore.
        if (renderedSize < 4.5f * density) {
            val side = renderedSize * 0.67f

            canvas.drawRect(
                flake.x,
                flake.y,
                flake.x + side,
                flake.y + side,
                paint
            )

            return
        }

        val pattern = patterns[flake.pattern]
        val pixelSize = renderedSize / 3f

        val centerX = flake.x + renderedSize / 2f
        val centerY = flake.y + renderedSize / 2f

        canvas.save()
        canvas.rotate(flake.rotation, centerX, centerY)

        for (row in 0 until 3) {
            for (column in 0 until 3) {
                if (pattern[row * 3 + column] == 1) {
                    val left = flake.x + column * pixelSize
                    val top = flake.y + row * pixelSize

                    canvas.drawRect(
                        left,
                        top,
                        left + pixelSize,
                        top + pixelSize,
                        paint
                    )
                }
            }
        }

        canvas.restore()
    }

    fun scheduleFrame(callback: Runnable, delayMillis: Long = 33L) {
        handler.postDelayed(callback, delayMillis.coerceIn(1L, 33L))
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        lastFrameNanos = 0L
    }

    private fun randomFloat(min: Float, max: Float): Float {
        return min + Random.nextFloat() * (max - min)
    }
}
