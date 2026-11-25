package com.mainsnow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Формы глаз. Клетки глаза (3 ряда × 2 колонки) красятся в атласе текстуры.
internal object BeeFace {
    const val OPEN = 0
    const val CLOSED = 1
    const val HAPPY = 2
    const val WIDE = 3
    const val HALF = 4
    const val SAD = 5
    const val LOOK_IN = 6
    const val LOOK_OUT = 7
    const val LOOK_UP = 8
    const val LOOK_DOWN = 9

    // Веко почти опущено: промежуточная ступень при моргании.
    const val LID = 10

    // Глаза открыты (взгляд по сторонам телом, а не зрачками, форма глаза у всех «открытых» одна).
    // HAPPY («умиление») тоже открытый глаз: с ним пчела моргает.
    fun isOpenish(code: Int) =
        code == OPEN || code == WIDE || code == HAPPY || (code >= LOOK_IN && code <= LOOK_DOWN)
}

// Поза крыльев, лап и усиков поверх клипа. Заполняется в BeeBrain каждый кадр, модель только читает.
internal class BeePose {
    var flap = 1f        // размах крыльев: 0 — не машут, 1 — полный взмах
    var flapPhase = 0f   // фаза взмаха, радианы
    var fold = 0f        // 0 — крылья расправлены, 1 — сложены назад
    var legReach = 0f    // 0 — лапы как в клипе, 1 — вытянуты вниз и держатся за цветок
    var legTuck = 0f     // 0..1 — лапы поджаты к брюшку
    var legStep = 0f     // размах шагов (0..1) при ходьбе по цветку
    var legPhase = 0f    // фаза шага, радианы
    var groom = 0f       // 0..1 — передние лапки потираются у мордочки
    var antPerk = 0f     // −1 усики опущены … +1 подняты
    var antWiggle = 0f   // размах покачивания усиков (0..1)
    var antPhase = 0f    // фаза покачивания, радианы
    var pollen = 0f      // 0..1 — пыльца на задних лапках
}

// Минимальный загрузчик glTF 2.0 под модель «Bee Minecraft»: меши, материалы,
// иерархия узлов и анимация узлов (перемещение, поворот, масштаб). Скелета в модели нет.
// Создавать только на GL-потоке при активном контексте.
internal class GltfBee(private val context: Context, private val dir: String) {

    // brow: 0 — обычная часть, -1 / 1 — левая/правая бровь (двигаются отдельно).
    private class Prim(
        val mesh: GlMesh,
        val useTex: Boolean,
        val wing: Boolean,
        val brow: Int = 0,
        val pivot: FloatArray? = null
    )

    private class Node(
        val name: String,
        val mesh: Int,
        val children: IntArray,
        val t: FloatArray,
        val r: FloatArray,
        val s: FloatArray,
        // Если у узла задана матрица (в этой модели она есть у корня и у группы тела
        // «Bee_0» — масштаб 1.78 × 1.78 × 2.97), она заменяет перемещение/поворот/масштаб.
        val matrix: FloatArray?
    ) {
        val ct = t.copyOf()
        val cr = r.copyOf()
        val cs = s.copyOf()
        val world = FloatArray(16)
    }

    private class Chan(
        val node: Int,
        val path: Int, // 0 перемещение, 1 поворот, 2 масштаб
        val times: FloatArray,
        val values: FloatArray
    )

    private val nodes = ArrayList<Node>()
    private val meshPrims = HashMap<Int, ArrayList<Prim>>()
    private val chans = ArrayList<Chan>()
    private var roots = IntArray(0)
    private var texture = 0

    var duration = 0f
        private set

    private companion object {
        // Свободная область атласа (чёрная, не используется моделью): клетки глаз и блики.
        const val EYE_X_L = 40
        const val EYE_X_R = 44
        const val HL_L = 48
        const val HL_R = 49

        // Румянец: четыре клетки нижнего ряда лица, по две под каждым глазом.
        const val BLUSH_X = 50

        // Веки: тёмный жёлтый → коричневый, как полоски на мордочке. Сверху вниз по рядам глаза.
        // Закрытый глаз — все три ряда под веком, нижний темнее (линия ресниц).
        const val COL_LID0 = 0xFFE4AE3B.toInt()
        const val COL_LID1 = 0xFFD08E48.toInt()
        const val COL_LID2 = 0xFFC5823C.toInt()
        const val COL_LASH = 0xFFA96A30.toInt()

        // Шарниры в системе координат модели: корень крыла (на боку, сверху тела) и основание усика.
        const val HINGE_X = 2.0f
        const val HINGE_Y = 2.228f
        const val HINGE_Z = -1.242f
        const val ANT_X = 0.92f
        const val ANT_Y = 1.9f
        const val ANT_Z = -3.2f

        const val COL_FACE = 0xFFF4CB50.toInt()
        const val COL_DARK0 = 0xFF1E1E28.toInt()
        const val COL_DARK1 = 0xFF302B37.toInt()
        const val COL_CYAN = 0xFF7CC9D1.toInt()
        const val COL_WHITE = 0xFFFFFFFF.toInt()
        const val COL_PINK = 0xFFF7A096.toInt()
    }

