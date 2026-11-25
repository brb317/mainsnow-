package com.mainsnow

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

// Живые обои «Пчела»: отдельный сервис на OpenGL ES. Режим снегопада (Canvas) остаётся
// в MainSnowWallpaperService без изменений. Настройки общие, лежат в тех же SharedPreferences.
class BeeWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = BeeEngine()

    private inner class BeeEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val prefs = getSharedPreferences("main_snow_settings", MODE_PRIVATE)
        private val handler = Handler(Looper.getMainLooper())
        private val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        private var gl: BeeGlRenderer? = null
        private var visibleNow = false

        // Раз в полторы секунды проверяет, играет ли на телефоне музыка. Разрешений не нужно:
        // системный флаг. Под бит не подстраивается, пчела танцует в своём ритме.
        private val musicPoll = object : Runnable {
            override fun run() {
                gl?.setMusic(audio.isMusicActive)

                if (visibleNow) handler.postDelayed(this, 1500L)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            prefs.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)

            gl?.release()

            gl = BeeGlRenderer(applicationContext).also {
                it.start(holder.surface, SnowSettings.getBeeTime(this@BeeWallpaperService))
                it.setVisible(visibleNow)
            }

            handler.removeCallbacks(musicPoll)
            if (visibleNow) handler.post(musicPoll)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            visibleNow = visible
            gl?.setVisible(visible)

            handler.removeCallbacks(musicPoll)
            if (visible) handler.post(musicPoll)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            handler.removeCallbacks(musicPoll)
            gl?.release()
            gl = null
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            handler.removeCallbacks(musicPoll)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            gl?.release()
            gl = null
            super.onDestroy()
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (key == null || key == "bee_time") {
                gl?.setTime(SnowSettings.getBeeTime(this@BeeWallpaperService))
            }

        }
    }
}
