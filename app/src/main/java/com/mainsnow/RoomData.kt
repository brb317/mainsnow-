package com.mainsnow

import android.content.Context
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Группа вершин сцены: статичная комната или отдельный движущийся предмет (кресло, носки, фонарь).
internal class RoomGroup(val name: String, val vertCount: Int, val indexCount: Int) {
    var vbo = 0
    var ibo = 0
}

internal class RoomLight(
    val x: Float, val y: Float, val z: Float,
    val r: Float, val g: Float, val b: Float,
    val radius: Float, val flicker: Float
)

internal class RoomHalo(
    val x: Float, val y: Float, val z: Float,
    val r: Float, val g: Float, val b: Float,
    val size: Float
)

// Рождественская комната из assets/room/room.bin (собрана скриптом из блоков в стиле Minecraft).
// Вершина 24 байта: позиция (3 float), нормаль (4 byte), UV (2 ushort), цвет и свечение (4 ubyte).
// Вызывать нужно на GL-потоке при активном контексте.
internal class RoomData(context: Context) {

    companion object {
        const val VERTEX_BYTES = 24
    }

    val groups = ArrayList<RoomGroup>()
    val lights = ArrayList<RoomLight>()
    val halos = ArrayList<RoomHalo>()
    val points = HashMap<String, FloatArray>()

    init {
        load(context)
    }

    fun group(name: String): RoomGroup? {
        for (g in groups) if (g.name == name) return g

        return null
    }

    fun point(name: String): FloatArray = points[name] ?: floatArrayOf(0f, 0f, 0f)

    private fun readName(bb: ByteBuffer): String {
        val raw = ByteArray(16)
        bb.get(raw)

        var n = 0
        while (n < 16 && raw[n].toInt() != 0) n++

        return String(raw, 0, n, Charsets.US_ASCII)
    }

    private fun load(context: Context) {
        val raw = context.assets.open("room/room.bin").use { it.readBytes() }
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

        val magic = ByteArray(4)
        bb.get(magic)

        check(String(magic, Charsets.US_ASCII) == "RMS1") { "Неверный формат комнаты" }

        val groupCount = bb.getInt()
        val lightCount = bb.getInt()
        val haloCount = bb.getInt()
        val pointCount = bb.getInt()

        for (i in 0 until groupCount) {
            val name = readName(bb)
            val nv = bb.getInt()
            val ni = bb.getInt()

            groups.add(RoomGroup(name, nv, ni))
        }

        for (i in 0 until lightCount) {
            lights.add(
                RoomLight(
                    bb.getFloat(), bb.getFloat(), bb.getFloat(),
                    bb.getFloat(), bb.getFloat(), bb.getFloat(),
                    bb.getFloat(), bb.getFloat()
                )
            )
        }

        for (i in 0 until haloCount) {
            halos.add(
                RoomHalo(
                    bb.getFloat(), bb.getFloat(), bb.getFloat(),
                    bb.getFloat(), bb.getFloat(), bb.getFloat(),
                    bb.getFloat()
                )
            )
        }

        for (i in 0 until pointCount) {
            val name = readName(bb)

            points[name] = floatArrayOf(bb.getFloat(), bb.getFloat(), bb.getFloat())
        }

        for (g in groups) {
            val vlen = g.vertCount * VERTEX_BYTES
            val ilen = g.indexCount * 2

            val vb = ByteBuffer.allocateDirect(vlen).order(ByteOrder.nativeOrder())
            vb.put(raw, bb.position(), vlen)
            vb.position(0)

            val ib = ByteBuffer.allocateDirect(ilen).order(ByteOrder.nativeOrder())
            ib.put(raw, bb.position() + vlen, ilen)
            ib.position(0)

            bb.position(bb.position() + vlen + ilen)

            val ids = IntArray(2)
            GLES20.glGenBuffers(2, ids, 0)

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vlen, vb, GLES20.GL_STATIC_DRAW)

            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ids[1])
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, ilen, ib, GLES20.GL_STATIC_DRAW)

            g.vbo = ids[0]
            g.ibo = ids[1]
        }
    }

    // Атрибуты: 0 — позиция, 1 — нормаль, 2 — UV, 3 — цвет и свечение.
    fun draw(g: RoomGroup) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, g.vbo)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, g.ibo)

        for (i in 0..3) GLES20.glEnableVertexAttribArray(i)

        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, VERTEX_BYTES, 0)
        GLES20.glVertexAttribPointer(1, 4, GLES20.GL_BYTE, true, VERTEX_BYTES, 12)
        GLES20.glVertexAttribPointer(2, 2, GLES20.GL_UNSIGNED_SHORT, true, VERTEX_BYTES, 16)
        GLES20.glVertexAttribPointer(3, 4, GLES20.GL_UNSIGNED_BYTE, true, VERTEX_BYTES, 20)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, g.indexCount, GLES20.GL_UNSIGNED_SHORT, 0)

        for (i in 0..3) GLES20.glDisableVertexAttribArray(i)
    }

    fun release() {
        for (g in groups) {
            if (g.vbo != 0) {
                GLES20.glDeleteBuffers(2, intArrayOf(g.vbo, g.ibo), 0)
                g.vbo = 0
                g.ibo = 0
            }
        }
    }
}
