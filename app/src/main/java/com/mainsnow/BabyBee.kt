package com.mainsnow

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

// Маленькая пчела Baby Bee (утро). Готовая сетка лежит в assets/bee/baby.bin, её делает
// tools/bee/make_baby.py из minecraft_bee.glb. Тело и два крыла рисуются отдельно,
// крылья машут вокруг своей оси у спинки. Цвета запечены в вершины, текстура не нужна.
internal class BabyBee(context: Context) {

    private class Part(val mesh: GlMesh, val side: Int, val pivot: FloatArray)

    private val parts = ArrayList<Part>()
    private val m1 = FloatArray(16)
    private val m2 = FloatArray(16)

    init {
        val data = context.assets.open("bee/baby.bin").use { it.readBytes() }
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        bb.position(4)

        val count = bb.int
        val nv = IntArray(count)
        val ni = IntArray(count)
        val side = IntArray(count)
        val piv = Array(count) { FloatArray(3) }

        for (i in 0 until count) {
            nv[i] = bb.int
            ni[i] = bb.int
            side[i] = bb.int
            piv[i][0] = bb.float
            piv[i][1] = bb.float
            piv[i][2] = bb.float
        }

        for (i in 0 until count) {
            val verts = FloatArray(nv[i] * VERTEX_FLOATS)

            for (k in verts.indices) verts[k] = bb.float

            val idx = ShortArray(ni[i])

            for (k in idx.indices) idx[k] = bb.short

            parts.add(Part(GlMesh.upload(verts, verts.size, idx, idx.size), side[i], piv[i]))
        }
    }

    private fun drawWing(p: GlProgram, base: FloatArray, part: Part, angle: Float) {
        val deg = Math.toDegrees(angle.toDouble()).toFloat() * (if (part.side < 0) -1f else 1f)

        Matrix.setIdentityM(m1, 0)
        Matrix.translateM(m1, 0, part.pivot[0], part.pivot[1], part.pivot[2])
        Matrix.rotateM(m1, 0, deg, 0f, 0f, 1f)
        Matrix.translateM(m1, 0, -part.pivot[0], -part.pivot[1], -part.pivot[2])
        Matrix.multiplyMM(m2, 0, base, 0, m1, 0)
        GLES20.glUniformMatrix4fv(p.u("uModel"), 1, false, m2, 0)
        part.mesh.draw()
    }

    // glow: зелёное свечение при взрослении, flapping: быстрые взмахи, folded: крылья сложены.
    fun draw(p: GlProgram, base: FloatArray, clock: Float, flapping: Boolean, folded: Boolean, glow: Float) {
        GLES20.glUniform1f(p.u("uUseTex"), 0f)
        GLES20.glUniformMatrix4fv(p.u("uModel"), 1, false, base, 0)
        GLES20.glUniform4f(p.u("uTint"), 1f - 0.45f * glow, 1f, 1f - 0.45f * glow, 1f)

        for (part in parts) if (part.side == 0) part.mesh.draw()

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)

        val phase = clock * 2f * PI.toFloat() * 11f
        val a0 = if (folded) -0.9f else sin(phase) * 0.75f

        GLES20.glUniform4f(p.u("uTint"), 0.86f, 0.95f, 1f, if (flapping) 0.40f else 0.60f)

        for (part in parts) if (part.side != 0) drawWing(p, base, part, a0)

        if (flapping) {
            val a1 = sin(phase + PI.toFloat()) * 0.75f

            for (part in parts) if (part.side != 0) drawWing(p, base, part, a1)
        }

        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun release() {
        for (part in parts) part.mesh.delete()
        parts.clear()
    }
}
