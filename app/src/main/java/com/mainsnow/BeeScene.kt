package com.mainsnow

import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// Точка посадки пчелы: центр площадки (x, y, z) и её наклон вокруг оси X.
// Нормаль площадки (0, cos, sin): для обычных цветов это строго вверх.
internal class Flower(
    val kind: Int,
    val x: Float,
    val y: Float,
    val z: Float,
    val tilt: Float
) {
    val nx = 0f
    val ny = cos(tilt)
    val nz = sin(tilt)
}

internal object BeeScene {

    const val K_SUNFLOWER = 0
    const val K_DANDELION = 1
    const val K_POPPY = 2
    const val K_CORNFLOWER = 3
    const val K_BLUET = 4
    const val K_TULIP = 5
    const val K_ALLIUM = 6
    const val K_DAISY = 7

    // Подсолнух: единственный цветок, на котором пчела спит.
    const val SLEEPER = 0

    private const val SUN_TILT = 0.6632f // 38°
    private const val SUN_DISC = 0.155f  // высота верхней грани тарелки над её началом

    val flowers: List<Flower> = listOf(
        Flower(K_SUNFLOWER, 0.0f, 2.55f, 0.2f, SUN_TILT),
        Flower(K_DANDELION, -1.2f, 1.0f, 1.9f, 0f),
        Flower(K_POPPY, 1.1f, 1.15f, 2.2f, 0f),
        Flower(K_CORNFLOWER, -0.7f, 0.95f, -0.9f, 0f),
        Flower(K_BLUET, 1.0f, 0.8f, -0.7f, 0f),
        Flower(K_TULIP, -1.6f, 1.15f, -2.0f, 0f),
        Flower(K_ALLIUM, 1.6f, 1.7f, -2.3f, 0f),
        Flower(K_DAISY, 0.35f, 0.9f, 3.0f, 0f)
    )

    // Кусты: x, z, размер. Три из них «светлячковые»: в листве светятся точки вечером и ночью.
    private val bushes = arrayOf(
        floatArrayOf(-2.3f, -4.0f, 0.95f, 1f),
        floatArrayOf(2.2f, -4.6f, 1.05f, 1f),
        floatArrayOf(-1.2f, -6.2f, 0.9f, 0f),
        floatArrayOf(1.0f, -7.0f, 1.0f, 1f),
        floatArrayOf(-2.6f, -0.6f, 0.8f, 0f),
        floatArrayOf(2.5f, 0.2f, 0.85f, 0f)
    )

    // Светящиеся точки светлячковых кустов (x, y, z), заполняется в buildWorld.
    val bushGlow = ArrayList<Float>()

    private val STEM = floatArrayOf(0.20f, 0.48f, 0.14f)
    private val LEAF = floatArrayOf(0.27f, 0.57f, 0.18f)

    fun buildWorld(): GlMesh {
        val mb = MeshBuilder()
        val rnd = Random(42)

        bushGlow.clear()
        buildGround(mb, rnd)

        for (f in flowers) {
            when (f.kind) {
                K_SUNFLOWER -> sunflower(mb, f)
                K_DANDELION -> dandelion(mb, f)
                K_POPPY -> poppy(mb, f)
                K_CORNFLOWER -> cornflower(mb, f)
                K_BLUET -> bluet(mb, f)
                K_TULIP -> tulip(mb, f)
                K_ALLIUM -> allium(mb, f)
                K_DAISY -> daisy(mb, f)
            }
        }

        for (b in bushes) bush(mb, rnd, b[0], b[1], b[2], b[3] > 0.5f)

        decorate(mb, rnd)

        return mb.build()
    }

    // Небесные тела — плоские квадраты, как в Minecraft.
    fun buildSun(): GlMesh {
        val mb = MeshBuilder()
        mb.box(0f, 0f, 0f, 3.2f, 3.2f, 0.2f, 1.0f, 0.97f, 0.72f)
        mb.box(0f, 0f, 0.25f, 2.1f, 2.1f, 0.1f, 1.0f, 1.0f, 0.92f)
        return mb.build()
    }

