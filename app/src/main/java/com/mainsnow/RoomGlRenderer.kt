package com.mainsnow

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.tan

private object RoomShaders {

    const val ROOM_VS = """
attribute vec3 aPos;
attribute vec4 aNormal;
attribute vec2 aUV;
attribute vec4 aColor;
uniform mat4 uVP;
uniform mat4 uModel;
varying vec3 vPos;
varying vec3 vN;
varying vec2 vUV;
varying vec4 vCol;
void main() {
    vec4 wp = uModel * vec4(aPos, 1.0);
    vPos = wp.xyz;
    vN = (uModel * vec4(aNormal.xyz, 0.0)).xyz;
    vUV = aUV;
    vCol = aColor;
    gl_Position = uVP * wp;
}
"""

    // Свет: слабый общий, лунный через окно и до восьми тёплых точечных источников (камин, фонари,
    // свечи, ёлка). Свечение блока (альфа цвета) показывает его без освещения, как огонёк или лампа.
    const val ROOM_FS = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTex;
uniform vec3 uAmbient;
uniform vec3 uMoonDir;
uniform vec3 uMoonCol;
uniform vec3 uLPos[8];
uniform vec3 uLCol[8];
uniform float uLRad[8];
uniform vec3 uTint;
uniform float uPass;
uniform vec3 uEye;
uniform float uFog;
varying vec3 vPos;
varying vec3 vN;
varying vec2 vUV;
varying vec4 vCol;
// Объёмный свет: сколько света лампы рассеивается в воздухе на луче от глаза до поверхности.
float scat(vec3 e, vec3 p, vec3 lp) {
    vec3 d = p - e;
    float len = max(length(d), 0.001);
    vec3 dir = d / len;
    vec3 el = lp - e;
    float tl = dot(el, dir);
    float h = sqrt(max(dot(el, el) - tl * tl, 0.04));
    return (atan((len - tl) / h) - atan(-tl / h)) / h;
}
void main() {
    vec4 t = texture2D(uTex, vUV);
    if (uPass < 0.5) {
        if (t.a < 0.9) discard;
    } else {
        if (t.a >= 0.9 || t.a < 0.02) discard;
    }
    vec3 base = t.rgb * vCol.rgb * 1.5 * uTint;
    vec3 n = normalize(vN);
    vec3 L = uAmbient + uMoonCol * max(dot(n, uMoonDir), 0.0);
    for (int i = 0; i < 8; i++) {
        vec3 d = uLPos[i] - vPos;
        float dist = length(d);
        float att = clamp(1.0 - dist / uLRad[i], 0.0, 1.0);
        att = att * att;
        float nd = clamp(dot(n, d / max(dist, 0.001)), 0.0, 1.0) * 0.75 + 0.25;
        L += uLCol[i] * (att * nd);
    }
    vec3 col = mix(base * L, base * 1.7, vCol.a);
    if (uPass < 0.5 && uFog > 0.5) {
        col += uLCol[0] * (scat(uEye, vPos, uLPos[0]) * 0.011) + uLCol[1] * (scat(uEye, vPos, uLPos[1]) * 0.013);
    }
    col = vec3(1.0) - exp(-col * 1.55);
    float a = (uPass < 0.5) ? 1.0 : t.a;
    gl_FragColor = vec4(col, a);
}
"""

    // Пламя, ореолы света и виньетка. Плоскость в мире (uWorld = 1) или на весь экран.
    const val FX_VS = """
attribute vec2 aPos;
uniform mat4 uVP;
uniform vec3 uC;
uniform vec3 uR;
uniform vec3 uU;
uniform float uWorld;
varying vec2 vP;
void main() {
    vP = aPos;
    if (uWorld > 0.5) {
        gl_Position = uVP * vec4(uC + uR * aPos.x + uU * aPos.y, 1.0);
    } else {
        gl_Position = vec4(aPos, 0.0, 1.0);
    }
}
"""

    const val FX_FS = """
precision mediump float;
uniform float uMode;
uniform float uTime;
uniform vec4 uColor;
varying vec2 vP;
float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}
void main() {
    if (uMode < 0.5) {
        vec2 q = vec2((vP.x * 0.5 + 0.5) * 10.0, (vP.y * 0.5 + 0.5) * 14.0);
        vec2 g = floor(q);
        float k = floor(uTime * 8.0);
        float hgt = g.y / 14.0;
        float h = hash(vec2(g.x, g.y - k));
        float h2 = hash(vec2(g.x + 3.0, k));
        float v = (1.0 - hgt) * 1.35 - abs(g.x - 4.5) / 5.0 * 1.1 + (h - 0.5) * 0.75 + (h2 - 0.5) * 0.15;
        if (v < 0.2) discard;
        vec3 c = (v < 0.45) ? vec3(0.95, 0.22, 0.04) : ((v < 0.75) ? vec3(1.0, 0.55, 0.1) : vec3(1.0, 0.92, 0.55));
        gl_FragColor = vec4(c * uColor.rgb, 1.0);
    } else if (uMode < 1.5) {
        float r = length(vP);
        float a = clamp(1.0 - r, 0.0, 1.0);
        a = a * a * uColor.a;
        gl_FragColor = vec4(uColor.rgb * a, a);
    } else {
        float r = length(vP * vec2(0.9, 0.78));
        float a = smoothstep(0.6, 1.5, r) * uColor.a;
        gl_FragColor = vec4(0.0, 0.0, 0.0, a);
    }
}
"""

    // Постобработка: свечение ярких мест (bloom), цветокоррекция и киношное зерно.
    const val POST_VS = """
