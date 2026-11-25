package com.mainsnow

import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

// Живые обои «Рождественская комната»: уютная комната в стиле Minecraft на OpenGL ES.
class RoomWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = RoomEngine()

    private inner class RoomEngine : Engine() {

        private var gl: RoomGlRenderer? = null
        private var visibleNow = false

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)

            gl?.release()

            gl = RoomGlRenderer(applicationContext).also {
                it.start(holder.surface)
                it.setVisible(visibleNow)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            visibleNow = visible
            gl?.setVisible(visible)

            if (visible) gl?.applySettings()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            gl?.release()
            gl = null
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            gl?.release()
            gl = null
            super.onDestroy()
        }
    }
}