    fun buildMoon(): GlMesh {
        val mb = MeshBuilder()
        mb.box(0f, 0f, 0f, 2.6f, 2.6f, 0.2f, 0.88f, 0.90f, 0.92f)
        mb.box(0f, 0f, 0.25f, 1.7f, 1.7f, 0.1f, 0.96f, 0.97f, 0.98f)
        mb.box(-0.9f, 0.7f, 0.4f, 0.45f, 0.45f, 0.05f, 0.75f, 0.78f, 0.82f)
        mb.box(0.8f, -0.5f, 0.4f, 0.6f, 0.5f, 0.05f, 0.78f, 0.80f, 0.84f)
        mb.box(0.2f, 1.0f, 0.4f, 0.3f, 0.3f, 0.05f, 0.80f, 0.82f, 0.86f)
        return mb.build()
    }

    private fun buildGround(mb: MeshBuilder, rnd: Random) {
        // Ближняя зона: плитки 0.5 — пиксельная «текстура» травы.
        for (gx in -7 until 7) {
            for (gz in -12 until 10) {
                for (sx in 0..1) {
                    for (sz in 0..1) {
                        val x0 = gx + sx * 0.5f
                        val z0 = gz + sz * 0.5f
                        grassTile(mb, rnd, x0, z0, x0 + 0.5f, z0 + 0.5f)
                    }
                }
            }
        }

        // Дальняя зона: крупные плитки, дальше туман скрывает край.
        var z = -12
        while (z > -52) {
            var x = -28
            while (x < 28) {
                if (!(x >= -7 && x < 7 && z > -12)) {
                    grassTile(mb, rnd, x.toFloat(), (z - 4).toFloat(), (x + 4).toFloat(), z.toFloat())
                }
                x += 4
            }
            z -= 4
        }
    }

    private fun grassTile(mb: MeshBuilder, rnd: Random, x0: Float, z0: Float, x1: Float, z1: Float) {
        var k = 0.88f + rnd.nextFloat() * 0.22f
        val roll = rnd.nextFloat()

        if (roll < 0.10f) k *= 0.82f else if (roll > 0.93f) k *= 1.12f

        mb.groundTile(x0, z0, x1, z1, 0f, 0.33f * k, 0.58f * k, 0.20f * k)
    }

    // Венчик из count лепестков вокруг вертикальной оси: radius — расстояние до центра
    // лепестка, halfLen — половина длины по радиусу, halfW — половина ширины.
    private fun ring(
        mb: MeshBuilder,
        cx: Float, cy: Float, cz: Float,
        count: Int, radius: Float,
        halfLen: Float, halfW: Float, halfH: Float,
        c: FloatArray, phase: Float = 0f
    ) {
        val saved = mb.save()

        for (i in 0 until count) {
            mb.restore(saved)
            mb.translate(cx, cy, cz)
            mb.rotate(360f * i / count + phase, 0f, 1f, 0f)
            mb.box(radius, 0f, 0f, halfLen, halfH, halfW, c[0], c[1], c[2])
        }

        mb.restore(saved)
    }

    private fun stem(mb: MeshBuilder, x: Float, z: Float, height: Float) {
        mb.box(x, height / 2f, z, 0.05f, height / 2f, 0.05f, STEM[0], STEM[1], STEM[2])
    }

    private fun leaves(mb: MeshBuilder, x: Float, z: Float, stemTop: Float) {
        mb.box(x + 0.13f, stemTop * 0.42f, z, 0.10f, 0.025f, 0.055f, LEAF[0], LEAF[1], LEAF[2])
        mb.box(x + 0.20f, stemTop * 0.42f + 0.05f, z, 0.05f, 0.025f, 0.04f, LEAF[0] * 0.9f, LEAF[1] * 0.9f, LEAF[2])
        mb.box(x - 0.13f, stemTop * 0.62f, z, 0.10f, 0.025f, 0.055f, LEAF[0], LEAF[1], LEAF[2])
        mb.box(x - 0.20f, stemTop * 0.62f + 0.05f, z, 0.05f, 0.025f, 0.04f, LEAF[0] * 0.9f, LEAF[1] * 0.9f, LEAF[2])
        mb.box(x, stemTop * 0.78f, z + 0.1f, 0.05f, 0.025f, 0.09f, LEAF[0], LEAF[1], LEAF[2])
    }

    // Верхняя грань центра лежит на f.y + 0.004 — на ней сидит пчела.
    private fun centerDisc(mb: MeshBuilder, f: Flower, half: Float, c: FloatArray) {
        mb.box(f.x, f.y - 0.03f, f.z, half, 0.034f, half, c[0], c[1], c[2])
    }

