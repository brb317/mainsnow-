package com.mainsnow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

        // Автоматический режим следит за часами телефона: раз в 10 секунд и по системным сигналам
        // смены времени (минутный тик, ручной перевод часов, смена пояса).
        private var applied: BeeTime? = null

        private val timeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                checkClock()
            }
        }

        private val clockPoll = object : Runnable {
            override fun run() {
                checkClock()

                if (visibleNow) handler.postDelayed(this, 10_000L)
            }
        }

        private fun checkClock() {
            if (!SnowSettings.getBeeAuto(this@BeeWallpaperService)) return

            val t = SnowSettings.beeTimeForNow()

            if (t != applied) {
                applied = t
                gl?.setTime(t, true)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            prefs.registerOnSharedPreferenceChangeListener(this)

            val filter = IntentFilter()
            filter.addAction(Intent.ACTION_TIME_TICK)
            filter.addAction(Intent.ACTION_TIME_CHANGED)
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED)
            registerReceiver(timeReceiver, filter)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)

            gl?.release()

            val startTime = SnowSettings.resolveBeeTime(this@BeeWallpaperService)
            applied = startTime

            gl = BeeGlRenderer(applicationContext).also {
                it.start(holder.surface, startTime)
                it.setVisible(visibleNow)
            }

            handler.removeCallbacks(musicPoll)
            handler.removeCallbacks(clockPoll)
            if (visibleNow) handler.post(musicPoll)
            if (visibleNow) handler.post(clockPoll)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            visibleNow = visible
            gl?.setVisible(visible)

            handler.removeCallbacks(musicPoll)
            handler.removeCallbacks(clockPoll)
            if (visible) handler.post(musicPoll)
            if (visible) handler.post(clockPoll)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            handler.removeCallbacks(musicPoll)
            handler.removeCallbacks(clockPoll)
            gl?.release()
            gl = null
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            handler.removeCallbacks(musicPoll)
            handler.removeCallbacks(clockPoll)

            try {
                unregisterReceiver(timeReceiver)
            } catch (e: Throwable) {
                // Уже снят.
            }

            prefs.unregisterOnSharedPreferenceChangeListener(this)
            gl?.release()
            gl = null
            super.onDestroy()
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (key == null || key == "bee_time" || key == "bee_auto") {
                // Выбор в меню: ручное состояние сразу фиксируется, взросление не запускается.
                val t = SnowSettings.resolveBeeTime(this@BeeWallpaperService)
                applied = t
                gl?.setTime(t, false)
            }

        }
    }
}