    private lateinit var json: JSONObject
    private lateinit var bin: ByteBuffer

    private val local = FloatArray(16)
    private val model = FloatArray(16)
    private val browM = FloatArray(16)
    private val browOut = FloatArray(16)

    // Узлы, которыми управляет поза: крылья, усики, шесть лап (передние, средние, задние пары).
    private val nameIndex = HashMap<String, Int>()
    private var wingLIdx = -1
    private var wingRIdx = -1
    private var antLIdx = -1
    private var antRIdx = -1
    private var legIdx = IntArray(0)
    private var hinge = Array(0) { FloatArray(16) }
    private var hasHinge = BooleanArray(0)
    private var hindMesh = BooleanArray(0)
    private var pollenLoad = 0f
    private val hmTmp = FloatArray(16)

    // Пиксели исходной текстуры (64×64): нужны для перекраски глаз и щёк.
    private var basePixels = IntArray(0)
    private var faceKey = -1

    // Исходные цвета четырёх клеток под глазами (для выключенного румянца).
    private var blushBase = IntArray(4)

    // Положение бровей: tilt > 0 — внутренние концы вверх (грусть), < 0 — вниз (сосредоточенность).
    var browTilt = 0f
    var browLift = 0f

    // Во сне брови убираются: лицо чистое, только метки закрытых глаз.
    var browsHidden = false

    init {
        json = JSONObject(
            String(context.assets.open("$dir/scene.gltf").readBytes(), Charsets.UTF_8)
        )
        bin = ByteBuffer.wrap(context.assets.open("$dir/scene.bin").readBytes())
            .order(ByteOrder.LITTLE_ENDIAN)

        loadTexture()
        loadMeshes()
        loadNodes()
        loadAnimation()
        animate(0f)
    }

    private fun loadTexture() {
        val images = json.optJSONArray("images") ?: return
        if (images.length() == 0) return

        val uri = images.getJSONObject(0).getString("uri")
        val options = BitmapFactory.Options().apply { inScaled = false }
        val bitmap = context.assets.open("$dir/$uri").use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        // NEAREST сохраняет пиксельный вид Minecraft; в файле стоял сглаженный mipmap-фильтр.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

        basePixels = IntArray(64 * 64)
        bitmap.getPixels(basePixels, 0, 64, 0, 0, 64, 64)

        bitmap.recycle()
    }

    private fun readFloats(accessor: Int): FloatArray {
        val a = json.getJSONArray("accessors").getJSONObject(accessor)
        val view = json.getJSONArray("bufferViews").getJSONObject(a.getInt("bufferView"))

        val comps = when (a.getString("type")) {
            "SCALAR" -> 1
            "VEC2" -> 2
            "VEC3" -> 3
            "VEC4" -> 4
            else -> throw IllegalArgumentException("Тип аксессора не поддерживается")
        }

        val count = a.getInt("count")
        val offset = view.optInt("byteOffset", 0) + a.optInt("byteOffset", 0)
        val stride = view.optInt("byteStride", 0).let { if (it == 0) comps * 4 else it }
        val out = FloatArray(count * comps)

        for (i in 0 until count) {
            for (c in 0 until comps) {
                out[i * comps + c] = bin.getFloat(offset + i * stride + c * 4)
            }
        }

        return out
    }

    private fun readIndices(accessor: Int): IntArray {
        val a = json.getJSONArray("accessors").getJSONObject(accessor)
        val view = json.getJSONArray("bufferViews").getJSONObject(a.getInt("bufferView"))
        val count = a.getInt("count")
        val offset = view.optInt("byteOffset", 0) + a.optInt("byteOffset", 0)
        val out = IntArray(count)

        when (a.getInt("componentType")) {
            5125 -> for (i in 0 until count) out[i] = bin.getInt(offset + i * 4)
            5123 -> for (i in 0 until count) out[i] = bin.getShort(offset + i * 2).toInt() and 0xFFFF
            5121 -> for (i in 0 until count) out[i] = bin.get(offset + i).toInt() and 0xFF
            else -> throw IllegalArgumentException("Тип индексов не поддерживается")
        }

        return out
    }

    private fun linearToSrgb(c: Float): Float = c.coerceIn(0f, 1f).pow(1f / 2.2f)