    private fun calyx(mb: MeshBuilder, f: Flower, half: Float, depth: Float) {
        mb.box(f.x, f.y - depth, f.z, half, 0.04f, half, 0.22f, 0.5f, 0.15f)
    }

    private fun dandelion(mb: MeshBuilder, f: Flower) {
        val top = f.y
        stem(mb, f.x, f.z, top - 0.12f)
        leaves(mb, f.x, f.z, top - 0.12f)
        calyx(mb, f, 0.1f, 0.11f)

        ring(mb, f.x, top - 0.09f, f.z, 14, 0.15f, 0.10f, 0.03f, 0.028f, floatArrayOf(0.98f, 0.78f, 0.06f))
        ring(mb, f.x, top - 0.06f, f.z, 10, 0.11f, 0.08f, 0.035f, 0.028f, floatArrayOf(1.0f, 0.88f, 0.15f), 18f)
        centerDisc(mb, f, 0.11f, floatArrayOf(1.0f, 0.93f, 0.30f))
    }

    private fun poppy(mb: MeshBuilder, f: Flower) {
        val top = f.y
        stem(mb, f.x, f.z, top - 0.14f)
        leaves(mb, f.x, f.z, top - 0.14f)
        calyx(mb, f, 0.07f, 0.13f)

        ring(mb, f.x, top - 0.10f, f.z, 4, 0.16f, 0.15f, 0.13f, 0.022f, floatArrayOf(0.70f, 0.07f, 0.07f), 45f)
        ring(mb, f.x, top - 0.06f, f.z, 4, 0.15f, 0.14f, 0.12f, 0.022f, floatArrayOf(0.88f, 0.12f, 0.10f))
        centerDisc(mb, f, 0.075f, floatArrayOf(0.08f, 0.04f, 0.05f))
        ring(mb, f.x, top - 0.02f, f.z, 8, 0.1f, 0.025f, 0.015f, 0.02f, floatArrayOf(0.18f, 0.1f, 0.12f))
    }

    private fun cornflower(mb: MeshBuilder, f: Flower) {
        val top = f.y
        stem(mb, f.x, f.z, top - 0.12f)
        leaves(mb, f.x, f.z, top - 0.12f)
        calyx(mb, f, 0.08f, 0.11f)

        ring(mb, f.x, top - 0.09f, f.z, 10, 0.14f, 0.10f, 0.035f, 0.025f, floatArrayOf(0.20f, 0.30f, 0.82f))
        ring(mb, f.x, top - 0.06f, f.z, 8, 0.10f, 0.08f, 0.04f, 0.025f, floatArrayOf(0.34f, 0.46f, 0.95f), 22f)
        centerDisc(mb, f, 0.08f, floatArrayOf(0.13f, 0.16f, 0.50f))
    }

    private fun bluet(mb: MeshBuilder, f: Flower) {
        val top = f.y
        stem(mb, f.x, f.z, top - 0.1f)
        leaves(mb, f.x, f.z, top - 0.1f)
        calyx(mb, f, 0.07f, 0.09f)

        ring(mb, f.x, top - 0.07f, f.z, 4, 0.14f, 0.1f, 0.09f, 0.02f, floatArrayOf(0.95f, 0.95f, 0.98f))
        ring(mb, f.x, top - 0.04f, f.z, 4, 0.12f, 0.08f, 0.08f, 0.02f, floatArrayOf(0.83f, 0.88f, 0.97f), 45f)
        centerDisc(mb, f, 0.08f, floatArrayOf(0.82f, 0.86f, 0.30f))
    }