attribute vec2 aPos;
varying vec2 vUV;
void main() {
    vUV = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    const val BRIGHT_FS = """
precision mediump float;
uniform sampler2D uTex;
uniform vec2 uTexel;
varying vec2 vUV;
void main() {
    vec3 c = texture2D(uTex, vUV + vec2(-uTexel.x, -uTexel.y)).rgb
           + texture2D(uTex, vUV + vec2(uTexel.x, -uTexel.y)).rgb
           + texture2D(uTex, vUV + vec2(-uTexel.x, uTexel.y)).rgb
           + texture2D(uTex, vUV + vec2(uTexel.x, uTexel.y)).rgb;
    c *= 0.25;
    float l = max(max(c.r, c.g), c.b);
    float k = smoothstep(0.62, 1.0, l);
    gl_FragColor = vec4(c * k, 1.0);
}
"""

    const val BLUR_FS = """
precision mediump float;
uniform sampler2D uTex;
uniform vec2 uDir;
varying vec2 vUV;
void main() {
    vec3 c = texture2D(uTex, vUV).rgb * 0.227027;
    c += (texture2D(uTex, vUV + uDir * 1.3846154).rgb + texture2D(uTex, vUV - uDir * 1.3846154).rgb) * 0.3162162;
    c += (texture2D(uTex, vUV + uDir * 3.2307692).rgb + texture2D(uTex, vUV - uDir * 3.2307692).rgb) * 0.0702703;
    gl_FragColor = vec4(c, 1.0);
}
"""

    const val COMP_FS = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uScene;
uniform sampler2D uBloom;
uniform float uBloomK;
uniform float uTime;
varying vec2 vUV;
float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}
void main() {
    vec3 c = texture2D(uScene, vUV).rgb;
    c += texture2D(uBloom, vUV).rgb * uBloomK;
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    c *= mix(vec3(0.94, 0.98, 1.08), vec3(1.06, 1.0, 0.93), smoothstep(0.15, 0.7, l));
    c = clamp(c, 0.0, 1.0);
    c = mix(c, c * c * (3.0 - 2.0 * c), 0.3);
    float n = hash(gl_FragCoord.xy + vec2(floor(uTime * 24.0) * 7.13, 0.0)) - 0.5;
    c += n * 0.022;
    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""

    const val SNOW_FS = """
precision mediump float;
varying vec4 vC;
void main() {
    gl_FragColor = vC;
}
"""
}

// Рождественская комната в стиле Minecraft на OpenGL ES 2.0. Свой поток с EGL, 30 кадров в секунду.
// Анимация: огонь в камине и свечи, качающееся кресло с Сантой и паром от кружки, спящая лиса,
// покачивающиеся носки и фонарь, мерцание гирлянд, искры, пылинки в свете и снег за окном.
internal class RoomGlRenderer(private val context: Context) {

    private companion object {
        const val TAG = "RoomGl"
        const val FRAME_MS = 33L
        const val POINT_FLOATS = 8
        const val MAX_SNOW = 1500
        const val EMBERS = 24
        const val DUST = 40
        const val STEAM = 10
        const val MAX_POINTS = MAX_SNOW + EMBERS + DUST + STEAM + 8

        // Позы спящей лисы: поворот корпуса, наклон головы, поворот головы, крен головы,
        // поворот хвоста, подъём хвоста, наклон ушей (градусы). Последняя поза: приподнятая голова.
        val FOX_POSES = arrayOf(
            floatArrayOf(0f, -6f, 25f, 0f, -100f, 0f, 30f),
            floatArrayOf(0f, -4f, 0f, 0f, 35f, 0f, 22f),
            floatArrayOf(15f, -8f, -30f, 4f, 110f, 0f, 34f),
            floatArrayOf(0f, 8f, 10f, 0f, 20f, -12f, 0f)
        )
    }

    private val thread = HandlerThread("room-gl").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var visible = false
    @Volatile private var released = false
    @Volatile private var ready = false

    private var surface: Surface? = null
    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var cfg: EGLConfig? = null

    private var roomProg: GlProgram? = null
    private var fx: GlProgram? = null
    private var points: GlProgram? = null
    private var snowPts: GlProgram? = null
    private var brightProg: GlProgram? = null
    private var blurProg: GlProgram? = null
    private var compProg: GlProgram? = null
    private var sceneFbo = 0
    private var sceneTex = 0
    private var sceneDepth = 0
    private val bloomFbo = IntArray(2)
    private val bloomTex = IntArray(2)
    private var fbW = 0
    private var fbH = 0
    private var bloomW = 0
    private var bloomH = 0
    private var postOn = true
    private var slowAvg = 0f
    private var framesSeen = 0
    private var frameDt = 0.033f

    private val foxCur = FloatArray(7)
    private val foxA = FloatArray(7)
    private val foxB = FloatArray(7)
    private val foxRoot = FloatArray(16)
    private val foxBody = FloatArray(16)
    private val foxHead = FloatArray(16)
    private val fa = FloatArray(16)
    private val fb = FloatArray(16)
    private val fc = FloatArray(16)
    private var foxStage = 0
    private var foxPose = 0
    private var foxT = 0f
    private var foxDur = 1f
    private var foxNext = 12f
    private var foxEarAt = 6f
    private var foxEarT0 = -10f
    private var atlas = 0
    private var data: RoomData? = null
    private var quadVbo = 0
    private var pointVbo = 0
    private var maxPoint = 64f

    private var snowDensity = 100
    private var snowSpeed = 100

    private val failed = HashSet<String>()
    private var firstFrameDone = false