    private fun loadMeshes() {
        val materials = json.optJSONArray("materials") ?: JSONArray()
        val meshes = json.getJSONArray("meshes")

        for (mi in 0 until meshes.length()) {
            val list = ArrayList<Prim>()
            val primitives = meshes.getJSONObject(mi).getJSONArray("primitives")

            for (pi in 0 until primitives.length()) {
                val p = primitives.getJSONObject(pi)
                val attrs = p.getJSONObject("attributes")

                val pos = readFloats(attrs.getInt("POSITION"))
                val nrm = if (attrs.has("NORMAL")) readFloats(attrs.getInt("NORMAL")) else null
                val uv = if (attrs.has("TEXCOORD_0")) readFloats(attrs.getInt("TEXCOORD_0")) else null
                val indices = readIndices(p.getInt("indices"))

                var color = floatArrayOf(1f, 1f, 1f, 1f)
                var textured = false
                var wing = false
                var materialName = ""

                val materialIndex = p.optInt("material", -1)

                if (materialIndex >= 0) {
                    val material = materials.getJSONObject(materialIndex)
                    val pbr = material.optJSONObject("pbrMetallicRoughness")

                    textured = pbr?.has("baseColorTexture") == true

                    val factor = pbr?.optJSONArray("baseColorFactor")
                    if (factor != null && !textured) {
                        color = floatArrayOf(
                            linearToSrgb(factor.getDouble(0).toFloat()),
                            linearToSrgb(factor.getDouble(1).toFloat()),
                            linearToSrgb(factor.getDouble(2).toFloat()),
                            factor.getDouble(3).toFloat()
                        )
                    }

                    // В этой модели крылья — единственный материал без текстуры и цвета
                    // (Material.011). Рисуем их отдельным полупрозрачным проходом.
                    materialName = material.optString("name")
                    wing = materialName == "Material.011"
                }

                when (materialName) {
                    // Клетки глаз (5 тёмных на глаз) и блики: свои пиксели атласа, чтобы красить по одному.
                    "Material.005", "Material.004" ->
                        addEyePrim(list, materialName, pos, nrm, uv, indices, color)

                    // Брови: две отдельные части, чтобы наклонять каждую.
                    "Material.010" ->
                        addBrowPrims(list, pos, nrm, uv, indices, color)

                    // Тело: клетки под глазами получают свои пиксели атласа для румянца.
                    "Material.001" ->
                        addBodyPrim(list, pos, nrm, uv, indices, color)

                    else ->
                        list.add(
                            makePrim(
                                pos, nrm, uv, indices, color, textured, wing, 0, null
                            )
                        )
                }
            }

            meshPrims[mi] = list
        }
    }

    // ---- Сборка примитивов ----

    private fun makePrim(
        pos: FloatArray, nrm: FloatArray?, uv: FloatArray?, indices: IntArray,
        color: FloatArray, textured: Boolean, wing: Boolean, brow: Int, pivot: FloatArray?
    ): Prim {
        val vertexCount = pos.size / 3
        val data = FloatArray(vertexCount * VERTEX_FLOATS)

        for (v in 0 until vertexCount) {
            val o = v * VERTEX_FLOATS

            data[o] = pos[v * 3]
            data[o + 1] = pos[v * 3 + 1]
            data[o + 2] = pos[v * 3 + 2]

            if (nrm != null) {
                data[o + 3] = nrm[v * 3]
                data[o + 4] = nrm[v * 3 + 1]
                data[o + 5] = nrm[v * 3 + 2]
            } else {
                data[o + 4] = 1f
            }

            if (uv != null) {
                data[o + 6] = uv[v * 2]
                data[o + 7] = uv[v * 2 + 1]
            }

            data[o + 8] = color[0]
            data[o + 9] = color[1]
            data[o + 10] = color[2]
            data[o + 11] = color[3]
        }

        val shorts = ShortArray(indices.size) { indices[it].toShort() }

        return Prim(GlMesh.upload(data, data.size, shorts, shorts.size), textured, wing, brow, pivot)
    }

    // Пиксель атласа для клетки глаза по центру треугольника (координаты модели до масштаба Bee_0).
    // Глаз — 3 ряда × 2 колонки, колонка 0 снаружи. Вне глаз возвращает null.
    private fun cellTexel(cx: Float, cy: Float): IntArray? {
        if (cy < -0.82f || cy > 0.26f) return null

        val left = cx < 0f

        if (left) {
            if (cx < -1.27f || cx > -0.50f) return null
        } else {
            if (cx < 0.40f || cx > 1.16f) return null
        }

        val col = if (left) (if (cx < -0.89f) 0 else 1) else (if (cx > 0.78f) 0 else 1)
        val row = if (cy > -0.11f) 0 else if (cy > -0.44f) 1 else 2

        return intArrayOf((if (left) EYE_X_L else EYE_X_R) + col, row)
    }