    // Тюльпан: чашечка из четырёх стенок, верх — кромка на f.y.
    private fun tulip(mb: MeshBuilder, f: Flower) {
        val top = f.y
        stem(mb, f.x, f.z, top - 0.32f)
        leaves(mb, f.x, f.z, top - 0.32f)

        val pink = floatArrayOf(1.0f, 0.55f, 0.72f)
        val deep = floatArrayOf(0.88f, 0.34f, 0.55f)
        val cy = top - 0.16f

        mb.box(f.x + 0.12f, cy, f.z, 0.04f, 0.16f, 0.12f, pink[0], pink[1], pink[2])
        mb.box(f.x - 0.12f, cy, f.z, 0.04f, 0.16f, 0.12f, pink[0], pink[1], pink[2])
        mb.box(f.x, cy, f.z + 0.12f, 0.12f, 0.16f, 0.04f, pink[0], pink[1], pink[2])
        mb.box(f.x, cy, f.z - 0.12f, 0.12f, 0.16f, 0.04f, pink[0], pink[1], pink[2])
        mb.box(f.x, top - 0.28f, f.z, 0.11f, 0.04f, 0.11f, deep[0], deep[1], deep[2])
        mb.box(f.x, top - 0.34f, f.z, 0.06f, 0.06f, 0.06f, 0.22f, 0.5f, 0.15f)
    }

    private fun allium(mb: MeshBuilder, f: Flower) {
        val top = f.y
        stem(mb, f.x, f.z, top - 0.5f)
        leaves(mb, f.x, f.z, top - 0.5f)

        val c1 = floatArrayOf(0.64f, 0.32f, 0.78f)
        val c2 = floatArrayOf(0.76f, 0.46f, 0.90f)
        val c3 = floatArrayOf(0.56f, 0.26f, 0.70f)

        mb.box(f.x, top - 0.25f, f.z, 0.17f, 0.17f, 0.17f, c3[0], c3[1], c3[2])

        // Шар из мелких кубиков по трём ярусам.
        val levels = floatArrayOf(-0.05f, -0.22f, -0.40f)
        val radii = floatArrayOf(0.20f, 0.27f, 0.20f)

        for (l in 0..2) {
            ring(mb, f.x, top + levels[l], f.z, 8, radii[l], 0.07f, 0.07f, 0.07f, if (l == 1) c2 else c1, l * 15f)
        }

        centerDisc(mb, f, 0.13f, c2)
        mb.box(f.x, top - 0.54f, f.z, 0.11f, 0.06f, 0.11f, 0.5f, 0.24f, 0.62f)
    }

    private fun daisy(mb: MeshBuilder, f: Flower) {
        val top = f.y
        stem(mb, f.x, f.z, top - 0.1f)
        leaves(mb, f.x, f.z, top - 0.1f)
        calyx(mb, f, 0.08f, 0.09f)

        ring(mb, f.x, top - 0.07f, f.z, 16, 0.19f, 0.11f, 0.024f, 0.02f, floatArrayOf(0.97f, 0.97f, 0.94f))
        ring(mb, f.x, top - 0.045f, f.z, 12, 0.15f, 0.09f, 0.026f, 0.02f, floatArrayOf(0.92f, 0.93f, 0.9f), 15f)
        centerDisc(mb, f, 0.1f, floatArrayOf(0.96f, 0.76f, 0.10f))
    }

    private fun sunflower(mb: MeshBuilder, f: Flower) {
        // Начало координат головки: площадка минус нормаль * высота диска.
        val hx = f.x - f.nx * SUN_DISC
        val hy = f.y - f.ny * SUN_DISC
        val hz = f.z - f.nz * SUN_DISC

        stem(mb, hx, hz, hy)

        val saved = mb.save()

        mb.translate(hx + 0.30f, 1.0f, hz)
        mb.rotate(-22f, 0f, 0f, 1f)
        mb.box(0f, 0f, 0f, 0.28f, 0.03f, 0.14f, LEAF[0], LEAF[1], LEAF[2])
        mb.restore(saved)

        mb.translate(hx - 0.30f, 1.45f, hz)
        mb.rotate(22f, 0f, 0f, 1f)
        mb.box(0f, 0f, 0f, 0.28f, 0.03f, 0.14f, LEAF[0], LEAF[1], LEAF[2])
        mb.restore(saved)

        mb.translate(hx, hy, hz)
        mb.rotate(Math.toDegrees(SUN_TILT.toDouble()).toFloat(), 1f, 0f, 0f)

        val head = mb.save()

        // Два ряда лепестков с разным оттенком.
        ring(mb, 0f, 0.025f, 0f, 20, 0.66f, 0.22f, 0.095f, 0.025f, floatArrayOf(0.96f, 0.68f, 0.04f), 9f)
        ring(mb, 0f, 0.05f, 0f, 20, 0.56f, 0.20f, 0.10f, 0.025f, floatArrayOf(1.0f, 0.82f, 0.10f))
        mb.restore(head)

        mb.box(0f, 0.075f, 0f, 0.40f, 0.075f, 0.40f, 0.36f, 0.20f, 0.06f)
        mb.box(0f, 0.11f, 0f, 0.27f, 0.044f, 0.27f, 0.24f, 0.12f, 0.04f)

        // Семечки: тёмные и светлые крапинки на диске.
        ring(mb, 0f, 0.158f, 0f, 8, 0.16f, 0.03f, 0.03f, 0.004f, floatArrayOf(0.45f, 0.27f, 0.08f))
        ring(mb, 0f, 0.158f, 0f, 6, 0.28f, 0.03f, 0.03f, 0.004f, floatArrayOf(0.12f, 0.06f, 0.02f), 20f)

        mb.box(0f, -0.05f, 0f, 0.25f, 0.05f, 0.25f, 0.22f, 0.50f, 0.15f)

        mb.restore(saved)
    }

