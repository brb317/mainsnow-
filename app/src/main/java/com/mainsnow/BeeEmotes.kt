package com.mainsnow

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils

// Номера спрайтов в атласе эмоций.
internal object Emote {
    const val HEART = 0
    const val SPARKLE = 1
    const val Z = 2
    const val EXCLAIM = 3
    const val QUESTION = 4
    const val NOTE = 5
    const val DROP = 6
    const val STAR = 7
}

// Атлас 4 × 2 спрайта по 8 × 8 пикселей, нарисованный в коде. Рисуется с фильтром NEAREST.
internal object BeeEmotes {

    private const val CELL = 8
    private const val COLS = 4
    private const val ROWS = 2

    private val palette = mapOf(
        'd' to 0xFF8E1738.toInt(), // контур сердца
        'r' to 0xFFFF4A78.toInt(), // сердце
        'h' to 0xFFFFC2D4.toInt(), // блик сердца
        'y' to 0xFFFFD84A.toInt(), // золото
        'W' to 0xFFFFFFFF.toInt(),
        'z' to 0xFFF2F6FF.toInt(), // буква Z
        's' to 0xFF8FA0D8.toInt(), // тень буквы
        'o' to 0xFFFF9A1F.toInt(), // «!»
        'c' to 0xFF6EC8FF.toInt(), // «?»
        'n' to 0xFFD2A2FF.toInt(), // нота
        'b' to 0xFF5AB4FF.toInt()  // капля
    )

    private val sprites = arrayOf(
        // HEART
        arrayOf(
            ".dd..dd.",
            "drrddrrd",
            "drhrrrrd",
            "drrrrrrd",
            ".drrrrd.",
            "..drrd..",
            "...dd...",
            "........"
        ),
        // SPARKLE
        arrayOf(
            "...yy...",
            "...yy...",
            "..yWWy..",
            "yyWWWWyy",
            "yyWWWWyy",
            "..yWWy..",
            "...yy...",
            "...yy..."
        ),
        // Z
        arrayOf(
            "zzzzzz..",
            "....zs..",
            "...zs...",
            "..zs....",
            ".zs.....",
            "zzzzzzs.",
            ".ssssss.",
            "........"
        ),
        // EXCLAIM
        arrayOf(
            "...oo...",
            "...oo...",
            "...oo...",
            "...oo...",
            "...oo...",
            "........",
            "...oo...",
            "...oo..."
        ),
        // QUESTION
        arrayOf(
            "..cccc..",
            ".cc..cc.",
            ".....cc.",
            "....cc..",
            "...cc...",
            "...cc...",
            "........",
            "...cc..."
        ),
        // NOTE
        arrayOf(
            "....nnnn",
            "....n..n",
            "....n..n",
            "....n...",
            ".nn.n...",
            "nnn.n...",
            "nnn.....",
            ".n......"
        ),
        // DROP
        arrayOf(
            "...b....",
            "...b....",
            "..bbb...",
            ".bbWbb..",
            ".bbbbb..",
            ".bbbbb..",
            "..bbb...",
            "........"
        ),
        // STAR
        arrayOf(
            "...yy...",
            "...yy...",
            "yyyyyyyy",
            ".yyWWyy.",
            "..yyyy..",
            ".yyyyyy.",
            ".yy..yy.",
            "yy....yy"
        )
    )

    // Создаёт текстуру атласа. Вызывать на GL-потоке.
    fun createTexture(): Int {
        val w = COLS * CELL
        val h = ROWS * CELL
        val pixels = IntArray(w * h)

        for ((i, sprite) in sprites.withIndex()) {
            val ox = (i % COLS) * CELL
            val oy = (i / COLS) * CELL

            for (y in 0 until CELL) {
                for (x in 0 until CELL) {
                    val ch = sprite[y][x]
                    pixels[(oy + y) * w + ox + x] = palette[ch] ?: 0
                }
            }
        }

        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()

        return ids[0]
    }
}