    // Меш глаз без общих вершин: у каждого треугольника свой пиксель атласа.
    private fun addEyePrim(
        list: ArrayList<Prim>, name: String,
        pos: FloatArray, nrm: FloatArray?, uv: FloatArray?, indices: IntArray,
        color: FloatArray
    ) {
        val tris = indices.size / 3
        val p = FloatArray(tris * 9)
        val n = if (nrm != null) FloatArray(tris * 9) else null
        val t = FloatArray(tris * 6)
        val idx = IntArray(tris * 3) { it }

        for (k in 0 until tris) {
            var cx = 0f
            var cy = 0f

            for (j in 0 until 3) {
                val vi = indices[k * 3 + j]

                for (c in 0 until 3) {
                    p[k * 9 + j * 3 + c] = pos[vi * 3 + c]
                    if (n != null && nrm != null) n[k * 9 + j * 3 + c] = nrm[vi * 3 + c]
                }

                if (uv != null) {
                    t[k * 6 + j * 2] = uv[vi * 2]
                    t[k * 6 + j * 2 + 1] = uv[vi * 2 + 1]
                }

                cx += pos[vi * 3] / 3f
                cy += pos[vi * 3 + 1] / 3f
            }

            val texel = if (name == "Material.004") {
                intArrayOf(if (cx < 0f) HL_L else HL_R, 0)
            } else {
                cellTexel(cx, cy)
            }

            if (texel != null) {
                for (j in 0 until 3) {
                    t[k * 6 + j * 2] = (texel[0] + 0.5f) / 64f
                    t[k * 6 + j * 2 + 1] = (texel[1] + 0.5f) / 64f
                }
            }
        }

        list.add(makePrim(p, n, t, idx, color, true, false, 0, null))
    }

    // Тело без общих вершин у передних клеток нижнего ряда лица (под глазами).
    // Ряд лежит на z ≈ -1.074, клетки идут слева направо шагом 0.34 от x = -1.23.
    private fun addBodyPrim(
        list: ArrayList<Prim>,
        pos: FloatArray, nrm: FloatArray?, uv: FloatArray?, indices: IntArray,
        color: FloatArray
    ) {
        if (basePixels.size >= 64 * 64) {
            blushBase = intArrayOf(
                basePixels[47 * 64 + 16],
                basePixels[47 * 64 + 15],
                basePixels[47 * 64 + 11],
                basePixels[47 * 64 + 10]
            )
        }

        val tris = indices.size / 3
        val p = FloatArray(tris * 9)
        val n = if (nrm != null) FloatArray(tris * 9) else null
        val t = FloatArray(tris * 6)
        val idx = IntArray(tris * 3) { it }

        for (k in 0 until tris) {
            var cx = 0f
            var cy = 0f
            var cz = 0f
            var zMin = Float.MAX_VALUE
            var zMax = -Float.MAX_VALUE

            for (j in 0 until 3) {
                val vi = indices[k * 3 + j]

                for (c in 0 until 3) {
                    p[k * 9 + j * 3 + c] = pos[vi * 3 + c]
                    if (n != null && nrm != null) n[k * 9 + j * 3 + c] = nrm[vi * 3 + c]
                }

                if (uv != null) {
                    t[k * 6 + j * 2] = uv[vi * 2]
                    t[k * 6 + j * 2 + 1] = uv[vi * 2 + 1]
                }

                cx += pos[vi * 3] / 3f
                cy += pos[vi * 3 + 1] / 3f
                cz += pos[vi * 3 + 2] / 3f
                zMin = minOf(zMin, pos[vi * 3 + 2])
                zMax = maxOf(zMax, pos[vi * 3 + 2])
            }

            val front = cz < -1.0f && zMax - zMin < 0.01f && cy < -0.85f && cy > -1.2f

            val cell = if (!front) -1 else when {
                cx > -1.25f && cx < -0.89f -> 0
                cx >= -0.89f && cx < -0.55f -> 1
                cx > 0.44f && cx <= 0.78f -> 2
                cx > 0.78f && cx < 1.14f -> 3
                else -> -1
            }

            if (cell >= 0) {
                for (j in 0 until 3) {
                    t[k * 6 + j * 2] = (BLUSH_X + cell + 0.5f) / 64f
                    t[k * 6 + j * 2 + 1] = 0.5f / 64f
                }
            }
        }

        list.add(makePrim(p, n, t, idx, color, true, false, 0, null))
    }

    private fun addBrowPrims(
        list: ArrayList<Prim>,
        pos: FloatArray, nrm: FloatArray?, uv: FloatArray?, indices: IntArray,
        color: FloatArray
    ) {
        for (side in intArrayOf(-1, 1)) {
            val picked = ArrayList<Int>()

            for (k in 0 until indices.size / 3) {
                var cx = 0f
                for (j in 0 until 3) cx += pos[indices[k * 3 + j] * 3] / 3f

                if ((side == -1 && cx < 0f) || (side == 1 && cx >= 0f)) picked.add(k)
            }

            if (picked.isEmpty()) continue

            val p = FloatArray(picked.size * 9)
            val n = if (nrm != null) FloatArray(picked.size * 9) else null
            val t = FloatArray(picked.size * 6)
            val idx = IntArray(picked.size * 3) { it }
            val pivot = FloatArray(3)

            for ((i, k) in picked.withIndex()) {
                for (j in 0 until 3) {
                    val vi = indices[k * 3 + j]

                    for (c in 0 until 3) {
                        p[i * 9 + j * 3 + c] = pos[vi * 3 + c]
                        pivot[c] += pos[vi * 3 + c] / (picked.size * 3f)
                        if (n != null && nrm != null) n[i * 9 + j * 3 + c] = nrm[vi * 3 + c]
                    }

                    if (uv != null) {
                        t[i * 6 + j * 2] = uv[vi * 2]
                        t[i * 6 + j * 2 + 1] = uv[vi * 2 + 1]
                    }
                }
            }

            list.add(makePrim(p, n, t, idx, color, false, false, side, pivot))
        }
    }

