package com.mainsnow

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Вершина: pos(3) normal(3) uv(2) color(4). Один формат и для сцены, и для модели пчелы.
internal const val VERTEX_FLOATS = 12
private const val VERTEX_BYTES = VERTEX_FLOATS * 4

internal object BeeShaders {

    const val LIT_VS = """
attribute vec3 aPos;
attribute vec3 aNormal;
attribute vec2 aUV;
attribute vec4 aColor;
uniform mat4 uVP;
uniform mat4 uModel;
uniform vec3 uEye;
varying vec3 vN;
varying vec2 vUV;
varying vec4 vColor;
varying float vDist;
void main() {
    vec4 wp = uModel * vec4(aPos, 1.0);
    vN = (uModel * vec4(aNormal, 0.0)).xyz;
    vUV = aUV;
    vColor = aColor;
    vDist = length(wp.xyz - uEye);
    gl_Position = uVP * wp;
}
"""

    const val LIT_FS = """
precision mediump float;
uniform sampler2D uTex;
uniform float uUseTex;
uniform float uTwoSided;
uniform float uEmissive;
uniform vec4 uTint;
uniform vec3 uLightDir;
uniform vec3 uLightCol;
uniform vec3 uAmbient;
uniform vec3 uFogCol;
uniform vec2 uFog;
varying vec3 vN;
varying vec2 vUV;
varying vec4 vColor;
varying float vDist;
void main() {
    vec4 base = vColor;
    if (uUseTex > 0.5) base = texture2D(uTex, vUV);
    base *= uTint;
    vec3 n = normalize(vN);
    if (uTwoSided > 0.5 && !gl_FrontFacing) n = -n;
    float d = max(dot(n, uLightDir), 0.0);
    vec3 lit = base.rgb * (uAmbient + uLightCol * d);
    lit = mix(lit, base.rgb, uEmissive);
    float f = clamp((vDist - uFog.x) / (uFog.y - uFog.x), 0.0, 1.0);
    gl_FragColor = vec4(mix(lit, uFogCol, f), base.a);
}
"""

    const val SKY_VS = """
attribute vec2 aPos;
varying float vY;
void main() {
    vY = aPos.y * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.999, 1.0);
}
"""

    const val SKY_FS = """
precision mediump float;
uniform vec3 uTop;
uniform vec3 uHor;
uniform float uHorizon;
varying float vY;
void main() {
    float t = clamp((vY - uHorizon) / (1.0 - uHorizon), 0.0, 1.0);
    t = pow(t, 0.75);
    gl_FragColor = vec4(mix(uHor, uTop, t), 1.0);
}
"""

    // Светлячки, пыльца, звёзды: мягкие круглые точки, размер задаётся в единицах мира.
    const val POINT_VS = """
attribute vec3 aPos;
attribute vec4 aColor;
attribute float aSize;
uniform mat4 uVP;
uniform float uScale;
uniform float uMaxPoint;
varying vec4 vC;
varying float vK;
void main() {
    vec4 p = uVP * vec4(aPos, 1.0);
    gl_Position = p;
    gl_PointSize = min(abs(aSize) * uScale / max(p.w, 0.1), uMaxPoint);
    vC = aColor;
    vK = 1.0 - step(0.0, aSize);
}
"""

    // Эмоции: спрайт из атласа 4 × 2, номер спрайта кодируется в альфа-канале цвета (id / 10).
    const val EMOTE_FS = """
precision mediump float;
uniform sampler2D uAtlas;
varying vec4 vC;
void main() {
    float id = floor(vC.a * 10.0 + 0.5);
    vec2 cell = vec2(mod(id, 4.0), floor(id / 4.0));
    vec4 c = texture2D(uAtlas, (cell + gl_PointCoord) / vec2(4.0, 2.0));
    if (c.a < 0.5) discard;
    gl_FragColor = vec4(c.rgb * vC.rgb, 1.0);
}
"""

    const val POINT_FS = """
precision mediump float;
varying vec4 vC;
void main() {
    vec2 c = gl_PointCoord - vec2(0.5);
    float a = max(1.0 - dot(c, c) * 4.0, 0.0);
    a = a * a;
    gl_FragColor = vec4(vC.rgb, vC.a * a);
}
"""
}