    // Куст из блоков-листьев. Светлячковый куст получает светящиеся точки.
    private fun bush(mb: MeshBuilder, rnd: Random, x: Float, z: Float, size: Float, glow: Boolean) {
        val n = 22

        for (i in 0 until n) {
            val ox = (rnd.nextFloat() - 0.5f) * 1.3f * size
            val oz = (rnd.nextFloat() - 0.5f) * 1.1f * size
            val oy = 0.12f + rnd.nextFloat() * 0.75f * size
            val s = 0.17f + rnd.nextFloat() * 0.14f
            val shade = 0.82f + rnd.nextFloat() * 0.35f

            mb.box(x + ox, oy, z + oz, s, s, s, 0.17f * shade, 0.42f * shade, 0.12f * shade)
        }

        // Тёмное основание.
        mb.box(x, 0.2f, z, 0.45f * size, 0.2f, 0.4f * size, 0.12f, 0.32f, 0.09f)

        if (glow) {
            for (i in 0 until 3) {
                bushGlow.add(x + (rnd.nextFloat() - 0.5f) * 1.0f * size)
                bushGlow.add(0.35f + rnd.nextFloat() * 0.6f * size)
                bushGlow.add(z + 0.45f * size)
            }
        } else if (rnd.nextBoolean()) {
            // Цветущий куст: розовые крапинки.
            for (i in 0 until 5) {
                val ox = (rnd.nextFloat() - 0.5f) * 1.1f * size
                val oy = 0.3f + rnd.nextFloat() * 0.6f * size

                mb.box(x + ox, oy, z + 0.42f * size, 0.06f, 0.06f, 0.04f, 0.92f, 0.45f, 0.65f)
            }
        }
    }

    private fun mushroom(mb: MeshBuilder, x: Float, z: Float, scale: Float) {
        mb.box(x, 0.1f * scale, z, 0.045f * scale, 0.1f * scale, 0.045f * scale, 0.92f, 0.90f, 0.82f)
        mb.box(x, 0.23f * scale, z, 0.13f * scale, 0.04f * scale, 0.13f * scale, 0.78f, 0.12f, 0.10f)
        mb.box(x, 0.285f * scale, z, 0.09f * scale, 0.02f * scale, 0.09f * scale, 0.86f, 0.18f, 0.14f)
        mb.box(x + 0.05f * scale, 0.31f * scale, z, 0.022f * scale, 0.006f * scale, 0.022f * scale, 0.98f, 0.97f, 0.92f)
        mb.box(x - 0.04f * scale, 0.31f * scale, z + 0.04f * scale, 0.02f * scale, 0.006f * scale, 0.02f * scale, 0.98f, 0.97f, 0.92f)
    }

