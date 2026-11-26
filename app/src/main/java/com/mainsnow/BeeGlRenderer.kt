package com.mainsnow

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// Рендерер режима «Пчела». Работает на собственном потоке с EGL и OpenGL ES 2.0,
// 30 кадров в секунду, при скрытых обоях цикл полностью останавливается.
internal class BeeGlRenderer(private val context: Context) {

    private companion object {
        const val TAG = "BeeGl"
        const val FOV = 58f
        const val FRAME_MS = 33L

        // Индексы массива окружения.
        const val TOP = 0
        const val HOR = 3
        const val LDIR = 6
        const val LCOL = 9
        const val AMB = 12
        const val STAR = 15
        const val SUN_VIS = 16
        const val MOON_VIS = 17
        const val SUN_X = 18
        const val SUN_Y = 19
        const val SUN_COL = 20

        val ENV_DAY = floatArrayOf(
            0.34f, 0.62f, 0.96f, 0.80f, 0.91f, 1.00f,
            0.35f, 0.85f, 0.40f, 1.00f, 0.96f, 0.84f,
            0.46f, 0.52f, 0.60f,
            0f, 1f, 0f, 8f, 19f, 1.00f, 0.96f, 0.70f
        )

        val ENV_MORNING = floatArrayOf(
            0.30f, 0.50f, 0.85f, 1.00f, 0.78f, 0.62f,
            0.65f, 0.45f, 0.50f, 1.00f, 0.84f, 0.66f,
            0.44f, 0.46f, 0.58f,
            0f, 1f, 0f, 6f, 9f, 1.00f, 0.84f, 0.52f
        )

        val ENV_EVENING = floatArrayOf(
            0.20f, 0.18f, 0.42f, 1.00f, 0.56f, 0.30f,
            -0.75f, 0.30f, 0.35f, 1.00f, 0.58f, 0.32f,
            0.36f, 0.29f, 0.40f,
            0.35f, 1f, 0.35f, 5f, 5.5f, 1.00f, 0.55f, 0.25f
        )

        val ENV_NIGHT = floatArrayOf(
            0.015f, 0.03f, 0.10f, 0.07f, 0.11f, 0.26f,
            -0.30f, 0.80f, 0.50f, 0.34f, 0.44f, 0.74f,
            0.15f, 0.18f, 0.32f,
            1f, 0f, 1f, 8f, 19f, 1.00f, 0.96f, 0.70f
        )

        const val MOON_X = -6f
        const val MOON_Y = 16f
        const val SKY_Z = -42f
        const val STAR_COUNT = 90
        const val POINT_FLOATS = 8
        const val MAX_POINTS = STAR_COUNT + 14 * 3 + 48 + 24 + 120
    }

    private val thread = HandlerThread("bee-gl").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var visible = false
    @Volatile private var released = false
    @Volatile private var ready = false

    private var surface: Surface? = null
    private var startTime = BeeTime.DAY

    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var cfg: EGLConfig? = null

    private var lit: GlProgram? = null
    private var sky: GlProgram? = null
    private var points: GlProgram? = null
    private var emote: GlProgram? = null
    private var emoteTex = 0
    private var world: GlMesh? = null
    private var sunMesh: GlMesh? = null
    private var moonMesh: GlMesh? = null
    private var bee: GltfBee? = null
    private var baby: BabyBee? = null
    private var skyVbo = 0
    private var pointVbo = 0
    private var maxPoint = 64f

    private var brain: BeeBrain? = null

    private val env = ENV_DAY.copyOf()
    private var envTarget = ENV_DAY

    private val ex = floatArrayOf(0f, 2.9f, 8.2f)
    private val tg = floatArrayOf(0f, 1.5f, 0f)

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val sizeOut = IntArray(1)