internal class GlProgram(vs: String, fs: String, vararg attribs: String) {

    val id: Int
    private val locations = HashMap<String, Int>()

    init {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)

        id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, v)
        GLES20.glAttachShader(id, f)

        attribs.forEachIndexed { index, name ->
            GLES20.glBindAttribLocation(id, index, name)
        }

        GLES20.glLinkProgram(id)

        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)

        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(id)
            GLES20.glDeleteProgram(id)
            throw RuntimeException("Ошибка линковки шейдера: $log")
        }

        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)

        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Ошибка компиляции шейдера: $log")
        }

        return shader
    }

    fun use() {
        GLES20.glUseProgram(id)
    }

    fun u(name: String): Int {
        return locations.getOrPut(name) {
            GLES20.glGetUniformLocation(id, name)
        }
    }

    fun delete() {
        GLES20.glDeleteProgram(id)
    }
}

internal class GlMesh(
    private val vbo: Int,
    private val ibo: Int,
    private val indexCount: Int
) {

    fun draw() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)

        GLES20.glEnableVertexAttribArray(0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glEnableVertexAttribArray(2)
        GLES20.glEnableVertexAttribArray(3)

        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, VERTEX_BYTES, 0)
        GLES20.glVertexAttribPointer(1, 3, GLES20.GL_FLOAT, false, VERTEX_BYTES, 12)
        GLES20.glVertexAttribPointer(2, 2, GLES20.GL_FLOAT, false, VERTEX_BYTES, 24)
        GLES20.glVertexAttribPointer(3, 4, GLES20.GL_FLOAT, false, VERTEX_BYTES, 32)

        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES,
            indexCount,
            GLES20.GL_UNSIGNED_SHORT,
            0
        )

        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
        GLES20.glDisableVertexAttribArray(2)
        GLES20.glDisableVertexAttribArray(3)
    }

    fun delete() {
        GLES20.glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
    }

    companion object {
        fun upload(
            vertices: FloatArray,
            floatCount: Int,
            indices: ShortArray,
            indexCount: Int
        ): GlMesh {
            val ids = IntArray(2)
            GLES20.glGenBuffers(2, ids, 0)

            val vb = ByteBuffer.allocateDirect(floatCount * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            vb.put(vertices, 0, floatCount)
            vb.position(0)

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER,
                floatCount * 4,
                vb,
                GLES20.GL_STATIC_DRAW
            )

            val ib = ByteBuffer.allocateDirect(indexCount * 2)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer()
            ib.put(indices, 0, indexCount)
            ib.position(0)

            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ids[1])
            GLES20.glBufferData(
                GLES20.GL_ELEMENT_ARRAY_BUFFER,
                indexCount * 2,
                ib,
                GLES20.GL_STATIC_DRAW
            )

            return GlMesh(ids[0], ids[1], indexCount)
        }
    }
}

// Собирает блочную геометрию в стиле Minecraft. Текущая трансформация применяется ко всем вершинам.
internal class MeshBuilder {

    private var verts = FloatArray(1 shl 14)
    private var floatCount = 0
    private var idx = ShortArray(1 shl 13)
    private var indexCount = 0

    private val tf = FloatArray(16)
    private val vin = FloatArray(4)
    private val vout = FloatArray(4)
    private val corners = FloatArray(12)

    init {
        Matrix.setIdentityM(tf, 0)
    }

    fun identity() {
        Matrix.setIdentityM(tf, 0)
    }

    fun translate(x: Float, y: Float, z: Float) {
        Matrix.translateM(tf, 0, x, y, z)
    }

    fun rotate(degrees: Float, ax: Float, ay: Float, az: Float) {
        Matrix.rotateM(tf, 0, degrees, ax, ay, az)
    }

    fun save(): FloatArray = tf.copyOf()

    fun restore(saved: FloatArray) {
        saved.copyInto(tf)
    }