    private val eye = FloatArray(3)
    private val tgt = FloatArray(3)
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val ident = FloatArray(16)
    private val m1 = FloatArray(16)
    private val chairM = FloatArray(16)
    private val vec4 = FloatArray(4)
    private val out4 = FloatArray(4)
    private val sizeOut = IntArray(1)

    private val lPos = FloatArray(24)
    private val lCol = FloatArray(24)
    private val lRad = FloatArray(8)

    private val pointData = FloatArray(MAX_POINTS * POINT_FLOATS)
    private val pointBuffer = ByteBuffer
        .allocateDirect(MAX_POINTS * POINT_FLOATS * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val rnd = Random(77)
    private val sx = FloatArray(MAX_SNOW)
    private val sy = FloatArray(MAX_SNOW)
    private val sz = FloatArray(MAX_SNOW)
    private val sPhase = FloatArray(MAX_SNOW)
    private val sRate = FloatArray(MAX_SNOW)
    private val ex = FloatArray(EMBERS)
    private val ey = FloatArray(EMBERS)
    private val ez = FloatArray(EMBERS)
    private val eVx = FloatArray(EMBERS)
    private val eVy = FloatArray(EMBERS)
    private val eAge = FloatArray(EMBERS)
    private val eMax = FloatArray(EMBERS)
    private val dustBase = FloatArray(DUST * 3)
    private val dustPhase = FloatArray(DUST)
    private val stAge = FloatArray(STEAM)

    private var clock = 0f
    private var lastNanos = 0L
    private var width = 0
    private var height = 0
    private var scalePx = 1000f
    private var snowCount = 600

    private val frameRunnable = Runnable { frame() }

    init {
        Matrix.setIdentityM(ident, 0)

        System.arraycopy(FOX_POSES[0], 0, foxCur, 0, 7)
        System.arraycopy(FOX_POSES[0], 0, foxA, 0, 7)
        System.arraycopy(FOX_POSES[0], 0, foxB, 0, 7)

        for (i in 0 until EMBERS) {
            eAge[i] = rnd.nextFloat() * 2f
            eMax[i] = 1.2f + rnd.nextFloat() * 1.4f
            resetEmber(i)
            eAge[i] = rnd.nextFloat() * eMax[i]
        }

        for (i in 0 until DUST) {
            dustBase[i * 3] = 2f + rnd.nextFloat() * 7f
            dustBase[i * 3 + 1] = 0.6f + rnd.nextFloat() * 3.9f
            dustBase[i * 3 + 2] = 1.5f + rnd.nextFloat() * 6.5f
            dustPhase[i] = rnd.nextFloat() * 6.28f
        }

        for (i in 0 until STEAM) stAge[i] = i * 0.18f

        for (i in 0 until MAX_SNOW) {
            resetSnow(i, true)
            sPhase[i] = rnd.nextFloat() * 6.28f
            sRate[i] = rnd.nextFloat()
        }
    }

    private fun resetEmber(i: Int) {
        ex[i] = 3.9f + rnd.nextFloat() * 1.4f
        ey[i] = 0.85f
        ez[i] = 0.6f + rnd.nextFloat() * 0.7f
        eVx[i] = (rnd.nextFloat() - 0.5f) * 0.35f
        eVy[i] = 0.7f + rnd.nextFloat() * 0.9f
        eAge[i] = 0f
        eMax[i] = 1.2f + rnd.nextFloat() * 1.4f
    }

    // Снег за окном: область перед окном на улице, видна только через стекло.
    private fun resetSnow(i: Int, anywhere: Boolean) {
        sx[i] = -12f + rnd.nextFloat() * 11.6f
        sz[i] = -12f + rnd.nextFloat() * 16f
        sy[i] = if (anywhere) 0.2f + rnd.nextFloat() * 9f else 9f + rnd.nextFloat() * 1.5f
    }

    // ---- Управление из основного потока ----

    fun start(target: Surface) {
        surface = target

        handler.post {
            try {
                setup()
            } catch (e: Throwable) {
                Log.e(TAG, "Не удалось запустить OpenGL", e)
                ready = false
                report("запуск", e)
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

    fun applySettings() {
        handler.post { readSettings() }
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

    // ---- Диагностика: первая ошибка каждой части сохраняется и показывается в меню ----

    private fun report(where: String, e: Throwable?, extra: String = "") {
        if (!failed.add(where)) return

        val text = "$where: $extra${e?.javaClass?.simpleName ?: ""} ${e?.message ?: ""}".trim()

        Log.e(TAG, text, e)

        try {
            SnowSettings.setRoomError(context, text)
        } catch (ignored: Throwable) {
        }
    }

    private inline fun guard(name: String, block: () -> Unit) {
        if (failed.contains(name)) return

        try {
            block()
        } catch (e: Throwable) {
            report(name, e)
        }
    }

    private fun makeProgram(name: String, vs: String, fs: String, vararg attribs: String): GlProgram? {
        return try {
            GlProgram(vs, fs, *attribs)
        } catch (e: Throwable) {
            report("шейдер $name", e)
            null
        }
    }

    private fun readSettings() {
        snowDensity = SnowSettings.getSnowDensity(context)
        snowSpeed = SnowSettings.getSnowSpeed(context)
        snowCount = (600 * snowDensity / 100f).toInt().coerceIn(150, MAX_SNOW)
    }

    // ---- EGL ----

    private fun chooseConfig(): EGLConfig? {
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)

        val ok = EGL14.eglChooseConfig(dpy, attrs, 0, configs, 0, 1, count, 0)

        return if (ok && count[0] > 0) configs[0] else null
    }

    private fun setup() {
        if (released) return

        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)

        val version = IntArray(2)
        check(EGL14.eglInitialize(dpy, version, 0, version, 1)) { "eglInitialize" }

        cfg = chooseConfig()
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
        readSettings()

        ready = true

        if (visible && !released) {
            lastNanos = 0L
            handler.post(frameRunnable)
        }
    }

    private fun initGl() {
        roomProg = makeProgram("комнаты", RoomShaders.ROOM_VS, RoomShaders.ROOM_FS, "aPos", "aNormal", "aUV", "aColor")
        fx = makeProgram("огня", RoomShaders.FX_VS, RoomShaders.FX_FS, "aPos")
        points = makeProgram("искр", BeeShaders.POINT_VS, BeeShaders.POINT_FS, "aPos", "aColor", "aSize")
        snowPts = makeProgram("снега", BeeShaders.POINT_VS, RoomShaders.SNOW_FS, "aPos", "aColor", "aSize")
        brightProg = makeProgram("свечения", RoomShaders.POST_VS, RoomShaders.BRIGHT_FS, "aPos")
        blurProg = makeProgram("размытия", RoomShaders.POST_VS, RoomShaders.BLUR_FS, "aPos")
        compProg = makeProgram("цвета", RoomShaders.POST_VS, RoomShaders.COMP_FS, "aPos")

        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        quadVbo = ids[0]
        pointVbo = ids[1]

        val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quad.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        quad.position(0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 8 * 4, quad, GLES20.GL_STATIC_DRAW)

        val range = FloatArray(2)
        GLES20.glGetFloatv(GLES20.GL_ALIASED_POINT_SIZE_RANGE, range, 0)
        maxPoint = max(range[1], 8f)

        GLES20.glDisable(GLES20.GL_CULL_FACE)

        try {
            data = RoomData(context)
        } catch (e: Throwable) {
            report("данные комнаты", e)
            data = null
        }

        try {
            atlas = loadAtlas()
        } catch (e: Throwable) {
            report("текстуры комнаты", e)
            atlas = 0
        }
    }

    private fun loadAtlas(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)

        val bmp = context.assets.open("room/room_atlas.png").use {
            BitmapFactory.decodeStream(it)
        } ?: error("Не удалось прочитать атлас комнаты")

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()

        return ids[0]
    }

    private fun teardown() {
        ready = false

        if (dpy != EGL14.EGL_NO_DISPLAY) {
            if (surf != EGL14.EGL_NO_SURFACE && ctx != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglMakeCurrent(dpy, surf, surf, ctx)

                data?.release()
                roomProg?.delete()
                fx?.delete()
                points?.delete()
                snowPts?.delete()
                brightProg?.delete()
                blurProg?.delete()
                compProg?.delete()
                freeTargets()

                if (atlas != 0) GLES20.glDeleteTextures(1, intArrayOf(atlas), 0)
                if (quadVbo != 0) GLES20.glDeleteBuffers(2, intArrayOf(quadVbo, pointVbo), 0)
            }

            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)

            if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf)
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)

            EGL14.eglTerminate(dpy)
        }