    // ---- Выражение лица ----

    // Сколько верхних рядов глаза закрыто веком и сколько нижних приподнято (щёки при улыбке).
    private fun lidTop(shape: Int) = when (shape) {
        BeeFace.CLOSED -> 3
        BeeFace.LID -> 2
        BeeFace.HALF, BeeFace.SAD -> 1
        else -> 0
    }

    private fun lidLow(shape: Int) = if (shape == BeeFace.HAPPY) 1 else 0

    // Клетка под веком: чёрные пиксели остаются глазом, веко ложится поверх них.
    private fun covered(shape: Int, r: Int) = r < lidTop(shape) || r >= 3 - lidLow(shape)

    private fun lidColor(shape: Int, r: Int): Int = when {
        shape == BeeFace.CLOSED && r == 2 -> COL_LASH
        r == 0 -> COL_LID0
        r == 1 -> COL_LID1
        else -> COL_LID2
    }

    // Цвет клетки глаза (ряд 0 сверху, колонка 0 снаружи). Чёрные клетки — это глаз; веко —
    // отдельный слой тёмно-жёлтого/коричневого цвета, который опускается сверху (моргание,
    // сон) или поднимается снизу («умиление»). Взгляд по сторонам решается поворотом головы.
    private fun cellColor(shape: Int, r: Int, c: Int): Int {
        val dark = if ((r + c) % 2 == 0) COL_DARK0 else COL_DARK1

        return when {
            covered(shape, r) -> lidColor(shape, r)
            shape == BeeFace.SAD && r == 2 && c == 0 -> COL_CYAN
            else -> dark
        }
    }

    private fun paintEye(px: IntArray, stride: Int, xOff: Int, hlOff: Int, shape: Int) {
        for (r in 0 until 3) {
            for (c in 0 until 2) px[r * stride + xOff + c] = cellColor(shape, r, c)
        }

        // Блик живёт в верхней внутренней клетке: под веком он прячется, иначе белый или голубой.
        px[hlOff] = when {
            covered(shape, 0) -> lidColor(shape, 0)
            shape == BeeFace.WIDE || shape == BeeFace.HAPPY -> COL_WHITE
            else -> COL_CYAN
        }
    }