    private val pointData = FloatArray(MAX_POINTS * POINT_FLOATS)
    private val pointBuffer = ByteBuffer
        .allocateDirect(MAX_POINTS * POINT_FLOATS * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val starPos = FloatArray(STAR_COUNT * 3)
    private val starPhase = FloatArray(STAR_COUNT)

    private var lastNanos = 0L
    private var clock = 0f
    private var width = 0
    private var height = 0

    private val frameRunnable = Runnable { frame() }

    init {
        val r = Random(5)

        for (i in 0 until STAR_COUNT) {
            val az = (r.nextFloat() - 0.5f) * 2.2f
            val el = 0.14f + r.nextFloat() * 1.1f
            val d = 60f

            starPos[i * 3] = d * sin(az) * kotlin.math.cos(el)
            starPos[i * 3 + 1] = 2.6f + d * sin(el)
            starPos[i * 3 + 2] = -d * kotlin.math.cos(az) * kotlin.math.cos(el)
            starPhase[i] = r.nextFloat() * 6.28f
        }
    }

    // ---- Управление из основного потока ----

    fun start(target: Surface, time: BeeTime) {
        surface = target
        startTime = time

        handler.post {
            try {
                setup()
            } catch (e: Throwable) {
                Log.e(TAG, "Не удалось запустить OpenGL", e)
                ready = false
            }
        }
    }

    fun setVisible(v: Boolean) {
        visible = v

        handler.post {
            handler.removeCallbacks(frameRunnable)

            if (v && ready && !released) {
                lastNanos = 0L
                handler.post(frameRunnable)
            }
        }
    }

    fun setTime(time: BeeTime, natural: Boolean = false) {
        handler.post {
            startTime = time
            brain?.setTime(time, natural)
            envTarget = envFor(time)
        }
    }

    fun setMusic(on: Boolean) {
        handler.post { brain?.setMusic(on) }
    }

    fun release() {
        if (released) return
        released = true

        val latch = CountDownLatch(1)

        handler.post {
            handler.removeCallbacks(frameRunnable)

            try {
                teardown()
            } catch (e: Throwable) {
                Log.e(TAG, "Ошибка освобождения", e)
            }

            latch.countDown()
        }

        latch.await(800, TimeUnit.MILLISECONDS)
        thread.quitSafely()
    }

    // ---- EGL и ресурсы (только на GL-потоке) ----

    private fun envFor(time: BeeTime) = when (time) {
        BeeTime.MORNING -> ENV_MORNING
        BeeTime.DAY -> ENV_DAY
        BeeTime.EVENING -> ENV_EVENING
        BeeTime.NIGHT -> ENV_NIGHT
    }

    private fun chooseConfig(msaa: Boolean): EGLConfig? {
        val attrs = ArrayList<Int>()

        attrs.add(EGL14.EGL_RENDERABLE_TYPE); attrs.add(EGL14.EGL_OPENGL_ES2_BIT)
        attrs.add(EGL14.EGL_SURFACE_TYPE); attrs.add(EGL14.EGL_WINDOW_BIT)
        attrs.add(EGL14.EGL_RED_SIZE); attrs.add(8)
        attrs.add(EGL14.EGL_GREEN_SIZE); attrs.add(8)
        attrs.add(EGL14.EGL_BLUE_SIZE); attrs.add(8)
        attrs.add(EGL14.EGL_ALPHA_SIZE); attrs.add(8)
        attrs.add(EGL14.EGL_DEPTH_SIZE); attrs.add(16)

        if (msaa) {
            attrs.add(EGL14.EGL_SAMPLE_BUFFERS); attrs.add(1)
            attrs.add(EGL14.EGL_SAMPLES); attrs.add(4)
        }

        attrs.add(EGL14.EGL_NONE)

        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)

        val ok = EGL14.eglChooseConfig(dpy, attrs.toIntArray(), 0, configs, 0, 1, count, 0)

        return if (ok && count[0] > 0) configs[0] else null
    }

    private fun setup() {
        if (released) return

        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)

        val version = IntArray(2)
        check(EGL14.eglInitialize(dpy, version, 0, version, 1)) { "eglInitialize" }

        cfg = chooseConfig(true) ?: chooseConfig(false)
        checkNotNull(cfg) { "Нет подходящего EGL-конфига" }