        data = null
        roomProg = null; fx = null; points = null; snowPts = null
        brightProg = null; blurProg = null; compProg = null
        sceneFbo = 0; sceneTex = 0; sceneDepth = 0; fbW = 0; fbH = 0
        atlas = 0; quadVbo = 0; pointVbo = 0
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
                    ready = false
                }

                return
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Ошибка кадра", e)
            report("кадр", e)
            ready = false
            return
        }

        if (!firstFrameDone) {
            firstFrameDone = true

            // Первый кадр без ошибок: старая запись об ошибке больше не нужна.
            if (failed.isEmpty()) {
                try {
                    SnowSettings.setRoomError(context, null)
                } catch (ignored: Throwable) {
                }
            }
        }

        val spent = (System.nanoTime() - t0) / 1_000_000L

        // Если телефон не тянет шейдеры (кадр дольше ~38 мс), постобработка отключается сама.
        if (postOn) {
            framesSeen++
            slowAvg = if (framesSeen == 1) spent.toFloat() else slowAvg * 0.92f + spent * 0.08f

            if (framesSeen > 120 && slowAvg > 38f) {
                postOn = false
                Log.w(TAG, "Постобработка выключена: кадр $slowAvg мс")
            }
        }
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

    private fun render() {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.033f else ((now - lastNanos) / 1e9f).coerceIn(0.001f, 0.05f)
        lastNanos = now
        clock += dt
        frameDt = dt

        if (!surfaceSize()) return

        val aspect = width.toFloat() / height.toFloat()
        val fov = if (aspect > 1.2f) 54f else 72f
        val tanV = tan(Math.toRadians(fov / 2.0)).toFloat()

        scalePx = height / (2f * tanV)

        // Кинематографичный кадр: камера медленно дрейфует, как будто стоит в руках оператора.
        val t = clock

        eye[0] = 11.3f + sin(t * 0.13f) * 0.35f
        eye[1] = 1.6f + sin(t * 0.09f) * 0.06f
        eye[2] = 11.3f + cos(t * 0.11f) * 0.30f
        tgt[0] = 3.3f + sin(t * 0.07f + 1f) * 0.25f
        tgt[1] = 1.45f + sin(t * 0.05f) * 0.05f
        tgt[2] = 1.7f

        Matrix.perspectiveM(proj, 0, fov, aspect, 0.1f, 80f)
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], tgt[0], tgt[1], tgt[2], 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        val post = postOn && brightProg != null && blurProg != null && compProg != null &&
            !failed.contains("постобработка") && ensureTargets()

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, if (post) sceneFbo else 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0.03f, 0.02f, 0.03f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)

        val d = data

        if (d != null && roomProg != null && atlas != 0) guard("комната") { drawRoom(d) }

        if (d != null) {
            guard("огонь") { drawFlames(d) }
            guard("свет") { drawHalos(d) }
            guard("частицы") { drawParticles(d, dt) }
        }

        if (post) {
            guard("постобработка") { drawPost() }

            if (failed.contains("постобработка")) {
                postOn = false
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            }
        }

        guard("виньетка") { drawVignette() }

        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    // ---- Комната и движущиеся предметы ----

    private fun rotAbout(out: FloatArray, p: FloatArray, angle: Float, ax: Float, ay: Float, az: Float) {
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, p[0], p[1], p[2])
        Matrix.rotateM(out, 0, angle, ax, ay, az)
        Matrix.translateM(out, 0, -p[0], -p[1], -p[2])
    }

    private fun drawGroup(d: RoomData, p: GlProgram, name: String, m: FloatArray, tint: Float) {
        val g = d.group(name) ?: return

        GLES20.glUniformMatrix4fv(p.u("uModel"), 1, false, m, 0)
        GLES20.glUniform3f(p.u("uTint"), tint, tint, tint)
        d.draw(g)
    }

    private fun drawRoom(d: RoomData) {
        val p = roomProg ?: return
        val t = clock

        // Свет: огонь и свечи мерцают, у лампы слабее.
        val n = min(d.lights.size, 8)

        for (i in 0 until 8) {
            if (i < n) {
                val l = d.lights[i]
                val f = 1f + l.flicker * (0.6f * sin(t * (7f + i * 1.3f) + i) + 0.4f * sin(t * (13f + i * 2f) + i * 2f))

                lPos[i * 3] = l.x; lPos[i * 3 + 1] = l.y; lPos[i * 3 + 2] = l.z
                lCol[i * 3] = l.r * f; lCol[i * 3 + 1] = l.g * f; lCol[i * 3 + 2] = l.b * f
                lRad[i] = l.radius
            } else {
                lPos[i * 3] = 0f; lPos[i * 3 + 1] = 0f; lPos[i * 3 + 2] = 0f
                lCol[i * 3] = 0f; lCol[i * 3 + 1] = 0f; lCol[i * 3 + 2] = 0f
                lRad[i] = 1f
            }
        }

        p.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlas)
        GLES20.glUniform1i(p.u("uTex"), 0)
        GLES20.glUniformMatrix4fv(p.u("uVP"), 1, false, vp, 0)
        GLES20.glUniform3f(p.u("uAmbient"), 0.075f, 0.062f, 0.10f)
        GLES20.glUniform3f(p.u("uMoonDir"), -0.94f, 0.31f, 0.10f)
        GLES20.glUniform3f(p.u("uMoonCol"), 0.10f, 0.14f, 0.30f)
        GLES20.glUniform3fv(p.u("uLPos"), 8, lPos, 0)
        GLES20.glUniform3fv(p.u("uLCol"), 8, lCol, 0)
        GLES20.glUniform1fv(p.u("uLRad"), 8, lRad, 0)
        GLES20.glUniform1f(p.u("uPass"), 0f)
        GLES20.glUniform3f(p.u("uEye"), eye[0], eye[1], eye[2])
        GLES20.glUniform1f(p.u("uFog"), if (postOn) 1f else 0f)

        for (g in d.groups) {
            if (g.name.startsWith("static")) drawGroup(d, p, g.name, ident, 1f)
        }

        // Гирлянды мерцают двумя группами в противофазе, звезда на макушке дышит.
        drawGroup(d, p, "treeA", ident, 0.55f + 0.45f * sin(t * 2.3f))
        drawGroup(d, p, "treeB", ident, 0.55f + 0.45f * sin(t * 2.3f + 3.14f))
        drawGroup(d, p, "star", ident, 0.85f + 0.15f * sin(t * 1.7f))

        // Кресло-качалка с Сантой: поворот вокруг оси сиденья.
        val cp = d.point("chairpiv")
        val ca = d.point("chairax")
        val angle = 3.4f * sin(t * 1.75f) + 0.8f * sin(t * 0.9f)

        rotAbout(chairM, cp, angle, ca[0], ca[1], ca[2])
        drawGroup(d, p, "chair", chairM, 1f)

        // Лиса спит: дышит, иногда шевелит ухом и меняет позу.
        drawFox(d, p, frameDt)

        // Носки качаются на каминной полке.
        for (i in 0 until 3) {
            val sp = d.point("stock$i")

            rotAbout(m1, sp, 3.0f * sin(t * 1.3f + i * 1.7f), 0f, 0f, 1f)
            drawGroup(d, p, "stock$i", m1, 1f)
        }

        // Подвесной фонарь чуть покачивается на цепи.
        val lp = d.point("lanternpiv")

        rotAbout(m1, lp, 2.2f * sin(t * 0.9f), 1f, 0f, 0.4f)
        drawGroup(d, p, "lantern0", m1, 1f)

        // Стекло окна поверх всего.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glUniform1f(p.u("uPass"), 1f)
        drawGroup(d, p, "static", ident, 1f)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    // ---- Постобработка: свечение, цвет, зерно ----

    private fun makeTexture(w: Int, h: Int): Int {
        val ids = IntArray(1)

        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)

        return ids[0]
    }

    private fun makeFbo(tex: Int, depth: Int): Int {
        val ids = IntArray(1)

        GLES20.glGenFramebuffers(1, ids, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)

        if (depth != 0) {
            GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, depth)
        }

        val ok = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        if (!ok) {
            GLES20.glDeleteFramebuffers(1, ids, 0)
            return 0
        }

        return ids[0]
    }

    private fun freeTargets() {
        if (sceneFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(sceneFbo), 0)
        if (bloomFbo[0] != 0 || bloomFbo[1] != 0) GLES20.glDeleteFramebuffers(2, bloomFbo, 0)
        if (sceneTex != 0) GLES20.glDeleteTextures(1, intArrayOf(sceneTex), 0)
        if (bloomTex[0] != 0 || bloomTex[1] != 0) GLES20.glDeleteTextures(2, bloomTex, 0)
        if (sceneDepth != 0) GLES20.glDeleteRenderbuffers(1, intArrayOf(sceneDepth), 0)

        sceneFbo = 0; sceneTex = 0; sceneDepth = 0
        bloomFbo[0] = 0; bloomFbo[1] = 0
        bloomTex[0] = 0; bloomTex[1] = 0
        fbW = 0; fbH = 0
    }

    // Цель для сцены (цвет и глубина) и две уменьшенные в 4 раза цели для свечения.
    private fun ensureTargets(): Boolean {
        if (sceneFbo != 0 && fbW == width && fbH == height) return true

        freeTargets()

        fbW = width
        fbH = height
        bloomW = max(fbW / 4, 16)
        bloomH = max(fbH / 4, 16)

        sceneTex = makeTexture(fbW, fbH)

        val rb = IntArray(1)

        GLES20.glGenRenderbuffers(1, rb, 0)
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, rb[0])
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, fbW, fbH)
        sceneDepth = rb[0]
        sceneFbo = makeFbo(sceneTex, sceneDepth)

        for (i in 0..1) {
            bloomTex[i] = makeTexture(bloomW, bloomH)
            bloomFbo[i] = makeFbo(bloomTex[i], 0)
        }

        val ok = sceneFbo != 0 && bloomFbo[0] != 0 && bloomFbo[1] != 0

        if (!ok) {
            freeTargets()
            postOn = false
            Log.w(TAG, "Постобработка недоступна: не удалось создать буферы")
        }

        return ok
    }

    private fun blurPass(p: GlProgram, srcTex: Int, dstFbo: Int, dx: Float, dy: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFbo)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
        GLES20.glUniform2f(p.u("uDir"), dx, dy)
        drawFxQuad()
    }

    private fun drawPost() {
        val bp = brightProg ?: return
        val up = blurProg ?: return
        val cp = compProg ?: return

        try {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

            // Яркие места сцены в уменьшенную копию.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bloomFbo[0])
            GLES20.glViewport(0, 0, bloomW, bloomH)
            bp.use()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTex)
            GLES20.glUniform1i(bp.u("uTex"), 0)
            GLES20.glUniform2f(bp.u("uTexel"), 1f / fbW, 1f / fbH)
            drawFxQuad()

            // Два прохода размытия, второй шире: мягкое свечение вокруг огня и ламп.
            up.use()
            GLES20.glUniform1i(up.u("uTex"), 0)
            blurPass(up, bloomTex[0], bloomFbo[1], 1f / bloomW, 0f)
            blurPass(up, bloomTex[1], bloomFbo[0], 0f, 1f / bloomH)
            blurPass(up, bloomTex[0], bloomFbo[1], 2.4f / bloomW, 0f)
            blurPass(up, bloomTex[1], bloomFbo[0], 0f, 2.4f / bloomH)

            // Сборка на экран: сцена, свечение, цветокоррекция, зерно.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, width, height)
            cp.use()
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTex)
            GLES20.glUniform1i(cp.u("uScene"), 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bloomTex[0])
            GLES20.glUniform1i(cp.u("uBloom"), 1)
            GLES20.glUniform1f(cp.u("uBloomK"), 0.55f)
            GLES20.glUniform1f(cp.u("uTime"), clock)
            drawFxQuad()
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        }
    }

    // ---- Спящая лиса: корпус, голова, уши и хвост двигаются отдельно ----

    private fun foxLerp(k: Float) {
        val e = k * k * (3f - 2f * k)

        for (i in 0 until 7) foxCur[i] = foxA[i] + (foxB[i] - foxA[i]) * e
    }

    // Раз в 18-40 секунд лиса приподнимает голову, оглядывается и ложится в другую позу.
    private fun foxStep(dt: Float) {
        val t = clock

        if (foxStage == 0) {
            if (t >= foxNext) {
                System.arraycopy(foxCur, 0, foxA, 0, 7)
                System.arraycopy(FOX_POSES[3], 0, foxB, 0, 7)
                foxStage = 1
                foxT = 0f
                foxDur = 1.3f
            }

            return
        }

        foxT += dt / foxDur

        if (foxT < 1f) {
            foxLerp(foxT)
            return
        }

        foxLerp(1f)
        foxT = 0f

        when (foxStage) {
            1 -> {
                System.arraycopy(foxB, 0, foxA, 0, 7)
                foxStage = 2
                foxDur = 1.4f
            }

            2 -> {
                foxPose = (foxPose + 1 + rnd.nextInt(2)) % 3
                System.arraycopy(foxB, 0, foxA, 0, 7)
                System.arraycopy(FOX_POSES[foxPose], 0, foxB, 0, 7)
                foxStage = 3
                foxDur = 2.4f
            }

            else -> {
                System.arraycopy(foxB, 0, foxA, 0, 7)
                foxStage = 0
                foxNext = t + 18f + rnd.nextFloat() * 22f
            }
        }
    }

    private fun drawFox(d: RoomData, p: GlProgram, dt: Float) {
        val t = clock

        foxStep(dt)

        val root = d.point("fx_root")
        val yaw0 = d.point("fx_yaw")[0]

        Matrix.setIdentityM(foxRoot, 0)
        Matrix.translateM(foxRoot, 0, root[0], root[1], root[2])
        Matrix.rotateM(foxRoot, 0, yaw0 + foxCur[0], 0f, 1f, 0f)

        // Дыхание: тело чуть растягивается по высоте.
        val br = 0.022f * sin(t * 1.6f)

        Matrix.setIdentityM(fa, 0)
        Matrix.scaleM(fa, 0, 1f + br * 0.4f, 1f + br, 1f + br * 0.4f)
        Matrix.multiplyMM(foxBody, 0, foxRoot, 0, fa, 0)
        drawGroup(d, p, "fbody", foxBody, 1f)
        drawGroup(d, p, "fpaws", foxRoot, 1f)

        // Голова: поворот, наклон и крен вокруг шеи.
        val ph = d.point("fp_head")

        rotAbout(fa, ph, foxCur[2], 0f, 1f, 0f)
        rotAbout(fb, ph, foxCur[1], 0f, 0f, 1f)
        Matrix.multiplyMM(fc, 0, fa, 0, fb, 0)
        rotAbout(fa, ph, foxCur[3], 1f, 0f, 0f)
        Matrix.multiplyMM(fb, 0, fc, 0, fa, 0)
        Matrix.multiplyMM(foxHead, 0, foxRoot, 0, fb, 0)
        drawGroup(d, p, "fhead", foxHead, 1f)

        // Уши: иногда дёргаются.
        if (t >= foxEarAt) {
            foxEarT0 = t
            foxEarAt = t + 5f + rnd.nextFloat() * 9f
        }

        val et = (t - foxEarT0) / 0.4f
        val twitch = if (et in 0f..1f) -18f * sin(et * 6.2832f) * (1f - et) else 0f

        val pl = d.point("fp_earl")

        rotAbout(fa, pl, foxCur[6] + twitch, 0f, 0f, 1f)
        Matrix.multiplyMM(fb, 0, foxHead, 0, fa, 0)
        drawGroup(d, p, "fearl", fb, 1f)

        val pr = d.point("fp_earr")

        rotAbout(fa, pr, foxCur[6] - twitch * 0.4f, 0f, 0f, 1f)
        Matrix.multiplyMM(fb, 0, foxHead, 0, fa, 0)
        drawGroup(d, p, "fearr", fb, 1f)

        // Хвост: чуть заметно покачивается.
        val pt = d.point("fp_tail")

        rotAbout(fa, pt, foxCur[4] + 4f * sin(t * 1.1f), 0f, 1f, 0f)
        rotAbout(fb, pt, foxCur[5], 0f, 0f, 1f)
        Matrix.multiplyMM(fc, 0, fa, 0, fb, 0)
        Matrix.multiplyMM(fa, 0, foxRoot, 0, fc, 0)
        drawGroup(d, p, "ftail", fa, 1f)
    }

    // ---- Огонь, ореолы, виньетка ----

    private fun drawFxQuad() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(0)
    }

    private fun flame(p: GlProgram, cx: Float, cy: Float, cz: Float, w: Float, h: Float, tint: Float, wz: Float = w) {
        GLES20.glUniform4f(p.u("uColor"), tint, tint, tint, 1f)

        GLES20.glUniform3f(p.u("uC"), cx, cy, cz)
        GLES20.glUniform3f(p.u("uR"), w, 0f, 0f)
        GLES20.glUniform3f(p.u("uU"), 0f, h, 0f)
        drawFxQuad()

        GLES20.glUniform3f(p.u("uR"), 0f, 0f, wz)
        drawFxQuad()
    }

    private fun drawFlames(d: RoomData) {
        val p = fx ?: return
        val t = clock

        p.use()
        GLES20.glUniformMatrix4fv(p.u("uVP"), 1, false, vp, 0)
        GLES20.glUniform1f(p.u("uWorld"), 1f)
        GLES20.glUniform1f(p.u("uMode"), 0f)

        GLES20.glDepthMask(false)

        // Камин: несколько языков пламени разной высоты.
        val f = d.point("fire")
        val k = 0.93f + 0.07f * sin(t * 9f)

        GLES20.glUniform1f(p.u("uTime"), t)
        flame(p, f[0], f[1] + 0.85f, f[2], 0.9f, 0.85f * k, 1f, 0.55f)
        GLES20.glUniform1f(p.u("uTime"), t + 0.37f)
        flame(p, f[0] - 0.45f, f[1] + 0.55f, f[2] + 0.1f, 0.5f, 0.5f * k, 0.95f, 0.3f)
        GLES20.glUniform1f(p.u("uTime"), t + 0.71f)
        flame(p, f[0] + 0.5f, f[1] + 0.5f, f[2] - 0.05f, 0.45f, 0.45f * k, 0.95f, 0.3f)

        // Свечи на полке.
        for (i in 0 until 8) {
            if (!d.points.containsKey("candle$i")) continue

            val c = d.point("candle$i")

            GLES20.glUniform1f(p.u("uTime"), t + i * 0.5f)
            flame(p, c[0], c[1] + 0.1f, c[2], 0.06f, 0.13f * (0.9f + 0.1f * sin(t * 11f + i * 2f)), 1f)
        }

        GLES20.glDepthMask(true)
    }

    private fun drawHalos(d: RoomData) {
        val p = fx ?: return
        val t = clock

        p.use()
        GLES20.glUniformMatrix4fv(p.u("uVP"), 1, false, vp, 0)
        GLES20.glUniform1f(p.u("uWorld"), 1f)
        GLES20.glUniform1f(p.u("uMode"), 1f)

        // Свечение вокруг ламп: мягкие плоскости, повёрнутые к камере.
        val rx = view[0]; val ry = view[4]; val rz = view[8]
        val ux = view[1]; val uy = view[5]; val uz = view[9]

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)

        for ((i, h) in d.halos.withIndex()) {
            val s = h.size * (0.93f + 0.07f * sin(t * (5f + i) + i))

            GLES20.glUniform3f(p.u("uC"), h.x, h.y, h.z)
            GLES20.glUniform3f(p.u("uR"), rx * s, ry * s, rz * s)
            GLES20.glUniform3f(p.u("uU"), ux * s, uy * s, uz * s)
            GLES20.glUniform4f(p.u("uColor"), h.r, h.g, h.b, 0.5f)
            drawFxQuad()
        }

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawVignette() {
        val p = fx ?: return

        p.use()
        GLES20.glUniform1f(p.u("uWorld"), 0f)
        GLES20.glUniform1f(p.u("uMode"), 2f)
        GLES20.glUniform4f(p.u("uColor"), 0f, 0f, 0f, 0.62f)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        drawFxQuad()
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    // ---- Частицы: снег за окном, искры, пылинки, пар от кружки ----

    private fun put(n: Int, x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float, size: Float) {
        val o = n * POINT_FLOATS

        pointData[o] = x; pointData[o + 1] = y; pointData[o + 2] = z
        pointData[o + 3] = r; pointData[o + 4] = g; pointData[o + 5] = b; pointData[o + 6] = a
        pointData[o + 7] = size
    }

    private fun drawParticles(d: RoomData, dt: Float) {
        val t = clock
        val speedMul = snowSpeed / 100f

        // Снег за окном падает и слегка сносится ветром.
        for (i in 0 until snowCount) {
            sy[i] -= (1.1f + sRate[i] * 0.9f) * speedMul * dt
            sx[i] += (0.35f * speedMul + sin(t * 0.8f + sPhase[i]) * 0.25f) * dt
            sz[i] += cos(t * 0.6f + sPhase[i]) * 0.2f * dt

            if (sy[i] < 0.95f) resetSnow(i, false)
            if (sx[i] > -0.3f) sx[i] -= 11.6f
            if (sx[i] < -12f) sx[i] += 11.6f

            put(i, sx[i], sy[i], sz[i], 0.82f, 0.9f, 1.0f, 0.85f, 0.085f)
        }

        drawBatch(snowPts, snowCount)

        // Искры из камина.
        var n = 0

        for (i in 0 until EMBERS) {
            eAge[i] += dt

            if (eAge[i] >= eMax[i]) resetEmber(i)

            ex[i] += eVx[i] * dt
            ey[i] += eVy[i] * dt

            val life = 1f - eAge[i] / eMax[i]

            put(n++, ex[i] + sin(t * 3f + i) * 0.05f, ey[i], ez[i], 1.0f, 0.55f + 0.3f * life, 0.15f, life, 0.045f)
        }

        // Пылинки в тёплом свете.
        for (i in 0 until DUST) {
            val a = 0.16f + 0.14f * sin(t * 0.7f + dustPhase[i] * 2f)

            put(
                n++,
                dustBase[i * 3] + sin(t * 0.21f + dustPhase[i]) * 0.5f,
                dustBase[i * 3 + 1] + sin(t * 0.17f + dustPhase[i] * 1.3f) * 0.3f,
                dustBase[i * 3 + 2] + cos(t * 0.19f + dustPhase[i]) * 0.5f,
                1.0f, 0.85f, 0.6f, a, 0.04f
            )
        }

        // Пар от кружки Санты поднимается вместе с качанием кресла.
        val mug = d.point("mug")

        vec4[0] = mug[0]; vec4[1] = mug[1]; vec4[2] = mug[2]; vec4[3] = 1f

        Matrix.multiplyMV(out4, 0, chairM, 0, vec4, 0)

        for (i in 0 until STEAM) {
            stAge[i] += dt

            if (stAge[i] > 1.8f) stAge[i] = 0f

            val p = stAge[i] / 1.8f

            put(
                n++,
                out4[0] + sin(t * 1.4f + i * 1.7f) * 0.05f * (0.4f + p),
                out4[1] + 0.12f + p * 0.8f,
                out4[2] + cos(t * 1.1f + i) * 0.04f,
                1.0f, 1.0f, 1.0f, 0.30f * (1f - p), 0.06f + 0.12f * p
            )
        }

        drawBatch(points, n, true)
    }

    private fun drawBatch(p: GlProgram?, count: Int, additive: Boolean = false) {
        if (p == null || count <= 0) return

        val n = min(count, MAX_POINTS)

        pointBuffer.position(0)
        pointBuffer.put(pointData, 0, n * POINT_FLOATS)
        pointBuffer.position(0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pointVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, n * POINT_FLOATS * 4, pointBuffer, GLES20.GL_STREAM_DRAW)

        p.use()
        GLES20.glUniformMatrix4fv(p.u("uVP"), 1, false, vp, 0)
        GLES20.glUniform1f(p.u("uScale"), scalePx)
        GLES20.glUniform1f(p.u("uMaxPoint"), maxPoint)

        GLES20.glEnable(GLES20.GL_BLEND)

        if (additive) {
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        } else {
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }

        GLES20.glDepthMask(false)

        GLES20.glEnableVertexAttribArray(0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glEnableVertexAttribArray(2)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, POINT_FLOATS * 4, 0)
        GLES20.glVertexAttribPointer(1, 4, GLES20.GL_FLOAT, false, POINT_FLOATS * 4, 12)
        GLES20.glVertexAttribPointer(2, 1, GLES20.GL_FLOAT, false, POINT_FLOATS * 4, 28)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, n)
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
        GLES20.glDisableVertexAttribArray(2)

        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
}