    // Меняет глаза и румянец, подгружая в текстуру только нужные пиксели (область 14 × 3 с x = 40).
    fun setFace(left: Int, right: Int, blush: Boolean) {
        val key = (left shl 12) or (right shl 4) or (if (blush) 1 else 0)

        if (key == faceKey || texture == 0 || basePixels.isEmpty()) return

        faceKey = key

        val w = 14
        val h = 3
        val px = IntArray(w * h)

        for (r in 0 until h) {
            for (c in 0 until w) px[r * w + c] = basePixels[r * 64 + 40 + c]
        }

        paintEye(px, w, EYE_X_L - 40, HL_L - 40, left)
        paintEye(px, w, EYE_X_R - 40, HL_R - 40, right)

        for (i in 0 until 4) {
            px[BLUSH_X - 40 + i] = if (blush) COL_PINK else blushBase[i]
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)

        val bitmap = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 40, 0, bitmap)
        bitmap.recycle()
    }

    private fun floats(array: JSONArray?, fallback: FloatArray): FloatArray {
        if (array == null) return fallback

        return FloatArray(array.length()) { array.getDouble(it).toFloat() }
    }

    private fun loadNodes() {
        val list = json.getJSONArray("nodes")

        for (i in 0 until list.length()) {
            val n = list.getJSONObject(i)
            val kids = n.optJSONArray("children")

            nodes.add(
                Node(
                    n.optString("name"),
                    n.optInt("mesh", -1),
                    IntArray(kids?.length() ?: 0) { kids!!.getInt(it) },
                    floats(n.optJSONArray("translation"), floatArrayOf(0f, 0f, 0f)),
                    floats(n.optJSONArray("rotation"), floatArrayOf(0f, 0f, 0f, 1f)),
                    floats(n.optJSONArray("scale"), floatArrayOf(1f, 1f, 1f)),
                    n.optJSONArray("matrix")?.let { m ->
                        FloatArray(16) { m.getDouble(it).toFloat() }
                    }
                )
            )
        }

        val scene = json.getJSONArray("scenes").getJSONObject(json.optInt("scene", 0))
        val sceneNodes = scene.getJSONArray("nodes")
        roots = IntArray(sceneNodes.length()) { sceneNodes.getInt(it) }

        hasHinge = BooleanArray(nodes.size)
        hinge = Array(nodes.size) { FloatArray(16) }
        hindMesh = BooleanArray(nodes.size)

        for ((i, n) in nodes.withIndex()) nameIndex[n.name] = i

        wingLIdx = nameIndex["RingL_4"] ?: -1
        wingRIdx = nameIndex["RingR_5"] ?: -1
        antLIdx = nameIndex["USL_3"] ?: -1
        antRIdx = nameIndex["USR_2"] ?: -1

        // Пары по длине тела (в модели перед — это −Z): передние, средние, задние.
        legIdx = intArrayOf(
            nameIndex["leg.004_1"] ?: -1, nameIndex["leg.001_7"] ?: -1,
            nameIndex["leg.002_8"] ?: -1, nameIndex["leg.003_9"] ?: -1,
            nameIndex["leg.005_10"] ?: -1, nameIndex["leg.006_11"] ?: -1
        )

        // Меши задних лап: на них показывается пыльца.
        for (k in 4..5) {
            val li = legIdx[k]

            if (li >= 0) for (c in nodes[li].children) hindMesh[c] = true
        }
    }

    private fun loadAnimation() {
        val animations = json.optJSONArray("animations") ?: return
        if (animations.length() == 0) return

        val animation = animations.getJSONObject(0)
        val samplers = animation.getJSONArray("samplers")
        val channels = animation.getJSONArray("channels")

        for (i in 0 until channels.length()) {
            val ch = channels.getJSONObject(i)
            val target = ch.getJSONObject("target")

            val path = when (target.getString("path")) {
                "translation" -> 0
                "rotation" -> 1
                "scale" -> 2
                else -> continue
            }

            val sampler = samplers.getJSONObject(ch.getInt("sampler"))
            val times = readFloats(sampler.getInt("input"))
            val values = readFloats(sampler.getInt("output"))

            if (times.isNotEmpty()) {
                duration = maxOf(duration, times[times.size - 1])
            }

            chans.add(Chan(target.getInt("node"), path, times, values))
        }
    }

    private fun slerp(
        a: FloatArray, ao: Int,
        b: FloatArray, bo: Int,
        t: Float,
        out: FloatArray
    ) {
        var bx = b[bo]
        var by = b[bo + 1]
        var bz = b[bo + 2]
        var bw = b[bo + 3]

        var dot = a[ao] * bx + a[ao + 1] * by + a[ao + 2] * bz + a[ao + 3] * bw

        if (dot < 0f) {
            bx = -bx; by = -by; bz = -bz; bw = -bw
            dot = -dot
        }

        val w1: Float
        val w2: Float

        if (dot > 0.9995f) {
            w1 = 1f - t
            w2 = t
        } else {
            val theta = acos(dot)
            val s = sin(theta)
            w1 = sin((1f - t) * theta) / s
            w2 = sin(t * theta) / s
        }

        val x = a[ao] * w1 + bx * w2
        val y = a[ao + 1] * w1 + by * w2
        val z = a[ao + 2] * w1 + bz * w2
        val w = a[ao + 3] * w1 + bw * w2
        val len = sqrt(x * x + y * y + z * z + w * w).coerceAtLeast(1e-6f)

        out[0] = x / len
        out[1] = y / len
        out[2] = z / len
        out[3] = w / len
    }

    private fun sample(c: Chan, time: Float) {
        val ts = c.times
        val n = ts.size
        val comps = if (c.path == 1) 4 else 3

        var i = 0
        while (i < n - 1 && time >= ts[i + 1]) i++

        val j = min(i + 1, n - 1)
        val span = ts[j] - ts[i]
        val k = if (span > 0f) ((time - ts[i]) / span).coerceIn(0f, 1f) else 0f

        val node = nodes[c.node]
        val dst = when (c.path) {
            0 -> node.ct
            1 -> node.cr
            else -> node.cs
        }

        if (c.path == 1) {
            slerp(c.values, i * 4, c.values, j * 4, k, dst)
        } else {
            for (x in 0 until comps) {
                val a = c.values[i * comps + x]
                val b = c.values[j * comps + x]
                dst[x] = a + (b - a) * k
            }
        }
    }

    // Выставляет позу на момент time (секунды, зацикливается по длине клипа). Если задана pose,
    // поверх клипа накладываются взмах и складывание крыльев, лапы, усики и пыльца.
    // wingPhase сдвигает фазу взмаха: второй проход крыльев даёт размытие от быстрых взмахов.
    fun animate(time: Float, pose: BeePose? = null, wingPhase: Float = 0f) {
        for (n in nodes) {
            n.t.copyInto(n.ct)
            n.r.copyInto(n.cr)
            n.s.copyInto(n.cs)
        }

        val tt = if (duration > 0f) time % duration else 0f

        for (c in chans) sample(c, tt)

        java.util.Arrays.fill(hasHinge, false)

        if (pose != null) applyPose(pose, wingPhase) else pollenLoad = 0f

        for (root in roots) updateWorld(root, null)
    }

    private fun applyPose(p: BeePose, wingPhase: Float) {
        pollenLoad = p.pollen

        // Лапы: ось вращения X, в клипе угол 129–169°. Меньше угол — лапа тянется вниз и вперёд,
        // больше — прижата к брюшку.
        for (k in 0 until legIdx.size) {
            val i = legIdx[k]

            if (i < 0) continue

            val n = nodes[i]
            var qx = n.cr[0]
            var qw = n.cr[3]

            if (qw < 0f) {
                qx = -qx
                qw = -qw
            }

            var a = Math.toDegrees(2.0 * atan2((-qx).toDouble(), qw.toDouble())).toFloat()

            val pair = k / 2
            val left = k % 2 == 1

            a += (172f - a) * p.legTuck.coerceIn(0f, 1f)

            val reachAngle = when (pair) {
                0 -> 66f
                1 -> 98f
                else -> 112f
            }

            a += (reachAngle - a) * p.legReach.coerceIn(0f, 1f)

            if (pair == 0 && p.groom > 0.01f) {
                val g = 40f + 16f * sin(p.legPhase * 1.6f + (if (left) 1.2f else -1.2f))

                a += (g - a) * p.groom.coerceIn(0f, 1f)
            }

            if (p.legStep > 0.01f) {
                val off = pair * 2.1f + (if (left) PI.toFloat() else 0f)

                a += p.legStep * 14f * sin(p.legPhase + off)
            }

            val h = Math.toRadians(a.toDouble() / 2.0).toFloat()

            n.cr[0] = -sin(h)
            n.cr[1] = 0f
            n.cr[2] = 0f
            n.cr[3] = cos(h)

            // Пыльцевые корзинки: задние лапки раздуваются.
            if (pair == 2) {
                val sc = 1f + 1.0f * p.pollen

                n.cs[0] = sc
                n.cs[2] = sc
            }
        }

        // Крылья: поворот вокруг продольной оси тела (взмах) и вокруг вертикальной (отведение назад).
        val amp = p.flap.coerceIn(0f, 1.2f)
        val fold = p.fold.coerceIn(0f, 1f)
        val sw = sin(p.flapPhase + wingPhase)
        val flapDeg = (1f - fold) * amp * (14f + 42f * sw) + fold * 24f
        val sweepDeg = fold * 66f + (1f - fold) * amp * 5f * sw

        if (wingLIdx >= 0) {
            val m = hinge[wingLIdx]

            Matrix.setIdentityM(m, 0)
            Matrix.translateM(m, 0, -HINGE_X, HINGE_Y, HINGE_Z)
            Matrix.rotateM(m, 0, -flapDeg, 0f, 0f, 1f)
            Matrix.rotateM(m, 0, sweepDeg, 0f, 1f, 0f)
            Matrix.translateM(m, 0, HINGE_X, -HINGE_Y, -HINGE_Z)
            hasHinge[wingLIdx] = true
        }

        if (wingRIdx >= 0) {
            val m = hinge[wingRIdx]

            Matrix.setIdentityM(m, 0)
            Matrix.translateM(m, 0, HINGE_X, HINGE_Y, HINGE_Z)
            Matrix.rotateM(m, 0, flapDeg, 0f, 0f, 1f)
            Matrix.rotateM(m, 0, -sweepDeg, 0f, 1f, 0f)
            Matrix.translateM(m, 0, -HINGE_X, -HINGE_Y, -HINGE_Z)
            hasHinge[wingRIdx] = true
        }

        // Усики: наклон вверх/вниз у основания и покачивание в стороны.
        val perk = p.antPerk.coerceIn(-1f, 1f) * 30f
        val wig = p.antWiggle.coerceIn(0f, 1f)

        if (antRIdx >= 0) {
            val m = hinge[antRIdx]

            Matrix.setIdentityM(m, 0)
            Matrix.translateM(m, 0, ANT_X, ANT_Y, ANT_Z)
            Matrix.rotateM(m, 0, perk + wig * 10f * sin(p.antPhase), 1f, 0f, 0f)
            Matrix.rotateM(m, 0, wig * 16f * sin(p.antPhase * 0.8f + 1f), 0f, 1f, 0f)
            Matrix.translateM(m, 0, -ANT_X, -ANT_Y, -ANT_Z)
            hasHinge[antRIdx] = true
        }

        if (antLIdx >= 0) {
            val m = hinge[antLIdx]

            Matrix.setIdentityM(m, 0)
            Matrix.translateM(m, 0, -ANT_X, ANT_Y, ANT_Z)
            Matrix.rotateM(m, 0, perk + wig * 10f * sin(p.antPhase + 1.6f), 1f, 0f, 0f)
            Matrix.rotateM(m, 0, -wig * 16f * sin(p.antPhase * 0.8f + 2.4f), 0f, 1f, 0f)
            Matrix.translateM(m, 0, ANT_X, -ANT_Y, -ANT_Z)
            hasHinge[antLIdx] = true
        }
    }

    private fun updateWorld(index: Int, parent: FloatArray?) {
        val n = nodes[index]
        val fixed = n.matrix

        if (fixed != null) {
            fixed.copyInto(local)
        } else {
            trs(n.ct, n.cr, n.cs, local)
        }

        if (parent == null) {
            local.copyInto(n.world)
        } else {
            Matrix.multiplyMM(n.world, 0, parent, 0, local, 0)
        }

        // Поза крыла или усика: поворот вокруг шарнира в системе координат модели.
        if (hasHinge[index]) {
            Matrix.multiplyMM(hmTmp, 0, hinge[index], 0, n.world, 0)
            hmTmp.copyInto(n.world)
        }

        for (child in n.children) updateWorld(child, n.world)
    }

    private fun trs(t: FloatArray, r: FloatArray, s: FloatArray, m: FloatArray) {
        val x = r[0]; val y = r[1]; val z = r[2]; val w = r[3]
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z

        m[0] = (1f - 2f * (yy + zz)) * s[0]
        m[1] = (2f * (xy + wz)) * s[0]
        m[2] = (2f * (xz - wy)) * s[0]
        m[3] = 0f

        m[4] = (2f * (xy - wz)) * s[1]
        m[5] = (1f - 2f * (xx + zz)) * s[1]
        m[6] = (2f * (yz + wx)) * s[1]
        m[7] = 0f

        m[8] = (2f * (xz + wy)) * s[2]
        m[9] = (2f * (yz - wx)) * s[2]
        m[10] = (1f - 2f * (xx + yy)) * s[2]
        m[11] = 0f

        m[12] = t[0]
        m[13] = t[1]
        m[14] = t[2]
        m[15] = 1f
    }

    // Мировая матрица узла по имени (после animate). Нужна для накладок на лицо.
    fun worldOf(name: String): FloatArray? = nodes.firstOrNull { it.name == name }?.world

    // pass 0 — непрозрачные части, pass 1 — крылья (полупрозрачные, прозрачность wingAlpha).
    fun draw(program: GlProgram, base: FloatArray, pass: Int, wingAlpha: Float = 0.55f) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(program.u("uTex"), 0)

        for ((ni, n) in nodes.withIndex()) {
            if (n.mesh < 0) continue

            val prims = meshPrims[n.mesh] ?: continue

            Matrix.multiplyMM(model, 0, base, 0, n.world, 0)
            GLES20.glUniformMatrix4fv(program.u("uModel"), 1, false, model, 0)

            for (p in prims) {
                if (p.wing != (pass == 1)) continue

                GLES20.glUniform1f(program.u("uUseTex"), if (p.useTex) 1f else 0f)

                val pv = p.pivot

                if (p.brow != 0 && pv != null) {
                    if (browsHidden) continue

                    // Бровь: поворот вокруг своего центра и подъём, затем обычная матрица узла.
                    Matrix.setIdentityM(browM, 0)
                    Matrix.translateM(browM, 0, pv[0], pv[1] + browLift, pv[2])

                    val angle = Math.toDegrees(
                        (if (p.brow < 0) browTilt else -browTilt).toDouble()
                    ).toFloat()

                    Matrix.rotateM(browM, 0, angle, 0f, 0f, 1f)
                    Matrix.translateM(browM, 0, -pv[0], -pv[1], -pv[2])
                    Matrix.multiplyMM(browOut, 0, model, 0, browM, 0)
                    GLES20.glUniformMatrix4fv(program.u("uModel"), 1, false, browOut, 0)
                    GLES20.glUniform4f(program.u("uTint"), 1f, 1f, 1f, 1f)

                    p.mesh.draw()

                    GLES20.glUniformMatrix4fv(program.u("uModel"), 1, false, model, 0)
                    continue
                }

                if (p.wing) {
                    GLES20.glUniform4f(program.u("uTint"), 0.86f, 0.95f, 1.0f, wingAlpha)
                } else if (hindMesh[ni] && pollenLoad > 0.1f) {
                    // Задняя лапка с пыльцой: тёплый жёлто-оранжевый шарик.
                    GLES20.glUniform1f(program.u("uUseTex"), 0f)
                    GLES20.glUniform4f(program.u("uTint"), 1.0f, 0.74f, 0.14f, 1f)
                } else {
                    GLES20.glUniform4f(program.u("uTint"), 1f, 1f, 1f, 1f)
                }

                p.mesh.draw()
            }
        }
    }

    fun release() {
        for (list in meshPrims.values) for (p in list) p.mesh.delete()
        meshPrims.clear()

        if (texture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            texture = 0
        }
    }
}