    // Трава, высокая трава, группы мелких цветов, грибы.
    private fun decorate(mb: MeshBuilder, rnd: Random) {
        var placed = 0
        var tries = 0

        // Низкая трава: пучки по 2–3 травинки.
        while (placed < 220 && tries < 1400) {
            tries++

            val x = rnd.nextFloat() * 10f - 5f
            val z = rnd.nextFloat() * 14f - 8f

            if (nearFlower(x, z, 0.5f) || nearBush(x, z, 0.5f)) continue

            val tuft = 0.84f + rnd.nextFloat() * 0.3f
            val blades = 2 + rnd.nextInt(2)

            for (i in 0 until blades) {
                val h = 0.18f + rnd.nextFloat() * 0.30f
                val ox = (rnd.nextFloat() - 0.5f) * 0.24f
                val oz = (rnd.nextFloat() - 0.5f) * 0.24f

                mb.box(x + ox, h / 2f, z + oz, 0.035f, h / 2f, 0.035f, 0.26f * tuft, 0.55f * tuft, 0.17f * tuft)
            }

            placed++
        }

        // Высокая трава: стебли по 0.6–1.0 с тёмным верхом.
        placed = 0
        tries = 0

        while (placed < 70 && tries < 700) {
            tries++

            val x = rnd.nextFloat() * 10f - 5f
            val z = rnd.nextFloat() * 13f - 8f

            if (nearFlower(x, z, 0.6f) || nearBush(x, z, 0.5f)) continue

            val tuft = 0.8f + rnd.nextFloat() * 0.3f

            for (i in 0 until 3) {
                val h = 0.55f + rnd.nextFloat() * 0.45f
                val ox = (rnd.nextFloat() - 0.5f) * 0.3f
                val oz = (rnd.nextFloat() - 0.5f) * 0.3f

                mb.box(x + ox, h / 2f, z + oz, 0.03f, h / 2f, 0.03f, 0.22f * tuft, 0.5f * tuft, 0.14f * tuft)
                mb.box(x + ox, h + 0.02f, z + oz, 0.04f, 0.04f, 0.04f, 0.15f * tuft, 0.38f * tuft, 0.10f * tuft)
            }

            placed++
        }

        val palette = arrayOf(
            floatArrayOf(1.0f, 0.85f, 0.10f),
            floatArrayOf(0.86f, 0.10f, 0.10f),
            floatArrayOf(0.25f, 0.36f, 0.88f),
            floatArrayOf(0.95f, 0.95f, 0.97f),
            floatArrayOf(1.0f, 0.55f, 0.72f),
            floatArrayOf(1.0f, 0.55f, 0.12f),
            floatArrayOf(0.35f, 0.82f, 0.90f),
            floatArrayOf(0.70f, 0.36f, 0.85f)
        )

        // Группы мелких цветов по 4–6 штук.
        placed = 0
        tries = 0

        while (placed < 16 && tries < 400) {
            tries++

            val cx = rnd.nextFloat() * 9f - 4.5f
            val cz = rnd.nextFloat() * 12f - 7f

            if (nearFlower(cx, cz, 0.9f) || nearBush(cx, cz, 0.7f)) continue

            val count = 4 + rnd.nextInt(3)
            val base = palette[rnd.nextInt(palette.size)]

            for (i in 0 until count) {
                val x = cx + (rnd.nextFloat() - 0.5f) * 0.7f
                val z = cz + (rnd.nextFloat() - 0.5f) * 0.7f
                val c = if (rnd.nextFloat() < 0.7f) base else palette[rnd.nextInt(palette.size)]
                val h = 0.28f + rnd.nextFloat() * 0.3f

                mb.box(x, h / 2f, z, 0.022f, h / 2f, 0.022f, STEM[0], STEM[1], STEM[2])
                mb.box(x, h + 0.04f, z, 0.075f, 0.04f, 0.075f, c[0], c[1], c[2])
                mb.box(x, h + 0.085f, z, 0.03f, 0.012f, 0.03f, c[0] * 0.75f, c[1] * 0.75f, c[2] * 0.75f)
            }

            placed++
        }

        // Несколько грибов.
        val spots = arrayOf(
            floatArrayOf(-0.35f, 0.9f, 1.0f),
            floatArrayOf(1.8f, -1.2f, 0.8f),
            floatArrayOf(-1.9f, 2.5f, 1.2f)
        )

        for (m in spots) {
            if (!nearFlower(m[0], m[1], 0.4f)) mushroom(mb, m[0], m[1], m[2])
        }
    }

    private fun nearFlower(x: Float, z: Float, radius: Float): Boolean {
        for (f in flowers) {
            if (abs(f.x - x) < radius && abs(f.z - z) < radius) return true
        }

        return false
    }

    private fun nearBush(x: Float, z: Float, margin: Float): Boolean {
        for (b in bushes) {
            val r = b[2] * 0.7f + margin

            if (abs(b[0] - x) < r && abs(b[1] - z) < r) return true
        }

        return false
    }
}