        ctx = EGL14.eglCreateContext(
            dpy, cfg, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(ctx != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }

        surf = EGL14.eglCreateWindowSurface(dpy, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(surf != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }

        check(EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) { "eglMakeCurrent" }

        initGl()

        brain = BeeBrain(BeeScene.flowers, BeeScene.SLEEPER, startTime)
        envTarget = envFor(startTime)
        envTarget.copyInto(env)

        ready = true

        if (visible && !released) {
            lastNanos = 0L
            handler.post(frameRunnable)
        }
    }

    private fun initGl() {
        lit = GlProgram(
            BeeShaders.LIT_VS, BeeShaders.LIT_FS,
            "aPos", "aNormal", "aUV", "aColor"
        )
        sky = GlProgram(BeeShaders.SKY_VS, BeeShaders.SKY_FS, "aPos")
        points = GlProgram(BeeShaders.POINT_VS, BeeShaders.POINT_FS, "aPos", "aColor", "aSize")
        emote = GlProgram(BeeShaders.POINT_VS, BeeShaders.EMOTE_FS, "aPos", "aColor", "aSize")
        emoteTex = BeeEmotes.createTexture()

        world = BeeScene.buildWorld()
        sunMesh = BeeScene.buildSun()
        moonMesh = BeeScene.buildMoon()
        bee = GltfBee(context, "bee")
        baby = try {
            BabyBee(context)
        } catch (e: Throwable) {
            Log.e(TAG, "Baby Bee не загрузилась", e)
            null
        }

        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        skyVbo = ids[0]
        pointVbo = ids[1]

        val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quad.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        quad.position(0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, skyVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 8 * 4, quad, GLES20.GL_STATIC_DRAW)

        val range = FloatArray(2)
        GLES20.glGetFloatv(GLES20.GL_ALIASED_POINT_SIZE_RANGE, range, 0)
        maxPoint = max(range[1], 8f)

        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun teardown() {
        ready = false

        if (dpy != EGL14.EGL_NO_DISPLAY) {
            if (surf != EGL14.EGL_NO_SURFACE && ctx != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglMakeCurrent(dpy, surf, surf, ctx)

                bee?.release()
                baby?.release()
                world?.delete()
                sunMesh?.delete()
                moonMesh?.delete()
                lit?.delete()
                sky?.delete()
                points?.delete()
                emote?.delete()
                if (emoteTex != 0) GLES20.glDeleteTextures(1, intArrayOf(emoteTex), 0)

                if (skyVbo != 0) GLES20.glDeleteBuffers(2, intArrayOf(skyVbo, pointVbo), 0)
            }

            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)

            if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf)
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)

            EGL14.eglTerminate(dpy)
        }

