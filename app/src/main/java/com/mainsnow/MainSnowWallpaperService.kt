
package com.mainsnow

import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.content.SharedPreferences
import android.graphics.Canvas
import android.os.Build

class MainSnowWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return SnowEngine()
    }

    private inner class SnowEngine :
        Engine(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val renderer = SnowRenderer()

        private val preferences = getSharedPreferences(
            "main_snow_settings",
            MODE_PRIVATE
        )

        private var isVisible = false
        private var useHardwareCanvas = true

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            setTouchEventsEnabled(false)

            preferences.registerOnSharedPreferenceChangeListener(this)

            applySettings()
        }

        private fun applySettings() {
            val context = this@MainSnowWallpaperService

            renderer.setMode(
                SnowSettings.getMode(context)
            )

            renderer.setBackground(
                SnowSettings.getBackground(context),
                SnowSettings.getCustomBackgroundColor(context)
            )

            renderer.setPerspective(
                SnowSettings.getPerspective(context)
            )

            renderer.setSnowDensity(
                SnowSettings.getSnowDensity(context)
            )

            renderer.setSnowSpeed(
                SnowSettings.getSnowSpeed(context)
            )

            renderer.setSnowAngle(
                SnowSettings.getSnowAngle(context)
            )

            renderer.setCloudSpeed(
                SnowSettings.getCloudSpeed(context)
            )

            renderer.setBackgroundImage(
                context,
                SnowSettings.getBackgroundImageUri(context)
            )

            renderer.setFogEnabled(
                SnowSettings.isFogEnabled(context)
            )

            renderer.setFogDensity(SnowSettings.getFogDensity(context))
            renderer.setCloudsEnabled(SnowSettings.areCloudsEnabled(context))
            renderer.setCloudCoverage(SnowSettings.getCloudCoverage(context))
            renderer.setCloudSize(SnowSettings.getCloudSize(context))
            renderer.setCloudHeight(SnowSettings.getCloudHeight(context))
            renderer.setCloudOpacity(SnowSettings.getCloudOpacity(context))
            renderer.setCloudBrightness(SnowSettings.getCloudBrightness(context))
            renderer.setGlowStrength(SnowSettings.getGlowStrength(context))
            renderer.setGlowHue(SnowSettings.getGlowHue(context))
            renderer.setGlowPosition(SnowSettings.getGlowPosition(context))
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            applySettings()
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)

            renderer.stop()

            renderer.resize(
                width,
                height,
                resources.displayMetrics.density
            )

            applySettings()
            drawFrame()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible

            if (visible) {
                drawFrame()
            } else {
                renderer.stop()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            isVisible = false
            renderer.stop()

            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            isVisible = false

            renderer.stop()
            preferences.unregisterOnSharedPreferenceChangeListener(this)

            super.onDestroy()
        }

        private fun drawFrame() {
            if (!isVisible) return

            val frameStart = System.nanoTime()
            var canvas: Canvas? = null

            try {
                canvas = lockFrameCanvas()

                if (canvas != null) {
                    renderer.draw(canvas)
                }
            } finally {
                if (canvas != null) {
                    surfaceHolder.unlockCanvasAndPost(canvas)
                }
            }

            if (isVisible && renderer.isAnimationEnabled()) {
                // Вычитаем время отрисовки, чтобы держать ровные ~30 кадров/с.
                val spent = (System.nanoTime() - frameStart) / 1_000_000L

                renderer.scheduleFrame({ drawFrame() }, 33L - spent)
            }
        }

        // GPU-канвас (API 26+) заметно быстрее программного; при сбое — обычный.
        private fun lockFrameCanvas(): Canvas? {
            if (useHardwareCanvas && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    return surfaceHolder.lockHardwareCanvas()
                } catch (e: Throwable) {
                    useHardwareCanvas = false
                }
            }

            return surfaceHolder.lockCanvas()
        }
    }
}