    private fun addVertex(
        px: Float, py: Float, pz: Float,
        nx: Float, ny: Float, nz: Float,
        r: Float, g: Float, b: Float
    ) {
        if (floatCount + VERTEX_FLOATS > verts.size) {
            verts = verts.copyOf(verts.size * 2)
        }

        vin[0] = px; vin[1] = py; vin[2] = pz; vin[3] = 1f
        Matrix.multiplyMV(vout, 0, tf, 0, vin, 0)
        val o = floatCount

        verts[o] = vout[0]
        verts[o + 1] = vout[1]
        verts[o + 2] = vout[2]

        vin[0] = nx; vin[1] = ny; vin[2] = nz; vin[3] = 0f
        Matrix.multiplyMV(vout, 0, tf, 0, vin, 0)

        verts[o + 3] = vout[0]
        verts[o + 4] = vout[1]
        verts[o + 5] = vout[2]
        verts[o + 6] = 0f
        verts[o + 7] = 0f
        verts[o + 8] = r
        verts[o + 9] = g
        verts[o + 10] = b
        verts[o + 11] = 1f

        floatCount += VERTEX_FLOATS
    }

    // Четыре угла в corners, нормаль и цвет общие на грань.
    private fun quad(nx: Float, ny: Float, nz: Float, r: Float, g: Float, b: Float) {
        val base = floatCount / VERTEX_FLOATS

        for (i in 0 until 4) {
            addVertex(
                corners[i * 3], corners[i * 3 + 1], corners[i * 3 + 2],
                nx, ny, nz, r, g, b
            )
        }

        if (indexCount + 6 > idx.size) {
            idx = idx.copyOf(idx.size * 2)
        }

        idx[indexCount++] = base.toShort()
        idx[indexCount++] = (base + 1).toShort()
        idx[indexCount++] = (base + 2).toShort()
        idx[indexCount++] = base.toShort()
        idx[indexCount++] = (base + 2).toShort()
        idx[indexCount++] = (base + 3).toShort()
    }

    private fun set(i: Int, x: Float, y: Float, z: Float) {
        corners[i * 3] = x
        corners[i * 3 + 1] = y
        corners[i * 3 + 2] = z
    }

    // Горизонтальная плитка земли, нормаль вверх.
    fun groundTile(x0: Float, z0: Float, x1: Float, z1: Float, y: Float, r: Float, g: Float, b: Float) {
        set(0, x0, y, z0)
        set(1, x1, y, z0)
        set(2, x1, y, z1)
        set(3, x0, y, z1)
        quad(0f, 1f, 0f, r, g, b)
    }

    // Коробка: центр и половинные размеры.
    fun box(
        cx: Float, cy: Float, cz: Float,
        hx: Float, hy: Float, hz: Float,
        r: Float, g: Float, b: Float
    ) {
        val x0 = cx - hx; val x1 = cx + hx
        val y0 = cy - hy; val y1 = cy + hy
        val z0 = cz - hz; val z1 = cz + hz

        set(0, x1, y0, z0); set(1, x1, y1, z0); set(2, x1, y1, z1); set(3, x1, y0, z1)
        quad(1f, 0f, 0f, r, g, b)

        set(0, x0, y0, z1); set(1, x0, y1, z1); set(2, x0, y1, z0); set(3, x0, y0, z0)
        quad(-1f, 0f, 0f, r, g, b)

        set(0, x0, y1, z0); set(1, x0, y1, z1); set(2, x1, y1, z1); set(3, x1, y1, z0)
        quad(0f, 1f, 0f, r, g, b)

        set(0, x0, y0, z1); set(1, x0, y0, z0); set(2, x1, y0, z0); set(3, x1, y0, z1)
        quad(0f, -1f, 0f, r, g, b)

        set(0, x0, y0, z1); set(1, x1, y0, z1); set(2, x1, y1, z1); set(3, x0, y1, z1)
        quad(0f, 0f, 1f, r, g, b)

        set(0, x1, y0, z0); set(1, x0, y0, z0); set(2, x0, y1, z0); set(3, x1, y1, z0)
        quad(0f, 0f, -1f, r, g, b)
    }

    fun build(): GlMesh {
        return GlMesh.upload(verts, floatCount, idx, indexCount)
    }
}