        bee = null; baby = null; world = null; sunMesh = null; moonMesh = null
        lit = null; sky = null; points = null
        emote = null; emoteTex = 0
        skyVbo = 0; pointVbo = 0
        surf = EGL14.EGL_NO_SURFACE
        ctx = EGL14.EGL_NO_CONTEXT
        dpy = EGL14.EGL_NO_DISPLAY
    }

    // ---- Кадр ----

    private fun frame() {
        if (!visible || !ready || released) return

        val t0 = System.nanoTime()

        try {
            render()

            if (!EGL14.eglSwapBuffers(dpy, surf)) {
                val error = EGL14.eglGetError()

                if (error == EGL14.EGL_CONTEXT_LOST) {
                    teardown()
                    setup()
                } else {
                    // Поверхность исчезла: ждём onSurfaceDestroyed.
                    ready = false
                }

                return
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Ошибка кадра", e)
            ready = false
            return
        }

        val spent = (System.nanoTime() - t0) / 1_000_000L
        handler.postDelayed(frameRunnable, max(1L, FRAME_MS - spent))
    }

    private fun surfaceSize(): Boolean {
        EGL14.eglQuerySurface(dpy, surf, EGL14.EGL_WIDTH, sizeOut, 0)
        val w = sizeOut[0]
        EGL14.eglQuerySurface(dpy, surf, EGL14.EGL_HEIGHT, sizeOut, 0)
        val h = sizeOut[0]

        width = w
        height = h

        return w > 0 && h > 0
    }

    private fun lerpEnv(dt: Float) {
        val k = (dt * 1.4f).coerceAtMost(1f)

        for (i in env.indices) env[i] += (envTarget[i] - env[i]) * k
    }

    private fun updateCamera(dt: Float, b: BeeBrain) {
        val night = b.mode == BeeTime.NIGHT

        val dTx: Float
        val dTy: Float
        val dTz: Float
        val dEx: Float
        val dEy: Float
        val dEz: Float

        if (night) {
            // Крупный план спящей пчелы на подсолнухе.
            val f = BeeScene.flowers[BeeScene.SLEEPER]

            dTx = f.x
            dTy = f.y + 0.1f
            dTz = f.z
            dEx = dTx
            dEy = dTy + 0.55f
            dEz = dTz + 5.0f
        } else {
            // Общий план луга, камера мягко следит за пчелой.
            val fx = b.x.coerceIn(-1.6f, 1.6f) * 0.35f
            val fy = 1.5f + (b.y - 1.8f).coerceIn(-1f, 1f) * 0.3f

            dTx = fx
            dTy = fy
            dTz = 0f
            dEx = fx * 0.8f
            dEy = 2.9f + (fy - 1.5f) * 0.5f
            dEz = 8.2f
        }

        val k = 1f - exp(-dt * 1.8f)

        tg[0] += (dTx - tg[0]) * k
        tg[1] += (dTy - tg[1]) * k
        tg[2] += (dTz - tg[2]) * k
        ex[0] += (dEx - ex[0]) * k
        ex[1] += (dEy - ex[1]) * k
        ex[2] += (dEz - ex[2]) * k
    }

    private fun setLitUniforms(p: GlProgram) {
        val lx = env[LDIR]
        val ly = env[LDIR + 1]
        val lz = env[LDIR + 2]
        val len = sqrt(lx * lx + ly * ly + lz * lz)

        GLES20.glUniformMatrix4fv(p.u("uVP"), 1, false, vp, 0)
        GLES20.glUniform3f(p.u("uEye"), ex[0], ex[1], ex[2])
        GLES20.glUniform3f(p.u("uLightDir"), lx / len, ly / len, lz / len)
        GLES20.glUniform3f(p.u("uLightCol"), env[LCOL], env[LCOL + 1], env[LCOL + 2])
        GLES20.glUniform3f(p.u("uAmbient"), env[AMB], env[AMB + 1], env[AMB + 2])
        GLES20.glUniform3f(p.u("uFogCol"), env[HOR], env[HOR + 1], env[HOR + 2])
        GLES20.glUniform2f(p.u("uFog"), 11f, 42f)
        GLES20.glUniform1i(p.u("uTex"), 0)
    }

    private fun render() {
        val b = brain ?: return

        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.033f else ((now - lastNanos) / 1e9f).coerceIn(0.001f, 0.05f)
        lastNanos = now
        clock += dt

        if (!surfaceSize()) return

        val aspect = width.toFloat() / height.toFloat()

        // Камера нужна пчеле, чтобы входить в кадр и уходить из него за его пределами.
        b.setView(ex, tg, FOV, aspect)
        b.update(dt)
        lerpEnv(dt)
        updateCamera(dt, b)

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(env[HOR], env[HOR + 1], env[HOR + 2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        Matrix.perspectiveM(proj, 0, FOV, aspect, 0.3f, 120f)
        val sk = b.shake
        val shX = if (sk > 0.01f) (Math.random().toFloat() - 0.5f) * 0.3f * sk else 0f
        val shY = if (sk > 0.01f) (Math.random().toFloat() - 0.5f) * 0.3f * sk else 0f

        Matrix.setLookAtM(view, 0, ex[0] + shX, ex[1] + shY, ex[2], tg[0] + shX, tg[1] + shY, tg[2], 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        drawSky()
        drawLit(b)
        drawPoints(b)
        drawEmotes(b)
    }

    // Эмоции: пиксельные спрайты из атласа над пчелой, рисуются поверх сцены.
    private fun drawEmotes(b: BeeBrain) {
        val p = emote ?: return
        var n = 0

        for (e in b.emotes) {
            if (e.life <= 0f || n >= 24) continue

            val age = e.max - e.life

            // Появление с «пружинкой» и плавное исчезновение.
            val pop = when {
                age < 0.12f -> age / 0.12f * 1.25f
                age < 0.25f -> 1.25f - (age - 0.12f) / 0.13f * 0.25f
                else -> 1f
            }
            val fade = (e.life / 0.35f).coerceAtMost(1f)
            val size = e.size * pop * fade

            if (size < 0.01f) continue

            n = addPoint(n, e.x, e.y, e.z, 1f, 1f, 1f, e.kind / 10f, size)
        }

        if (n == 0) return

        pointBuffer.position(0)
        pointBuffer.put(pointData, 0, n * POINT_FLOATS)
        pointBuffer.position(0)

        p.use()
        GLES20.glUniformMatrix4fv(p.u("uVP"), 1, false, vp, 0)
        GLES20.glUniform1f(p.u("uScale"), height / (2f * tan(Math.toRadians(FOV / 2.0).toFloat())))
        GLES20.glUniform1f(p.u("uMaxPoint"), maxPoint)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, emoteTex)
        GLES20.glUniform1i(p.u("uAtlas"), 1)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pointVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, n * POINT_FLOATS * 4, pointBuffer, GLES20.GL_STREAM_DRAW)

        val stride = POINT_FLOATS * 4

        GLES20.glEnableVertexAttribArray(0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glEnableVertexAttribArray(2)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glVertexAttribPointer(1, 4, GLES20.GL_FLOAT, false, stride, 12)
        GLES20.glVertexAttribPointer(2, 1, GLES20.GL_FLOAT, false, stride, 28)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, n)

        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
        GLES20.glDisableVertexAttribArray(2)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun drawSky() {
        val p = sky ?: return

        val pitch = atan2(ex[1] - tg[1], hypot(ex[0] - tg[0], ex[2] - tg[2]))
        val horizon = 0.5f + 0.5f * tan(pitch) / tan(Math.toRadians(FOV / 2.0).toFloat())

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        p.use()
        GLES20.glUniform3f(p.u("uTop"), env[TOP], env[TOP + 1], env[TOP + 2])
        GLES20.glUniform3f(p.u("uHor"), env[HOR], env[HOR + 1], env[HOR + 2])
        GLES20.glUniform1f(p.u("uHorizon"), horizon.coerceIn(0.05f, 0.95f))

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, skyVbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(0)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
    }

    private fun drawBodyMesh(p: GlProgram, mesh: GlMesh, x: Float, y: Float, z: Float, r: Float, g: Float, bl: Float, alpha: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)

        GLES20.glUniformMatrix4fv(p.u("uModel"), 1, false, model, 0)
        GLES20.glUniform4f(p.u("uTint"), r, g, bl, alpha)
        mesh.draw()
    }

    private fun drawLit(b: BeeBrain) {
        val p = lit ?: return

        p.use()
        setLitUniforms(p)

        // Солнце и луна: без освещения и тумана.
        GLES20.glUniform1f(p.u("uTwoSided"), 0f)
        GLES20.glUniform1f(p.u("uUseTex"), 0f)
        GLES20.glUniform1f(p.u("uEmissive"), 1f)
        GLES20.glUniform2f(p.u("uFog"), 1e6f, 2e6f)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val sunVis = env[SUN_VIS]
        val moonVis = env[MOON_VIS]

        if (sunVis > 0.02f) {
            sunMesh?.let {
                drawBodyMesh(p, it, env[SUN_X], env[SUN_Y], SKY_Z, env[SUN_COL], env[SUN_COL + 1], env[SUN_COL + 2], sunVis)
            }
        }

        if (moonVis > 0.02f) {
            moonMesh?.let { drawBodyMesh(p, it, MOON_X, MOON_Y, SKY_Z, 1f, 1f, 1f, moonVis) }
        }

        GLES20.glDisable(GLES20.GL_BLEND)

        // Луг и цветы.
        GLES20.glUniform1f(p.u("uEmissive"), 0f)
        GLES20.glUniform2f(p.u("uFog"), 11f, 42f)
        world?.let { drawBodyMesh(p, it, 0f, 0f, 0f, 1f, 1f, 1f, 1f) }

        // Пчела.
        val bee = bee ?: return

        bee.animate(b.animTime, b.pose, 0f)

        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, b.x + b.jx, b.y + b.bob + b.jy, b.z + b.jz)
        Matrix.rotateM(model, 0, Math.toDegrees(b.tilt.toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.rotateM(model, 0, Math.toDegrees(b.yaw.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(model, 0, Math.toDegrees(b.pitch.toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.rotateM(model, 0, Math.toDegrees(b.roll.toDouble()).toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(model, 0, BeeBrain.SCALE * b.sizeK, BeeBrain.SCALE * b.breath * b.sizeK, BeeBrain.SCALE * b.sizeK)

        val beeMatrix = model.copyOf()

        // Утром вместо взрослой рисуется Baby Bee.
        val babyModel = baby

        if (b.babyMode && babyModel != null) {
            GLES20.glUniform1f(p.u("uTwoSided"), 1f)
            babyModel.draw(p, beeMatrix, clock, b.pose.flap > 0.25f && b.pose.fold < 0.5f, b.pose.fold > 0.5f, b.glow)
            return
        }

        // Лицо: глаза и румянец красятся в текстуре, брови двигаются отдельно.
        bee.setFace(b.eyeL, b.eyeR, b.blush)
        bee.browTilt = b.browTilt
        bee.browLift = b.browLift
        bee.browsHidden = b.browsHidden

        GLES20.glUniform1f(p.u("uTwoSided"), 1f)
        bee.draw(p, beeMatrix, 0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)

        if (b.pose.flap > 0.25f && b.pose.fold < 0.5f) {
            // Быстрые взмахи: крылья рисуются в двух противоположных фазах, получается размытие.
            bee.draw(p, beeMatrix, 1, 0.40f)
            bee.animate(b.animTime, b.pose, Math.PI.toFloat())
            bee.draw(p, beeMatrix, 1, 0.40f)
        } else {
            bee.draw(p, beeMatrix, 1, 0.60f)
        }

        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun addPoint(n: Int, x: Float, y: Float, z: Float, r: Float, g: Float, bl: Float, a: Float, size: Float): Int {
        val o = n * POINT_FLOATS

        pointData[o] = x
        pointData[o + 1] = y
        pointData[o + 2] = z
        pointData[o + 3] = r
        pointData[o + 4] = g
        pointData[o + 5] = bl
        pointData[o + 6] = a
        pointData[o + 7] = size

        return n + 1
    }

    private fun drawPoints(b: BeeBrain) {
        val p = points ?: return
        var n = 0

        val starLevel = env[STAR]

        if (starLevel > 0.02f) {
            for (i in 0 until STAR_COUNT) {
                val tw = 0.65f + 0.35f * sin(clock * 1.7f + starPhase[i])

                n = addPoint(
                    n,
                    starPos[i * 3], starPos[i * 3 + 1], starPos[i * 3 + 2],
                    1f, 1f, 1f, starLevel * tw * 0.9f, 0.18f
                )
            }
        }

        val s = b.swarm

        // Светящиеся кусты загораются вместе со светлячками.
        if (s.level > 0.02f) {
            val glow = BeeScene.bushGlow
            var i = 0

            while (i + 2 < glow.size && n + 2 <= MAX_POINTS) {
                val blink = 0.5f + 0.5f * sin(clock * 1.3f + i * 0.9f)
                val a = s.level * (0.35f + 0.65f * blink)

                n = addPoint(n, glow[i], glow[i + 1], glow[i + 2], 0.7f, 1.0f, 0.25f, a * 0.3f, 0.36f)
                n = addPoint(n, glow[i], glow[i + 1], glow[i + 2], 0.95f, 1.0f, 0.6f, a, 0.08f)
                i += 3
            }
        }

        for (i in 0 until s.n) {
            val a = s.alpha[i]

            if (a < 0.02f) continue

            val k = s.size[i]

            // Шлейф, ореол и яркое ядро: тёплый жёлто-зелёный свет.
            n = addPoint(n, s.tx[i], s.ty[i], s.tz[i], 0.5f, 1.0f, 0.25f, a * 0.22f, 0.22f * k)
            n = addPoint(n, s.px[i], s.py[i], s.pz[i], 0.55f, 1.0f, 0.25f, a * 0.30f, 0.44f * k)
            n = addPoint(n, s.px[i], s.py[i], s.pz[i], 1.0f, 0.97f, 0.55f, a, 0.09f * k)
        }

        for (q in b.pollen) {
            if (q.life <= 0f) continue

            val qa = (q.life / q.max).coerceIn(0f, 1f)

            if (q.kind == 2) {
                n = addPoint(n, q.x, q.y, q.z, 0.45f, 1.0f, 0.40f, qa, 0.10f)
            } else {
                n = addPoint(n, q.x, q.y, q.z, 1.0f, 0.85f, 0.25f, qa, 0.06f)
            }
        }

        // Зелёное свечение вокруг пчелы при взрослении.
        if (b.glow > 0.02f) {
            n = addPoint(n, b.x, b.y + 0.05f, b.z, 0.4f, 1.0f, 0.35f, 0.35f * b.glow, 0.9f)
        }

        if (n == 0) return

        pointBuffer.position(0)
        pointBuffer.put(pointData, 0, n * POINT_FLOATS)
        pointBuffer.position(0)

        p.use()
        GLES20.glUniformMatrix4fv(p.u("uVP"), 1, false, vp, 0)
        GLES20.glUniform1f(p.u("uScale"), height / (2f * tan(Math.toRadians(FOV / 2.0).toFloat())))
        GLES20.glUniform1f(p.u("uMaxPoint"), maxPoint)

        GLES20.glDepthMask(false)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pointVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, n * POINT_FLOATS * 4, pointBuffer, GLES20.GL_STREAM_DRAW)

        val stride = POINT_FLOATS * 4

        GLES20.glEnableVertexAttribArray(0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glEnableVertexAttribArray(2)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glVertexAttribPointer(1, 4, GLES20.GL_FLOAT, false, stride, 12)
        GLES20.glVertexAttribPointer(2, 1, GLES20.GL_FLOAT, false, stride, 28)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, n)

        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
        GLES20.glDisableVertexAttribArray(2)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDepthMask(true)
    }
}
