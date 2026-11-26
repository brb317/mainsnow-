package com.mainsnow

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

// Частица: пыльца или эмоция (kind — номер спрайта в атласе Emote).
internal class Pollen {
    var x = 0f
    var y = 0f
    var z = 0f
    var vx = 0f
    var vy = 0f
    var vz = 0f
    var life = 0f
    var max = 1f
    var kind = 0
    var size = 0.3f
    var ph = 0f
    var orbit = false
    var grow = 0f
}

// Светлячки. Вечером летают по лугу, ночью кружат вокруг цветка со спящей пчелой.
// Мигают по-настоящему: короткая вспышка и долгая темнота. Блуждают плавно и иногда рывком,
// за каждым тянется короткий шлейф. Вечером пугаются близкой пчелы, улетают вверх, прячутся
// и появляются в другом месте.
internal class FireflySwarm(val n: Int = 14) {

    private val rnd = Random(11)

    private fun spread(min: Float, max: Float) = FloatArray(n) { min + rnd.nextFloat() * (max - min) }

    private val cx = spread(-1.6f, 1.6f)
    private val cy = spread(0.9f, 3.2f)
    private val cz = spread(-2.2f, 2.2f)
    private val ax = spread(0.3f, 0.8f)
    private val ay = spread(0.2f, 0.5f)
    private val az = spread(0.3f, 0.8f)
    private val w1 = spread(0.30f, 0.70f)
    private val w2 = spread(0.30f, 0.70f)
    private val w3 = spread(0.30f, 0.70f)
    private val p1 = spread(0f, 6.28f)
    private val p2 = spread(0f, 6.28f)
    private val p3 = spread(0f, 6.28f)

    // Блуждание: цель смещения меняется каждые 1.2–3.5 с, к ней летят плавно или рывком.
    private val wx = FloatArray(n)
    private val wy = FloatArray(n)
    private val wz = FloatArray(n)
    private val wtx = FloatArray(n)
    private val wty = FloatArray(n)
    private val wtz = FloatArray(n)
    private val wnext = spread(0.2f, 2f)
    private val wspeed = FloatArray(n) { 0.8f }

    // Мигание.
    private val blinkW = spread(0.55f, 1.1f)
    private val blinkP = spread(0f, 6.28f)
    private val blinkAcc = FloatArray(n)

    // Размер светлячка и орбита вокруг пчелы: каждый пятый подлетает совсем близко.
    val size = spread(0.75f, 1.3f)
    private val orbitR = FloatArray(n) { if (it % 5 == 0) 0.45f + rnd.nextFloat() * 0.25f else 0.7f + rnd.nextFloat() * 0.9f }
    private val orbitH = spread(-0.35f, 0.65f)
    private val orbitW = FloatArray(n) { (0.22f + rnd.nextFloat() * 0.3f) * (if (rnd.nextBoolean()) 1f else -1f) }

    private val ox = FloatArray(n)
    private val oy = FloatArray(n)
    private val oz = FloatArray(n)
    private val ovx = FloatArray(n)
    private val ovy = FloatArray(n)
    private val ovz = FloatArray(n)
    private val hide = FloatArray(n)
    private val esc = FloatArray(n)
    private val appear = FloatArray(n) { 1f }
    private val panic = FloatArray(n)

    val px = FloatArray(n)
    val py = FloatArray(n)
    val pz = FloatArray(n)

    // Шлейф: положение чуть позади, догоняет светлячка с запаздыванием.
    val tx = FloatArray(n)
    val ty = FloatArray(n)
    val tz = FloatArray(n)

    val alpha = FloatArray(n)

    var level = 0f
        private set

    private var nightBlend = 0f
    private var gatherT = 0f
    private var started = false

    // Бьётся в такт музыке (0..1).
    var beat = 0f

    // Пугаются ли светлячки близкой пчелы.
    var scary = false

    // Светлячок на месте и не прячется (мигание не учитывается).
    fun visible(i: Int) = hide[i] <= 0f && appear[i] > 0.5f && level > 0.25f

    // Светлячок улетает вверх и прячется на seconds секунд, потом появляется в другом месте.
    fun scatter(i: Int, seconds: Float) {
        if (hide[i] > 0f) return

        hide[i] = seconds
        esc[i] = 0.5f
        ovy[i] += 3.2f
        panic[i] = 1f
    }

    // Светлячки собираются вокруг пчелы, чтобы её подбодрить.
    fun cheer(seconds: Float) {
        gatherT = seconds
    }

    fun update(t: Float, dt: Float, visible: Boolean, night: Boolean, bx: Float, by: Float, bz: Float) {
        level += ((if (visible) 1f else 0f) - level) * min(1f, dt * 0.6f)
        nightBlend += ((if (night) 1f else 0f) - nightBlend) * min(1f, dt * 0.7f)

        if (gatherT > 0f) gatherT -= dt

        val damp = (1f - 1.5f * dt).coerceAtLeast(0f)
        val back = (1f - 0.5f * dt).coerceAtLeast(0f)
        val calm = 1f - nightBlend * 0.5f

        for (i in 0 until n) {
            val vx = cx[i] + ax[i] * sin(w1[i] * t + p1[i])
            val vy = cy[i] + ay[i] * sin(w2[i] * t + p2[i])
            val vz = cz[i] + az[i] * sin(w3[i] * t + p3[i])

            val a = orbitW[i] * t + p1[i]
            val orx = bx + orbitR[i] * cos(a)
            val ory = by + orbitH[i] + 0.15f * sin(w2[i] * t * 2f + p2[i])
            val orz = bz + orbitR[i] * sin(a) * 0.8f

            // Блуждание: плавно подтягиваемся к цели, иногда рывком.
            wnext[i] -= dt

            if (wnext[i] <= 0f) {
                wtx[i] = (rnd.nextFloat() - 0.5f) * 1.1f
                wty[i] = (rnd.nextFloat() - 0.4f) * 0.8f
                wtz[i] = (rnd.nextFloat() - 0.5f) * 1.1f
                wnext[i] = 1.2f + rnd.nextFloat() * 2.3f
                wspeed[i] = if (rnd.nextFloat() < 0.25f) 3.2f else 0.8f
            }

            val wk = min(1f, dt * wspeed[i] * calm)
            wx[i] += (wtx[i] - wx[i]) * wk
            wy[i] += (wty[i] - wy[i]) * wk
            wz[i] += (wtz[i] - wz[i]) * wk

            val wAmount = 1f - nightBlend * 0.6f

            val baseX = vx + (orx - vx) * nightBlend + wx[i] * wAmount
            val baseY = vy + (ory - vy) * nightBlend + wy[i] * wAmount
            val baseZ = vz + (orz - vz) * nightBlend + wz[i] * wAmount

            if (hide[i] > 0f) {
                hide[i] -= dt
                if (esc[i] > 0f) esc[i] -= dt

                if (hide[i] <= 0f) {
                    // Возвращается в новом месте и плавно проявляется.
                    cx[i] = -1.6f + rnd.nextFloat() * 3.2f
                    cy[i] = 0.9f + rnd.nextFloat() * 2.3f
                    cz[i] = -2.2f + rnd.nextFloat() * 4.4f
                    ox[i] = 0f; oy[i] = 0f; oz[i] = 0f
                    ovx[i] = 0f; ovy[i] = 0f; ovz[i] = 0f
                    appear[i] = 0f
                }
            } else {
                if (scary && !night) {
                    val dx = px[i] - bx
                    val dy = py[i] - by
                    val dz = pz[i] - bz
                    val d = sqrt(dx * dx + dy * dy + dz * dz)

                    if (d < 0.8f && d > 0.001f) {
                        val push = (0.8f - d) * 9f * dt

                        ovx[i] += dx / d * push
                        ovy[i] += dy / d * push
                        ovz[i] += dz / d * push
                        panic[i] = 1f

                        if (d < 0.3f && rnd.nextFloat() < dt * 1.2f) {
                            scatter(i, 2f + rnd.nextFloat() * 1.5f)
                        }
                    }
                }

                if (gatherT > 0f) {
                    val gx = bx + ((i % 3) - 1) * 0.35f
                    val gy = by + 0.25f + (i % 2) * 0.2f
                    val gz = bz + 0.3f

                    ovx[i] += (gx - px[i]) * 1.6f * dt
                    ovy[i] += (gy - py[i]) * 1.6f * dt
                    ovz[i] += (gz - pz[i]) * 1.6f * dt
                }
            }

            ox[i] += ovx[i] * dt
            oy[i] += ovy[i] * dt
            oz[i] += ovz[i] * dt

            ovx[i] *= damp
            ovy[i] *= damp
            ovz[i] *= damp

            if (gatherT <= 0f) {
                ox[i] *= back
                oy[i] *= back
                oz[i] *= back
            }

            px[i] = baseX + ox[i]
            py[i] = (baseY + oy[i]).coerceAtLeast(0.25f)
            pz[i] = baseZ + oz[i]

            if (!started) {
                tx[i] = px[i]; ty[i] = py[i]; tz[i] = pz[i]
            } else {
                val tk = min(1f, dt * 6f)
                tx[i] += (px[i] - tx[i]) * tk
                ty[i] += (py[i] - ty[i]) * tk
                tz[i] += (pz[i] - tz[i]) * tk
            }

            appear[i] += (1f - appear[i]) * min(1f, dt * 1.2f)
            panic[i] = (panic[i] - dt * 0.8f).coerceAtLeast(0f)

            // Вспышка и пауза: ночью мигают медленнее.
            blinkAcc[i] += blinkW[i] * (if (night) 0.6f else 1f) * (1f + 2.2f * panic[i]) * dt

            val s = sin(blinkAcc[i] + blinkP[i]).coerceAtLeast(0f)
            val glow = s * s
            val vis = if (hide[i] > 0f) (if (esc[i] > 0f) esc[i] / 0.5f else 0f) else appear[i]

            alpha[i] = (level * (0.12f + 0.88f * glow) * vis * (1f + beat * 0.5f)).coerceAtMost(1f)
        }

        started = true
    }
}

// Пчела. Поведение собрано из состояний с вероятностями, а не из жёстких сценариев.
//  День: заходит с разных сторон (сбоку, из глубины, от камеры, сверху), любуется цветами,
//        пьёт нектар, купается, здоровается с камерой, облетает её, уходит и возвращается.
//  Вечер: сидит на цветке, замечает светлячков, любопытствует, играет в догонялки;
//        может потерять светлячка и искать, притвориться спящей, расстроиться,
//        влететь в камеру и стряхнуться.
//  Ночь: доигрывает, быстро устаёт, ложится на подсолнух и засыпает.
//  Музыка на телефоне (день и вечер): танцует.
// Движения шмелиные: на малой скорости корпус задран носом вверх, перед посадкой тормозит и
// вытягивает лапы, на цветке складывает крылья, ползает, вибрирует и чистит лапками мордочку,
// на взлёте приседает и раскрывает крылья, утром разогревается дрожью. Вход и уход всегда
// происходят за краем кадра по текущей камере.
internal class BeeBrain(
    private val flowers: List<Flower>,
    private val sleeper: Int,
    startMode: BeeTime
) {

    companion object {
        // Размер подобран по скриншоту: 0.14 оказался крупнее нужного на 30%.
        const val SCALE = 0.10f

        // От центра модели до кончиков лап (в модели -2.44 по Y).
        const val FOOT = 2.44f * SCALE

        // Момент клипа, когда крылья опущены: поза для сна.
        const val SLEEP_ANIM_TIME = 0.2f
        const val CLIP = 0.8333f

        private const val PI_F = PI.toFloat()
        private const val TWO_PI_F = (2.0 * PI).toFloat()
        private const val BPM = 118f

        private const val THEN_ARRIVE = 0
        private const val THEN_AWAY = 1
        private const val THEN_PLAY = 2
    }

    private enum class Phase {
        PATH, GOTO, IDLE, DESCEND, FEED, RISE, AWAY, GREET, DANCE,
        PERCH, NOTICE, APPROACH, PLAY, SEARCH, SAD, DIZZY, SHAKE_OFF,
        TIRED, SLEEP_GOTO, SLEEP_LAND, SLEEP, WAKE, GROW
    }

    var mode: BeeTime = startMode
        private set

    // Утром пчела маленькая. Взрослой она становится только сама: при смене утра на день по часам.
    var babyMode = startMode == BeeTime.MORNING
        private set

    // Зелёное свечение и размер во время взросления.
    var glow = 0f
        private set
    var sizeK = 1f
        private set

    private var growPending = false
    private var growWait = 0f
    private var growX = 0f
    private var growY = 0f
    private var growZ = 0f
    private var growSwapped = false
    private var sparkAcc = 0f

    private val dayLike: Boolean get() = mode == BeeTime.DAY || mode == BeeTime.MORNING

    // Поза.
    var x = 0f; private set
    var y = 2.2f; private set
    var z = 1f; private set
    var yaw = PI_F; private set
    var pitch = 0f; private set
    var roll = 0f; private set
    var tilt = 0f; private set
    var breath = 1f; private set
    var bob = 0f; private set
    var animTime = 0f; private set

    // Лицо: формы глаз (BeeFace), румянец, брови.
    var eyeL = 0; private set
    var eyeR = 0; private set
    var blush = false; private set
    var browTilt = 0f; private set
    var browLift = 0f; private set
    var browsHidden = false; private set

    // Тряска камеры после столкновения (0..1).
    var shake = 0f; private set

    // Смещение корпуса при отрисовке: дрожь (разогрев, вибрация на цветке) и дрейф при зависании.
    var jx = 0f; private set
    var jy = 0f; private set
    var jz = 0f; private set

    // Поза крыльев, лап и усиков для модели.
    val pose = BeePose()

    // Камера (задаёт рендерер каждый кадр): нужна, чтобы входить и уходить за краем кадра.
    private var camX = 0f
    private var camY = 2.9f
    private var camZ = 8.2f
    private var fwdX = 0f
    private var fwdY = -0.168f
    private var fwdZ = -0.986f
    private var rgtX = 1f
    private var rgtZ = 0f
    private var upX = 0f
    private var upY = 0.986f
    private var upZ = -0.168f
    private var tanV = 0.5543f
    private var tanH = 0.25f

    private val ep = FloatArray(3)
    private val ep2 = FloatArray(3)

    // Направление, в котором пчела уходит дальше от кадра, если во время ожидания за краем камера сдвинулась.
    private var awayDirX = 0f
    private var awayDirY = 1f
    private var awayDirZ = 0f

    private var lastHs = 0f
    private var wakeWarm = false
    private var pollenLoad = 0f
    private var feedSeed = 0f
    private var groomAt = 0f
    private var groomWin = 0f
    private var blinkTotal = 0.24f
    private var slowBlink = false

    val swarm = FireflySwarm()
    val pollen = ArrayList<Pollen>()
    val emotes = ArrayList<Pollen>()

    private var phase = Phase.GOTO
    private var phaseT = 0f
    private var t = 0f
    private var animT = 0f
    private var energy = 0.8f

    private var vx = 0f
    private var vy = 0f
    private var vz = 0f

    private var rollBase = 0f
    private var rollSpin = 0f
    private var flipT = 0f

    // Выбранный цветок.
    private var cur = 1
    private var last = -1
    private var landYaw = PI_F
    private var bathe = false
    private var feedDur = 4f
    private var idleDur = 1.5f
    private var idleNext = Phase.DESCEND

    // Путь по точкам [x, y, z, скорость].
    private val path = ArrayList<FloatArray>()
    private var pathThen = THEN_ARRIVE
    private var pathExcited = false
    private var bumpArmed = false
    private var awayDur = 4f

    // Сидение на цветке: 0 — ждёт, 1 — притворяется спящей.
    private var perching = false
    private var perchKind = 0
    private var perchT = 0f
    private var pretendClosed = 2f

    // Игра со светлячками.
    private var playDur = 3f
    private var playMode = 0
    private var ffIdx = 0
    private var orbitAng = 0f
    private var dartT = 0f
    private var dartX = 0f
    private var dartY = 0f
    private var dartZ = 0f
    private var searchDur = 3f
    private var searchYaw = 0f
    private var emoteAcc = 0f
    private var noticeInit = false
    private var sadCheered = false
    private var sadHappy = false

    // Приветствие камере.
    private var greetDur = 2f
    private var greetArrived = false

    // Сон и усталость.
    private var sleepYaw = PI_F + 0.3f
    private var nextTwitch = 6f
    private var twitchT = 0f
    private var tireE = 1f
    private var pollenAcc = 0f
    private var peekT = 0f
    private var peekTotal = 1f
    private var nextPeek = 8f

    // Музыка.
    private var musicOn = false
    private var danceX = 0f
    private var danceY = 0f
    private var danceZ = 0f
    private var danceT = 0f
    private var danceDur = 10f
    private var danceCool = 0f
    private var danceArrived = false
    private var beatT = 0f
    private var lastBeat = -1
    private var danceMove = 0
    private var bounce = 0f

    // Глаза.
    private var blinkT = 0f
    private var nextBlink = 3f

    // Желаемое лицо на этот кадр.
    private var fL = 0
    private var fR = 0
    private var fB = false
    private var fTilt = 0f
    private var fLift = 0f
    private var fNoBrows = false

    private val tmp = FloatArray(3)

    // Точка посадки на спящем цветке: вокруг неё ночью кружат светлячки.
    private val sleepX = flowers[sleeper].x + flowers[sleeper].nx * FOOT
    private val sleepY = flowers[sleeper].y + flowers[sleeper].ny * FOOT
    private val sleepZ = flowers[sleeper].z + flowers[sleeper].nz * FOOT

    init {
        when (startMode) {
            BeeTime.DAY -> {
                // Камера на момент создания ещё неизвестна: первый кадр пчела ждёт высоко за кадром,
                // потом влетает в него по настоящей камере.
                x = 0f; y = 60f; z = 0f
                awayDur = 0f
                begin(Phase.AWAY)
            }

            BeeTime.MORNING -> {
                // Малыш, как и днём, влетает в кадр по настоящей камере.
                x = 0f; y = 60f; z = 0f
                awayDur = 0f
                begin(Phase.AWAY)
            }

            BeeTime.EVENING -> {
                // Вечер начинается с того, что пчела сидит на цветке.
                cur = 1
                last = 1
                landYaw = PI_F
                landOf(flowers[cur])
                x = tmp[0]; y = tmp[1]; z = tmp[2]
                tilt = flowers[cur].tilt
                perching = true
                perchKind = 0
                begin(Phase.PERCH)
            }

            BeeTime.NIGHT -> {
                x = sleepX; y = sleepY; z = sleepZ
                tilt = flowers[sleeper].tilt
                yaw = sleepYaw
                begin(Phase.SLEEP)
                nextTwitch = rnd(4f, 9f)
            }
        }
    }

    // ---- Вспомогательное ----

    private fun rnd(a: Float, b: Float) = a + Random.nextFloat() * (b - a)

    private fun landOf(f: Flower) {
        tmp[0] = f.x + f.nx * FOOT
        tmp[1] = f.y + f.ny * FOOT
        tmp[2] = f.z + f.nz * FOOT
    }

    private fun angDiff(target: Float, from: Float): Float {
        var d = (target - from) % TWO_PI_F
        if (d > PI_F) d -= TWO_PI_F
        if (d < -PI_F) d += TWO_PI_F
        return d
    }

    private fun begin(p: Phase) {
        phase = p
        phaseT = 0f
    }

    private fun wp(px: Float, py: Float, pz: Float, speed: Float) = floatArrayOf(px, py, pz, speed)

    private fun face(
        l: Int, r: Int = l, blushOn: Boolean = false,
        tiltBrow: Float = 0f, lift: Float = 0f, noBrows: Boolean = false
    ) {
        fL = l
        fR = r
        fB = blushOn
        fTilt = tiltBrow
        fLift = lift
        fNoBrows = noBrows
    }

    private fun emit(kind: Int, size: Float = 0.3f, orbit: Boolean = false): Pollen? {
        val e = if (emotes.size < 24) Pollen().also { emotes.add(it) } else emotes.firstOrNull { it.life <= 0f } ?: return null

        e.kind = kind
        e.size = size
        e.orbit = orbit
        e.x = x + rnd(-0.18f, 0.18f)
        e.y = y + 0.38f + rnd(0f, 0.1f)
        e.z = z + 0.15f
        e.vx = rnd(-0.05f, 0.05f)
        e.vy = rnd(0.32f, 0.5f)
        e.vz = 0f
        e.ph = rnd(0f, 6.28f)
        e.grow = if (kind == Emote.Z) 0.08f else 0f
        e.max = if (kind == Emote.Z) rnd(2.0f, 2.6f) else rnd(1.4f, 1.9f)
        e.life = e.max

        return e
    }

    private fun emitPollen(px: Float, py: Float, pz: Float) {
        val p = if (pollen.size < 48) Pollen().also { pollen.add(it) } else pollen.firstOrNull { it.life <= 0f } ?: return

        p.kind = 0
        p.x = px + rnd(-0.12f, 0.12f)
        p.y = py + 0.03f
        p.z = pz + rnd(-0.12f, 0.12f)
        p.vx = rnd(-0.08f, 0.08f)
        p.vy = rnd(0.3f, 0.65f)
        p.vz = rnd(-0.08f, 0.08f)
        p.max = rnd(0.8f, 1.4f)
        p.life = p.max
    }

    // Летит к цели с плавным разгоном и торможением. Возвращает расстояние до цели.
    private fun fly(tx: Float, ty: Float, tz: Float, maxSpeed: Float, accel: Float, dt: Float): Float {
        val dx = tx - x
        val dy = ty - y
        val dz = tz - z
        val d = sqrt(dx * dx + dy * dy + dz * dz)

        if (d > 0.0001f) {
            val speed = maxSpeed * (d / 0.9f).coerceIn(0.18f, 1f)
            val k = min(1f, dt * accel * 0.8f)

            vx += (dx / d * speed - vx) * k
            vy += (dy / d * speed - vy) * k
            vz += (dz / d * speed - vz) * k
        }

        x += vx * dt
        y += vy * dt
        z += vz * dt

        return d
    }

    private fun pin(px: Float, py: Float, pz: Float) {
        x = px; y = py; z = pz
        vx = 0f; vy = 0f; vz = 0f
    }

    private fun pickFirefly(): Int {
        for (i in 0 until 8) {
            val c = Random.nextInt(swarm.n)
            if (swarm.visible(c)) return c
        }

        for (i in 0 until swarm.n) if (swarm.visible(i)) return i

        return Random.nextInt(swarm.n)
    }

    private fun canDance(): Boolean {
        if (mode == BeeTime.NIGHT) return false

        return when (phase) {
            Phase.SLEEP, Phase.SLEEP_GOTO, Phase.SLEEP_LAND, Phase.TIRED,
            Phase.DIZZY, Phase.SHAKE_OFF, Phase.WAKE, Phase.DANCE -> false

            else -> true
        }
    }

    // ---- Камера: входы и выходы за кадром ----

    fun setView(ex: FloatArray, tg: FloatArray, fovDeg: Float, aspect: Float) {
        camX = ex[0]
        camY = ex[1]
        camZ = ex[2]

        var fx = tg[0] - ex[0]
        var fy = tg[1] - ex[1]
        var fz = tg[2] - ex[2]
        val fl = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(0.0001f)

        fx /= fl; fy /= fl; fz /= fl

        // Вправо: взгляд × вверх (0, 1, 0); вверх экрана: вправо × взгляд.
        var rx = -fz
        var rz = fx
        val rl = sqrt(rx * rx + rz * rz).coerceAtLeast(0.0001f)

        rx /= rl; rz /= rl

        fwdX = fx; fwdY = fy; fwdZ = fz
        rgtX = rx; rgtZ = rz
        upX = -rz * fy
        upY = rz * fx - rx * fz
        upZ = rx * fy

        tanV = tan(Math.toRadians(fovDeg / 2.0).toFloat())
        tanH = tanV * aspect
    }

    private fun depthOf(px: Float, py: Float, pz: Float) =
        (px - camX) * fwdX + (py - camY) * fwdY + (pz - camZ) * fwdZ

    // Точка на глубине depth вдоль взгляда; sx, sy — положение на экране (±1 — края).
    private fun viewPoint(depth: Float, sx: Float, sy: Float, out: FloatArray) {
        val hx = sx * tanH * depth
        val hy = sy * tanV * depth

        out[0] = camX + fwdX * depth + rgtX * hx + upX * hy
        out[1] = camY + fwdY * depth + upY * hy
        out[2] = camZ + fwdZ * depth + rgtZ * hx + upZ * hy
    }

    // Точка за боковым краем кадра с запасом на размер пчелы. side: −1 слева, 1 справа.
    private fun edgePoint(depth: Float, side: Float, sy: Float, out: FloatArray) {
        val d = depth.coerceAtLeast(1f)

        viewPoint(d, side * (1f + 1.7f / (tanH * d)), sy, out)
        out[1] = max(out[1], 1.3f)
    }

    // Виден ли шар радиуса r вокруг точки камере.
    private fun inView(px: Float, py: Float, pz: Float, r: Float): Boolean {
        val dx = px - camX
        val dy = py - camY
        val dz = pz - camZ
        val d = dx * fwdX + dy * fwdY + dz * fwdZ

        if (d < 0.2f - r) return false

        val dd = d.coerceAtLeast(0.3f)
        val sx = (dx * rgtX + dz * rgtZ) / (dd * tanH)
        val sy = (dx * upX + dy * upY + dz * upZ) / (dd * tanV)

        return abs(sx) < 1f + r / (dd * tanH) && abs(sy) < 1f + r / (dd * tanV)
    }

    // Куда уводить пчелу, ожидающую за кадром: от оси взгляда.
    private fun awayOutward() {
        val dep = depthOf(x, y, z)
        var ox = x - (camX + fwdX * dep)
        var oy = y - (camY + fwdY * dep)
        var oz = z - (camZ + fwdZ * dep)
        val l = sqrt(ox * ox + oy * oy + oz * oz)

        if (l < 0.001f) {
            ox = 0f; oy = 1f; oz = 0f
        } else {
            ox /= l; oy /= l; oz /= l
        }

        awayDirX = ox
        awayDirY = oy
        awayDirZ = oz
    }

    // ---- Переходы ----

    // natural = true только когда период сменился сам, по часам, в автоматическом режиме.
    fun setTime(m: BeeTime, natural: Boolean = false) {
        if (m == mode) return

        val old = mode
        val sleeping = phase == Phase.SLEEP || phase == Phase.SLEEP_LAND || phase == Phase.SLEEP_GOTO
        val grows = m == BeeTime.DAY && old == BeeTime.MORNING && natural && babyMode

        mode = m
        glow = 0f
        sizeK = 1f

        if (!grows) growPending = false

        when (m) {
            BeeTime.NIGHT -> {
                babyMode = false

                if (!sleeping) {
                    // Ночь начинается с конца игры: быстро устаёт, ложится и засыпает.
                    tireE = 1f
                    bumpArmed = false
                    path.clear()
                    begin(Phase.TIRED)
                }
            }

            BeeTime.EVENING -> {
                babyMode = false

                if (sleeping) startWake() else if (phase != Phase.DANCE) startPerch()
            }

            BeeTime.DAY -> {
                if (grows) {
                    // Малыш сначала заканчивает своё дело, потом растёт (startGoto запустит взросление).
                    growPending = true
                    growWait = 0f
                } else {
                    babyMode = false

                    if (sleeping) startWake() else if (phase != Phase.DANCE) startGoto(false)
                }
            }

            BeeTime.MORNING -> {
                // Утром всегда малыш, и он остаётся малышом, пока время не сменится само.
                babyMode = true
                path.clear()
                vx = 0f; vy = 0f; vz = 0f
                x = 0f; y = 60f; z = 0f
                awayDur = 0f
                begin(Phase.AWAY)
            }
        }
    }

    private fun startGrow() {
        growPending = false
        growSwapped = false
        growX = x
        growY = y
        growZ = z
        sparkAcc = 0f
        begin(Phase.GROW)
    }

    // Зелёная искорка, как при взрослении мобов в Minecraft.
    private fun emitSpark() {
        val p = if (pollen.size < 110) Pollen().also { pollen.add(it) } else pollen.firstOrNull { it.life <= 0f } ?: return
        val a = rnd(0f, TWO_PI_F)
        val r = rnd(0.08f, 0.36f)

        p.kind = 2
        p.x = x + cos(a) * r
        p.y = y + rnd(-0.1f, 0.35f)
        p.z = z + sin(a) * r
        p.vx = cos(a) * rnd(0.1f, 0.45f)
        p.vy = rnd(0.25f, 0.8f)
        p.vz = sin(a) * rnd(0.1f, 0.45f)
        p.max = rnd(0.7f, 1.3f)
        p.life = p.max
    }

    // Проснувшийся на цветке шмель сначала разогревается дрожью, на лету просыпается сразу.
    private fun startWake() {
        wakeWarm = phase == Phase.SLEEP || phase == Phase.SLEEP_LAND
        begin(Phase.WAKE)
    }

    fun setMusic(on: Boolean) {
        if (on == musicOn) return

        musicOn = on

        // Музыка танец не включает: пчела может заняться им при очередном выборе действия.
        // Если музыка стихла посреди танца, он плавно заканчивается.
        if (!on && phase == Phase.DANCE) danceDur = min(danceDur, danceT + 1.5f)
    }

    // Иногда, пока играет музыка, пчела танцует. Потом возвращается к своим делам.
    private fun maybeDance(chance: Float): Boolean {
        if (!musicOn || danceCool > 0f || !canDance() || Random.nextFloat() >= chance) return false

        enterDance()

        return true
    }

    // Шмель ходит маршрутом: чаще выбирает ближний цветок, но иногда перелетает через весь луг.
    private fun pickFlower(near: Boolean = true) {
        var best = 0
        var bestW = -1f

        for (i in flowers.indices) {
            if (i == last) continue

            var w = Random.nextFloat()

            if (near) {
                val f = flowers[i]
                val dx = f.x - x
                val dy = f.y - y
                val dz = f.z - z

                w += 1.1f / (0.7f + sqrt(dx * dx + dy * dy + dz * dz))
            }

            if (w > bestW) {
                bestW = w
                best = i
            }
        }

        cur = best
        last = best
        bathe = Random.nextFloat() < 0.35f
        feedDur = if (bathe) rnd(6f, 9f) else rnd(3f, 6.5f)
        landYaw = PI_F + rnd(-0.9f, 0.9f)

        // Как кормится: фаза покачиваний, чистка лапками на длинной кормёжке.
        feedSeed = rnd(0f, 6.28f)
        groomWin = if (feedDur > 4.2f && Random.nextFloat() < 0.6f) rnd(1.2f, 1.8f) else 0f
        groomAt = rnd(1.6f, (feedDur - groomWin - 0.5f).coerceAtLeast(1.7f))
    }

    private fun startGoto(spawn: Boolean) {
        // Пора взрослеть: прошлое действие закончено, пчела в воздухе.
        if (growPending && !spawn && phase != Phase.AWAY) {
            startGrow()
            return
        }

        // Из-за кадра (ожидание или смена времени суток) всегда влетает в кадр заново.
        val entry = spawn || phase == Phase.AWAY

        if (!entry && maybeDance(0.4f)) return

        perching = false
        perchKind = 0
        pickFlower(!entry)

        if (entry) {
            pollenLoad = rnd(0f, 0.12f)
            landOf(flowers[cur])
            buildEntry(tmp[0], tmp[1] + 0.75f, tmp[2])
        } else {
            begin(Phase.GOTO)
        }
    }

    private fun startPerch() {
        perching = true
        perchKind = 0

        val entry = phase == Phase.AWAY

        pickFlower(!entry)

        if (entry) {
            landOf(flowers[cur])
            buildEntry(tmp[0], tmp[1] + 0.75f, tmp[2])
        } else {
            begin(Phase.GOTO)
        }
    }

    private fun startPretend() {
        perching = true
        perchKind = 1
        pretendClosed = rnd(2f, 3.2f)
        pickFlower()
        begin(Phase.GOTO)
    }

    // Вход в кадр. Пчела всегда стартует за краем кадра и влетает в него: сбоку, из глубины,
    // из-за камеры или сверху. Точки считаются по текущей камере, поэтому из пустоты она не возникает.
    private fun buildEntry(hx: Float, hy: Float, hz: Float) {
        path.clear()
        vx = 0f; vy = 0f; vz = 0f

        val s = if (Random.nextBoolean()) 1f else -1f
        val r = Random.nextFloat()

        when {
            r < 0.36f -> {
                edgePoint(rnd(6f, 11f), s, rnd(-0.05f, 0.45f), ep)
                x = ep[0]; y = ep[1]; z = ep[2]
                path.add(wp(hx + s * 1.8f, hy + rnd(-0.1f, 0.5f), hz + rnd(-0.4f, 0.4f), 2.8f))
            }

            r < 0.62f -> {
                // Издалека: из дальнего угла луга по диагонали.
                edgePoint(rnd(15f, 19f), s, rnd(0.1f, 0.4f), ep)
                x = ep[0]; y = ep[1]; z = ep[2]

                viewPoint(rnd(9f, 11f), s * 0.4f, rnd(0.05f, 0.35f), ep2)
                ep2[1] = max(ep2[1], 1.5f)
                path.add(wp(ep2[0], ep2[1], ep2[2], 3.4f))
            }

            r < 0.78f -> {
                // Из-за камеры: влетает сбоку у самого объектива и разворачивается в кадр.
                edgePoint(rnd(2.4f, 3.2f), s, rnd(-0.1f, 0.4f), ep)
                x = ep[0]; y = ep[1]; z = ep[2]

                viewPoint(rnd(5.2f, 6.2f), -s * 0.35f, rnd(0f, 0.3f), ep2)
                ep2[1] = max(ep2[1], 1.5f)
                path.add(wp(ep2[0], ep2[1], ep2[2], 3.4f))
            }

            else -> {
                val d = rnd(7f, 10f)

                viewPoint(d, rnd(-0.5f, 0.5f), 1f + 1.1f / (tanV * d), ep)
                x = ep[0]; y = ep[1]; z = ep[2]
                path.add(wp(hx, hy + 1.6f, hz, 3.2f))
            }
        }

        path.add(wp(hx, hy, hz, 2.4f))
        pathThen = THEN_ARRIVE
        pathExcited = false
        begin(Phase.PATH)
    }

    // Уход за край кадра: вбок, вглубь луга, мимо камеры или вверх. Последняя точка всегда вне кадра.
    private fun buildExit() {
        path.clear()

        val side0 = if (x >= camX) 1f else -1f
        val s = if (Random.nextFloat() < 0.7f) side0 else -side0
        val dep = depthOf(x, y, z).coerceIn(3f, 12f)
        val r = Random.nextFloat()

        when {
            r < 0.36f -> {
                edgePoint(dep + rnd(0f, 2f), s, rnd(0f, 0.5f), ep)
                path.add(wp(x + s * 1.2f, y + 0.5f, z, 3f))
                path.add(wp(ep[0], ep[1], ep[2], 3.3f))
            }

            r < 0.62f -> {
                viewPoint(rnd(13f, 16f), -s * 0.2f, rnd(0.15f, 0.4f), ep)
                ep[1] = max(ep[1], 1.5f)
                path.add(wp(ep[0], ep[1], ep[2], 3.2f))

                edgePoint(rnd(17f, 20f), s, rnd(0.1f, 0.5f), ep)
                path.add(wp(ep[0], ep[1], ep[2], 3.6f))
            }

            r < 0.80f -> {
                viewPoint(rnd(3.2f, 4.2f), s * 0.4f, rnd(0f, 0.3f), ep)
                ep[1] = max(ep[1], 1.5f)
                path.add(wp(ep[0], ep[1], ep[2], 3.2f))

                edgePoint(rnd(1.6f, 2.2f), s, 0.2f, ep)
                path.add(wp(ep[0], ep[1], ep[2], 3.7f))
            }

            else -> {
                val sxCur = (((x - camX) * rgtX + (z - camZ) * rgtZ) / (dep * tanH)).coerceIn(-0.8f, 0.8f)

                viewPoint(dep, sxCur, 1f + 1.2f / (tanV * dep), ep)
                path.add(wp(ep[0], ep[1], ep[2], 3.0f))
            }
        }

        pathThen = THEN_AWAY
        pathExcited = false
        begin(Phase.PATH)
    }

    private fun finishPath() {
        when (pathThen) {
            THEN_ARRIVE -> arriveAtFlower()

            THEN_AWAY -> {
                awayDur = rnd(2.5f, 5.5f)
                awayOutward()
                begin(Phase.AWAY)
            }

            else -> pickPlay()
        }
    }

    private fun arriveAtFlower() {
        if (babyMode) {
            // Малыш нектар не собирает: зависает у цветка, разглядывает его и летит дальше.
            idleDur = rnd(2.4f, 4.6f)
            idleNext = Phase.GOTO
            begin(Phase.IDLE)

            if (Random.nextFloat() < 0.35f) emit(Emote.HEART)

            return
        }

        if (!perching && Random.nextFloat() < 0.45f) {
            // Сначала любуется цветком, потом садится.
            idleDur = rnd(1.1f, 2.2f)
            idleNext = Phase.DESCEND
            begin(Phase.IDLE)
        } else {
            begin(Phase.DESCEND)
        }
    }

    private fun startGreet() {
        greetDur = rnd(1.8f, 3f)
        greetArrived = false
        begin(Phase.GREET)
    }

    private fun enterDance() {
        // Танцует над случайным цветком, не уходя далеко от своих дел.
        landOf(flowers[Random.nextInt(flowers.size)])

        danceX = tmp[0]
        danceY = tmp[1] + 0.9f
        danceZ = tmp[2]
        danceArrived = false
        danceT = 0f
        danceDur = rnd(8f, 16f)
        beatT = 0f
        lastBeat = -1
        danceMove = Random.nextInt(4)
        begin(Phase.DANCE)
    }

    private fun pickPlay() {
        if (maybeDance(0.22f)) return

        ffIdx = pickFirefly()
        playDur = rnd(2.2f, 4.5f)

        val r = Random.nextFloat()
        playMode = if (r < 0.45f) 0 else if (r < 0.75f) 1 else 2
        dartT = 0f

        if (Random.nextFloat() < 0.3f) {
            flipT = 0.9f
            repeat(3) { emit(Emote.HEART) }
        }

        begin(Phase.PLAY)
    }

    private fun startSearch(force: Boolean) {
        if (force) swarm.scatter(ffIdx, rnd(3.5f, 5f))

        searchDur = rnd(2.4f, 3.6f)
        searchYaw = yaw
        emoteAcc = 0f
        begin(Phase.SEARCH)
    }

    private fun startSad() {
        sadCheered = false
        sadHappy = false
        emoteAcc = 0f
        begin(Phase.SAD)
    }

    private fun startZoom() {
        path.clear()

        val bump = Random.nextFloat() < 0.55f

        if (bump) {
            path.add(wp(rnd(-0.5f, 0.5f), 2.5f, 5.5f, 3.6f))
            path.add(wp(rnd(-0.3f, 0.3f), 2.5f, 8.0f, 4.4f))
            bumpArmed = true
        } else {
            val s = if (Random.nextBoolean()) 1f else -1f
            path.add(wp(s * 1.2f, 2.6f, 6.2f, 3.6f))
            path.add(wp(s * 2.8f, 2.8f, 7.2f, 3.8f))
        }

        pathThen = THEN_PLAY
        pathExcited = true
        begin(Phase.PATH)
    }

    private fun triggerBump() {
        bumpArmed = false
        path.clear()
        shake = 1f
        vx *= -0.3f
        vy = 0.3f
        vz = -2.8f

        for (i in 0 until 3) {
            emit(Emote.STAR, 0.26f, true)?.ph = i * 2.09f
        }

        begin(Phase.DIZZY)
    }

    private fun nextAfterPlay() {
        val r = Random.nextFloat()

        when {
            r < 0.46f -> pickPlay()
            r < 0.58f -> startZoom()
            r < 0.76f -> startSearch(true)
            else -> startPretend()
        }
    }

    // ---- Главный цикл ----

    fun update(dt: Float) {
        t += dt
        phaseT += dt

        energy = (energy + (Random.nextFloat() - 0.5f) * dt * 0.4f).coerceIn(0.55f, 1f)

        if (danceCool > 0f) danceCool -= dt

        // Если подходящего момента долго нет, взросление начинается на лету.
        if (growPending && phase != Phase.GROW) {
            growWait += dt

            if (growWait > 28f && (phase == Phase.IDLE || phase == Phase.GOTO || phase == Phase.PATH)) startGrow()
        }

        val speedF = (0.85f + 0.3f * energy) * (if (babyMode) 0.55f else 1f)

        var animSpeed = 2.4f
        var faceYaw = Float.NaN
        var tiltTarget = 0f
        var rollWiggle = 0f
        var pitchBias = 0f
        var frozen = false
        var flying = true
        var turn = 6f

        // Цели позы модели на этот кадр (плавно подтягиваются в конце).
        var flapT = 1f       // размах крыльев
        var foldT = 0f       // крылья сложены
        var reachT = 0f      // лапы тянутся вниз к опоре
        var tuckT = 0f       // лапы поджаты
        var groomT = 0f      // чистит мордочку передними лапками
        var stepT = 0f       // переступает по цветку
        var antPerkT = 0.15f // усики вверх/вниз
        var antWigT = 0.12f  // усики шевелятся
        var flapRate = 15f   // частота взмахов, Гц
        var shiver = 0f      // дрожь корпуса

        slowBlink = false
        breath = 1f
        face(BeeFace.OPEN)
        swarm.scary = false

        when (phase) {
            Phase.PATH -> {
                animSpeed = 2.7f
                flapRate = 16f
                antPerkT = if (pathExcited) 0.5f else -0.1f

                if (pathExcited) face(BeeFace.WIDE, BeeFace.WIDE, false, -0.25f, 0.1f)

                if (path.isEmpty()) {
                    finishPath()
                } else {
                    val w = path[0]
                    val lastWp = path.size == 1
                    val d = fly(w[0], w[1], w[2], w[3] * speedF, 3.2f, dt)

                    if (d < (if (lastWp) 0.12f else 0.45f)) path.removeAt(0)
                    if (path.isEmpty()) finishPath()
                }
            }

            Phase.GOTO -> {
                landOf(flowers[cur])

                val d = fly(tmp[0], tmp[1] + 0.75f, tmp[2], 2.4f * speedF, 3f, dt)

                if (d < 1.2f) {
                    faceYaw = landYaw
                    reachT = ((1.2f - d) / 0.8f).coerceIn(0f, 0.5f)
                }

                if (d < 0.1f) arriveAtFlower()
            }

            Phase.GROW -> {
                // Взросление: малыш зависает, вокруг зелёное свечение и искры, потом на его месте
                // появляется взрослая пчела и расправляется. Целиком занимает около 3.4 секунды.
                animSpeed = 2.4f
                flapRate = 19f
                antPerkT = 0.5f

                fly(growX, growY + sin(phaseT * 2.4f) * 0.04f, growZ, 0.5f, 3f, dt)

                val k = phaseT

                shiver = if (k < 2f) 0.35f * (k / 2f) else 0f

                when {
                    k < 0.8f -> {
                        glow = 0.6f * (k / 0.8f)
                        sizeK = 1f + 0.12f * (k / 0.8f)
                    }

                    k < 2f -> {
                        val u = (k - 0.8f) / 1.2f

                        glow = 0.6f + 0.4f * u
                        sizeK = 1.12f + 0.3f * u * u
                    }

                    k < 3f -> {
                        if (!growSwapped) {
                            growSwapped = true
                            babyMode = false
                            repeat(36) { emitSpark() }
                        }

                        val u = k - 2f

                        glow = 1f - u
                        sizeK = 0.78f + 0.22f * (u * u * (3f - 2f * u))
                    }

                    else -> {
                        glow = 0f
                        sizeK = 1f
                    }
                }

                sparkAcc += dt * (if (k < 2f) 18f + 30f * k / 2f else 16f)

                while (sparkAcc >= 1f) {
                    sparkAcc -= 1f
                    emitSpark()
                }

                if (k >= 3.4f) {
                    glow = 0f
                    sizeK = 1f
                    babyMode = false
                    startGoto(false)
                }
            }

            Phase.IDLE -> {
                landOf(flowers[cur])

                fly(tmp[0], tmp[1] + 0.75f + sin(phaseT * 2.1f) * 0.05f, tmp[2], 1.2f, 4f, dt)
                faceYaw = landYaw + sin(phaseT * 1.5f) * 0.9f

                // Любуется цветком: сначала большие глаза, потом умиление; усики тянутся к цветку.
                if (phaseT < 0.45f) {
                    face(BeeFace.WIDE, BeeFace.WIDE, false, 0f, 0.2f)
                } else {
                    face(BeeFace.HAPPY, BeeFace.HAPPY, true, 0f, 0.16f)
                }

                antPerkT = 0.6f
                antWigT = 0.55f
                flapRate = 18f
                reachT = 0.3f

                emoteAcc += dt * 1.6f

                while (emoteAcc >= 1f) {
                    emoteAcc -= 1f
                    emit(Emote.SPARKLE, 0.26f)
                }

                if (phaseT > idleDur) {
                    if (idleNext == Phase.GOTO) startGoto(false) else begin(idleNext)
                }
            }

            Phase.DESCEND -> {
                val f = flowers[cur]
                landOf(f)

                val d = fly(tmp[0], tmp[1], tmp[2], 1.1f, 4f, dt)

                faceYaw = landYaw
                animSpeed = 1.4f
                if (d < 0.5f) tiltTarget = f.tilt

                // Посадка: лапы тянутся вниз, перед касанием корпус задирается носом вверх.
                reachT = (1f - d / 0.7f).coerceIn(0f, 1f)
                flapT = 0.7f + 0.3f * (d / 0.7f).coerceIn(0f, 1f)
                flapRate = 20f
                antPerkT = 0.45f
                pitchBias = 0.3f * (1f - d / 0.6f).coerceIn(0f, 1f)

                if (d < 0.05f) {
                    if (perching) {
                        perchT = 0f
                        begin(Phase.PERCH)
                    } else {
                        begin(Phase.FEED)
                    }
                }
            }

            Phase.FEED -> {
                val f = flowers[cur]
                landOf(f)

                val sink = if (bathe) 0.07f * min(phaseT, 0.8f) / 0.8f else 0f

                // Ползает по цветку маленькими кругами, ищет, где больше нектара.
                val crawl = if (bathe) 0.012f else 0.035f
                val cx = sin(phaseT * 0.8f + feedSeed) * crawl
                val cz = sin(phaseT * 0.6f + feedSeed * 1.7f + 1f) * crawl

                pin(tmp[0] + cx, tmp[1] - sink - f.nz * cz / f.ny, tmp[2] + cz)

                // Иногда вибрирует крыльями и телом (так шмели вытряхивают пыльцу), иногда чистится.
                val buzz = phaseT > 1.0f && (phaseT * 0.42f + feedSeed) % 1f < 0.2f
                val grooming = !buzz && groomWin > 0f && phaseT > groomAt && phaseT < groomAt + groomWin

                faceYaw = landYaw + sin(phaseT * 1.3f) * 0.25f
                tiltTarget = f.tilt
                rollWiggle = sin(phaseT * 3.1f) * (if (bathe) 0.5f else 0.13f)
                pitchBias = -0.22f + sin(phaseT * 5.3f) * 0.08f
                animSpeed = if (bathe) 0.5f else 0.8f
                flying = false

                flapT = if (buzz) 0.3f else 0f
                flapRate = 42f
                foldT = if (buzz) 0.55f else 0.95f
                reachT = 1f
                stepT = if (grooming || buzz) 0f else 0.55f
                shiver = if (buzz) 0.8f else 0.08f
                groomT = if (grooming) 1f else 0f
                antPerkT = 0.05f
                antWigT = if (grooming) 0.15f else 0.5f

                pollenLoad = (pollenLoad + dt * (if (bathe) 0.12f else 0.07f)).coerceAtMost(1f)

                // Наслаждается нектаром: глаза то закрываются от удовольствия, то умилённо открыты.
                val mood = (phaseT * 0.36f + feedSeed) % 1f

                when {
                    grooming -> face(BeeFace.CLOSED, BeeFace.CLOSED, bathe, 0f, 0.1f)
                    mood < 0.08f -> face(BeeFace.HALF, BeeFace.HALF, bathe, 0f, 0.14f)
                    mood < 0.18f -> face(BeeFace.LID, BeeFace.LID, bathe, 0f, 0.14f)
                    mood < 0.6f -> face(BeeFace.CLOSED, BeeFace.CLOSED, bathe, 0f, 0.14f)
                    mood < 0.7f -> face(BeeFace.LID, BeeFace.LID, bathe, 0f, 0.14f)
                    mood < 0.78f -> face(BeeFace.HALF, BeeFace.HALF, bathe, 0f, 0.14f)
                    else -> face(BeeFace.HAPPY, BeeFace.HAPPY, bathe, 0f, 0.14f)
                }

                pollenAcc += dt * (if (bathe) 22f else 10f)

                while (pollenAcc >= 1f) {
                    pollenAcc -= 1f
                    emitPollen(tmp[0], tmp[1], tmp[2])
                }

                emoteAcc += dt * (if (bathe) 1.5f else 0.8f)

                while (emoteAcc >= 1f) {
                    emoteAcc -= 1f
                    emit(Emote.HEART)
                }

                if (phaseT > feedDur) {
                    if (Random.nextFloat() < 0.25f) {
                        flipT = 0.9f
                        repeat(2) { emit(Emote.HEART) }
                    }

                    begin(Phase.RISE)
                }
            }

            Phase.RISE -> {
                landOf(flowers[cur])

                // Взлёт: шмель приседает на лапах, крылья раскрываются и разгоняются, потом подъём.
                var d = 1f

                if (phaseT < 0.34f) {
                    pin(tmp[0], tmp[1] - 0.015f * sin(phaseT / 0.34f * PI_F), tmp[2])
                    flying = false
                    shiver = 0.5f
                    reachT = 1f
                } else {
                    d = fly(tmp[0], tmp[1] + 0.75f, tmp[2], 1.8f, 4f, dt)
                    reachT = (1f - (phaseT - 0.34f) / 0.5f).coerceIn(0f, 1f)
                    pitchBias = 0.15f
                }

                flapT = 1f
                flapRate = 20f
                antPerkT = 0.3f
                faceYaw = landYaw
                face(BeeFace.HAPPY, BeeFace.HAPPY, false, 0f, 0.1f)

                if (d < 0.1f) {
                    val r = Random.nextFloat()

                    when {
                        r < 0.22f -> buildExit()
                        r < 0.34f -> startGreet()

                        r < 0.58f -> {
                            idleDur = rnd(1f, 2.5f)
                            idleNext = Phase.GOTO
                            begin(Phase.IDLE)
                        }

                        else -> startGoto(false)
                    }
                }
            }

            Phase.AWAY -> {
                pin(x, y, z)
                flying = false

                // Камера могла сдвинуться: если пчела оказалась в кадре, она уплывает дальше от центра.
                val seen = inView(x, y, z, 1.0f)

                if (seen) {
                    x += awayDirX * 3.0f * dt
                    y += awayDirY * 3.0f * dt
                    z += awayDirZ * 3.0f * dt
                }

                // Возвращается, пока её не видно, чтобы не перескакивать на глазах. Если что-то пошло
                // не так и ожидание затянулось, возвращается в любом случае: лучше скачок, чем пустой экран.
                if (phaseT > awayDur && (!seen || phaseT > awayDur + 6f)) startGoto(true)
            }

            Phase.GREET -> {
                // Подлетает к камере, смотрит на нас, машет, потом обходит кругом и улетает.
                antWigT = 0.7f
                antPerkT = 0.5f
                flapRate = 17f
                val gx = 0f
                val gy = 2.3f
                val gz = 5.2f

                if (!greetArrived) {
                    val d = fly(gx + sin(t) * 0.3f, gy, gz, 2.6f, 3.5f, dt)

                    if (d < 0.25f) {
                        greetArrived = true
                        phaseT = 0f
                        emit(Emote.HEART)
                    }
                } else {
                    fly(gx + sin(t * 1.3f) * 0.25f, gy + sin(t * 2f) * 0.08f, gz, 1.5f, 5f, dt)

                    faceYaw = PI_F
                    rollWiggle = sin(phaseT * 9f) * 0.25f
                    face(BeeFace.HAPPY, BeeFace.HAPPY, true, 0f, 0.15f)

                    emoteAcc += dt * 1.7f

                    while (emoteAcc >= 1f) {
                        emoteAcc -= 1f
                        emit(if (Random.nextBoolean()) Emote.HEART else Emote.SPARKLE)
                    }

                    if (phaseT > greetDur) {
                        // Обходит нас кругом и уходит в сторону.
                        path.clear()

                        val s = if (Random.nextBoolean()) 1f else -1f

                        path.add(wp(s * 1.3f, 2.4f, 6.6f, 3.2f))
                        path.add(wp(s * 2.0f, 2.8f, 4.2f, 3.2f))
                        path.add(wp(s * 6.8f, rnd(1.8f, 3.4f), rnd(-1f, 2f), 3.4f))

                        pathThen = THEN_AWAY
                        pathExcited = false
                        begin(Phase.PATH)
                    }
                }
            }

            Phase.DANCE -> {
                turn = 7f
                antWigT = 0.6f
                antPerkT = 0.4f

                if (!danceArrived) {
                    val d = fly(danceX, danceY, danceZ, 2.2f, 4f, dt)

                    animSpeed = 2.6f
                    face(BeeFace.HAPPY, BeeFace.HAPPY, true, 0f, 0.12f)

                    if (d < 0.2f) {
                        danceArrived = true
                        beatT = 0f
                    }
                } else {
                    danceT += dt
                    beatT += dt

                    val bp = beatT * (BPM / 60f)
                    val beatIdx = floor(bp).toInt()

                    if (beatIdx != lastBeat) {
                        lastBeat = beatIdx
                        bounce = 1f

                        if (beatIdx % 8 == 0) danceMove = Random.nextInt(4)

                        if (Random.nextFloat() < 0.12f) {
                            emit(if (Random.nextBoolean()) Emote.NOTE else Emote.HEART)
                        }
                    }

                    bounce *= max(0f, 1f - dt * 3f)

                    // Только плавные движения: сильная фаза занимает четыре доли.
                    val ph = bp * (PI_F / 2f)
                    var ox = 0f
                    var oy = 0f
                    var oz = 0f

                    when (danceMove) {
                        0 -> oy = sin(ph) * 0.10f

                        1 -> {
                            ox = sin(ph * 0.5f) * 0.35f
                            rollWiggle = cos(ph * 0.5f) * 0.2f
                        }

                        2 -> {
                            ox = cos(ph * 0.4f) * 0.3f
                            oz = sin(ph * 0.4f) * 0.3f
                        }

                        else -> {
                            rollWiggle = sin(ph) * 0.22f
                            pitchBias = sin(ph * 2f) * 0.1f
                            oy = sin(ph) * 0.05f
                        }
                    }

                    fly(danceX + ox, danceY + oy, danceZ + oz, 2.4f, 4f, dt)

                    faceYaw = PI_F + sin(ph * 0.5f) * 0.35f
                    breath = 1f + bounce * 0.03f
                    animSpeed = 2.8f
                    face(BeeFace.HAPPY, BeeFace.HAPPY, true, 0f, 0.12f)

                    if (danceT > danceDur) {
                        danceCool = rnd(25f, 45f)

                        if (dayLike) startGoto(false) else pickPlay()
                    }
                }
            }

            Phase.PERCH -> {
                val f = flowers[cur]
                landOf(f)
                pin(tmp[0], tmp[1], tmp[2])

                tiltTarget = f.tilt
                faceYaw = landYaw
                animSpeed = 0.7f
                flying = false
                perchT += dt

                // Сидит на цветке: крылья сложены, лапы держатся, усики чутко шевелятся.
                flapT = 0f
                foldT = 0.95f
                reachT = 1f
                antPerkT = 0.3f
                antWigT = 0.2f
                shiver = 0.04f

                if (perchKind == 1) {
                    // Притворяется спящей: закрытые глаза, потом подглядывает одним глазом.
                    val peekEnd = pretendClosed + 0.9f

                    if (perchT < pretendClosed) {
                        face(BeeFace.CLOSED, BeeFace.CLOSED, false, 0f, 0f, true)
                    } else if (perchT < peekEnd) {
                        face(BeeFace.CLOSED, BeeFace.OPEN, false, 0f, 0f, true)
                    } else {
                        // Бросается за светлячком.
                        emit(Emote.EXCLAIM, 0.3f)
                        perching = false
                        perchKind = 0
                        pickPlay()
                        playMode = 0
                    }
                } else {
                    // Ждёт и оглядывается: глаза, поворот головы и взгляд вверх.
                    val look = ((perchT / 1.5f).toInt()) % 5

                    val shape = when (look) {
                        1 -> BeeFace.LOOK_UP
                        3 -> BeeFace.LOOK_OUT
                        4 -> BeeFace.LOOK_IN
                        else -> BeeFace.OPEN
                    }

                    face(shape, shape, false, 0f, 0f)

                    faceYaw = landYaw + when (look) {
                        3 -> 0.55f
                        4 -> -0.55f
                        else -> 0f
                    }

                    if (look == 1) pitchBias = 0.2f

                    if (mode == BeeTime.EVENING && swarm.level > 0.4f && perchT > 2.5f) {
                        ffIdx = pickFirefly()
                        noticeInit = false
                        begin(Phase.NOTICE)
                    }
                }
            }

            Phase.NOTICE -> {
                // Видит светлячка: подпрыгивает, «!», большие глаза, поворачивается к нему.
                val f = flowers[cur]
                landOf(f)

                if (!noticeInit) {
                    noticeInit = true
                    emit(Emote.EXCLAIM, 0.3f)
                }

                val hop = sin(min(phaseT / 0.45f, 1f) * PI_F) * 0.22f

                pin(tmp[0], tmp[1] + hop, tmp[2])

                tiltTarget = f.tilt
                animSpeed = 2.4f
                flying = false
                face(BeeFace.WIDE, BeeFace.WIDE, false, 0f, 0.25f)

                // Заметила светлячка: крылья раскрываются в прыжке, усики торчком.
                flapT = if (phaseT > 0.1f) 1f else 0f
                foldT = if (phaseT > 0.1f) 0f else 0.95f
                reachT = 0.6f
                flapRate = 18f
                antPerkT = 1f
                antWigT = 0.6f

                faceYaw = atan2(-(swarm.px[ffIdx] - x), -(swarm.pz[ffIdx] - z))

                if (phaseT > 1.1f) {
                    perching = false
                    begin(Phase.APPROACH)
                }
            }

            Phase.APPROACH -> {
                swarm.scary = true

                val d = fly(
                    swarm.px[ffIdx], swarm.py[ffIdx] + 0.2f, swarm.pz[ffIdx] + 0.3f,
                    1.5f * speedF, 3f, dt
                )

                face(if (phaseT < 1f) BeeFace.WIDE else BeeFace.OPEN, BeeFace.WIDE, false, -0.15f, 0.1f)

                emoteAcc += dt * 1.5f

                while (emoteAcc >= 1f) {
                    emoteAcc -= 1f
                    emit(Emote.SPARKLE, 0.24f)
                }

                if (d < 0.8f || phaseT > 3f) pickPlay()
            }

            Phase.PLAY -> {
                swarm.scary = true
                turn = 14f

                if (!swarm.visible(ffIdx)) {
                    // Светлячок спрятался: ищет его или переключается на другого.
                    if (Random.nextFloat() < 0.6f) startSearch(false) else pickPlay()
                } else {
                    var tx = swarm.px[ffIdx]
                    var ty = swarm.py[ffIdx]
                    var tz = swarm.pz[ffIdx]

                    val dd = sqrt((tx - x) * (tx - x) + (ty - y) * (ty - y) + (tz - z) * (tz - z))

                    when (playMode) {
                        0 -> {
                            tx += sin(t * 2.3f) * 0.35f
                            ty += 0.15f + cos(t * 3.1f) * 0.2f
                            tz += cos(t * 2.0f) * 0.35f

                            face(if (dd < 1f) BeeFace.WIDE else BeeFace.OPEN, BeeFace.WIDE, false, -0.35f, 0f)
                        }

                        1 -> {
                            orbitAng += dt * 3.4f
                            tx += cos(orbitAng) * 0.5f
                            ty += sin(orbitAng * 1.3f) * 0.25f
                            tz += sin(orbitAng) * 0.5f

                            face(BeeFace.HAPPY, BeeFace.HAPPY, true, 0f, 0.1f)

                            emoteAcc += dt * 1.1f

                            while (emoteAcc >= 1f) {
                                emoteAcc -= 1f
                                emit(Emote.HEART)
                            }
                        }

                        else -> {
                            dartT -= dt

                            if (dartT <= 0f) {
                                dartX = rnd(-0.7f, 0.7f)
                                dartY = rnd(-0.4f, 0.5f)
                                dartZ = rnd(-0.7f, 0.7f)
                                dartT = rnd(0.35f, 0.7f)
                            }

                            tx += dartX
                            ty += dartY
                            tz += dartZ

                            face(BeeFace.OPEN, BeeFace.OPEN, false, -0.15f, 0.05f)
                        }
                    }

                    fly(
                        tx.coerceIn(-1.9f, 1.9f),
                        ty.coerceIn(0.9f, 3.4f),
                        tz.coerceIn(-2.6f, 2.6f),
                        3.5f * speedF, 5.5f, dt
                    )

                    animSpeed = 2.8f

                    if (phaseT > playDur) nextAfterPlay()
                }
            }

            Phase.SEARCH -> {
                // Потеряла светлячка: оглядывается, смотрит вверх, «?».
                swarm.scary = false
                fly(x, y + sin(phaseT * 2f) * 0.04f, z, 0.5f, 3f, dt)

                faceYaw = searchYaw + sin(phaseT * 1.6f) * 1.4f
                pitchBias = 0.25f
                animSpeed = 2.2f
                turn = 9f
                antWigT = 0.9f
                antPerkT = 0.45f

                val look = ((phaseT * 1.8f).toInt()) % 3

                face(
                    when (look) {
                        0 -> BeeFace.LOOK_UP
                        1 -> BeeFace.LOOK_OUT
                        else -> BeeFace.LOOK_IN
                    },
                    when (look) {
                        0 -> BeeFace.LOOK_UP
                        1 -> BeeFace.LOOK_IN
                        else -> BeeFace.LOOK_OUT
                    },
                    false, 0.25f, 0.05f
                )

                emoteAcc += dt * 1.1f

                while (emoteAcc >= 1f) {
                    emoteAcc -= 1f
                    emit(Emote.QUESTION, 0.26f)
                }

                // Нашла кого-то из видимых светлячков.
                if (phaseT > 1.6f) {
                    val found = pickFirefly()

                    if (swarm.visible(found) && Random.nextFloat() < dt * 1.4f) {
                        ffIdx = found
                        emit(Emote.EXCLAIM, 0.3f)
                        pickPlay()
                        ffIdx = found
                    }
                }

                if (phase == Phase.SEARCH && phaseT > searchDur) {
                    if (Random.nextFloat() < 0.45f) startSad() else pickPlay()
                }
            }

            Phase.SAD -> {
                // Расстроилась: опустилась, глаза грустные, слёзки. Светлячки приходят подбодрить.
                fly(x, 1.1f, z, 0.6f, 2.5f, dt)

                pitchBias = -0.4f
                animSpeed = 1.2f
                flying = true
                antPerkT = -0.9f
                antWigT = 0.1f
                flapT = 0.7f
                flapRate = 12f

                emoteAcc += dt * 1.0f

                while (emoteAcc >= 1f) {
                    emoteAcc -= 1f
                    emit(Emote.DROP, 0.22f)
                }

                if (!sadCheered && phaseT > 1.9f) {
                    sadCheered = true
                    swarm.cheer(2.6f)
                }

                if (!sadHappy && phaseT > 2.7f) {
                    sadHappy = true
                    repeat(3) { emit(Emote.HEART) }
                }

                if (sadHappy) {
                    face(BeeFace.WIDE, BeeFace.WIDE, true, 0f, 0.2f)
                } else {
                    face(BeeFace.SAD, BeeFace.SAD, false, 0.55f, -0.05f)
                }

                if (phaseT > 3.5f) {
                    pickPlay()
                    playMode = 1
                }
            }

            Phase.DIZZY -> {
                // После столкновения с камерой: кружится голова, вокруг звёздочки.
                fly(x * 0.8f, y, z - 2.2f, 1.5f, 3f, dt)

                rollWiggle = sin(phaseT * 13f) * 0.55f
                pitchBias = sin(phaseT * 9f) * 0.3f
                animSpeed = 3.5f
                antWigT = 1f
                antPerkT = 0.1f
                shiver = 0.3f

                if (phaseT < 0.35f) {
                    face(BeeFace.CLOSED, BeeFace.CLOSED, false, 0.3f, -0.1f)
                } else {
                    face(BeeFace.WIDE, BeeFace.LOOK_OUT, false, 0.35f, 0.1f)
                }

                if (phaseT > 1.8f) {
                    flipT = 0.9f
                    begin(Phase.SHAKE_OFF)
                }
            }

            Phase.SHAKE_OFF -> {
                // Стряхивается и продолжает играть.
                fly(x, y, z, 0.5f, 3f, dt)

                rollWiggle = 0f
                animSpeed = 3.5f
                face(BeeFace.HAPPY, BeeFace.HAPPY, true, 0f, 0.1f)

                if (phaseT > 0.9f) {
                    emit(Emote.HEART)
                    pickPlay()
                }
            }

            Phase.TIRED -> {
                // Ночь: доигрывает и быстро устаёт.
                tireE = (tireE - dt / 7f).coerceAtLeast(0f)

                val i = ffIdx.coerceIn(0, swarm.n - 1)

                fly(
                    swarm.px[i] + sin(t * 1.1f) * 0.3f,
                    swarm.py[i] + 0.15f,
                    swarm.pz[i] + cos(t * 0.9f) * 0.3f,
                    2.6f * (0.25f + 0.75f * tireE), 2.5f, dt
                )

                animSpeed = 0.9f + 1.5f * tireE
                pitchBias = -0.25f * (1f - tireE)
                turn = 3f + 4f * tireE

                flapT = 0.55f + 0.45f * tireE
                flapRate = 11f + 5f * tireE
                antPerkT = -0.7f * (1f - tireE)
                antWigT = 0.05f
                slowBlink = true

                if (tireE > 0.6f) {
                    face(BeeFace.OPEN, BeeFace.OPEN, false, 0f, 0f)
                } else if (tireE > 0.3f) {
                    face(BeeFace.HALF, BeeFace.HALF, false, 0f, -0.08f * (1f - tireE))
                } else {
                    face(BeeFace.LID, BeeFace.LID, false, 0f, -0.1f)
                }

                if (tireE <= 0.12f) begin(Phase.SLEEP_GOTO)
            }

            Phase.SLEEP_GOTO -> {
                val d = fly(sleepX, sleepY + 0.8f, sleepZ, 1.4f, 2.5f, dt)

                if (d < 1.2f) faceYaw = sleepYaw

                animSpeed = 1.4f
                pitchBias = -0.2f
                flapT = 0.6f
                flapRate = 12f
                antPerkT = -0.6f
                slowBlink = true
                reachT = ((1.2f - d) / 1.2f).coerceIn(0f, 0.6f)

                if (d < 0.6f) {
                    face(BeeFace.LID, BeeFace.LID, false, 0f, -0.1f)
                } else {
                    face(BeeFace.HALF, BeeFace.HALF, false, 0f, -0.1f)
                }

                if (d < 0.15f) begin(Phase.SLEEP_LAND)
            }

            Phase.SLEEP_LAND -> {
                val f = flowers[sleeper]
                val d = fly(sleepX, sleepY, sleepZ, 0.7f, 4f, dt)

                faceYaw = sleepYaw
                animSpeed = 1.0f
                if (d < 0.6f) tiltTarget = f.tilt

                reachT = (1f - d / 0.6f).coerceIn(0f, 1f)
                flapT = (d / 0.6f).coerceIn(0.25f, 0.8f)
                flapRate = 12f
                foldT = (1f - d / 0.3f).coerceIn(0f, 1f)
                antPerkT = -0.7f
                slowBlink = true

                // Веки опускаются постепенно: полуприкрыты, почти закрыты, закрыты.
                val sh = if (phaseT < 0.7f) BeeFace.HALF else if (phaseT < 1.3f) BeeFace.LID else BeeFace.CLOSED

                face(sh, sh, false, 0f, 0f, phaseT > 1.0f)

                if (d < 0.04f) {
                    nextTwitch = rnd(4f, 9f)
                    begin(Phase.SLEEP)
                }
            }

            Phase.SLEEP -> {
                pin(sleepX, sleepY, sleepZ)

                tiltTarget = flowers[sleeper].tilt
                faceYaw = sleepYaw
                breath = 1f + 0.012f * sin(t * 1.7f)
                rollWiggle = sin(t * 0.4f) * 0.015f
                pitchBias = 0.02f * sin(t * 1.7f)
                frozen = true
                flying = false

                // Спит: крылья сложены, лапы держат цветок, усики опущены.
                flapT = 0f
                foldT = 1f
                reachT = 0.8f
                antPerkT = -0.75f
                antWigT = if (twitchT > 0f) 0.5f else 0f

                face(BeeFace.CLOSED, BeeFace.CLOSED, false, 0f, 0f, true)

                nextTwitch -= dt

                if (nextTwitch <= 0f) {
                    when (Random.nextInt(3)) {
                        0 -> twitchT = 0.6f
                        1 -> sleepYaw = (sleepYaw + rnd(-0.4f, 0.4f)).coerceIn(PI_F - 0.7f, PI_F + 1.0f)

                        else -> {
                            twitchT = 0.6f
                            sleepYaw = (sleepYaw + rnd(-0.3f, 0.3f)).coerceIn(PI_F - 0.7f, PI_F + 1.0f)
                        }
                    }

                    nextTwitch = rnd(5f, 12f)

                }

                // Иногда приоткрывает глаза, сонно смотрит в стороны и снова закрывает.
                nextPeek -= dt

                if (peekT <= 0f && nextPeek <= 0f) {
                    peekTotal = rnd(1.6f, 2.6f)
                    peekT = peekTotal
                    nextPeek = rnd(9f, 20f)
                }

                if (peekT > 0f) {
                    peekT -= dt

                    val e = peekTotal - peekT

                    // Веки приподнимаются по ступеням, глаза сонно открываются и снова закрываются.
                    when {
                        e < 0.25f || peekT < 0.2f -> face(BeeFace.LID, BeeFace.LID, false, 0f, 0f, true)
                        e < 0.5f || peekT < 0.45f -> face(BeeFace.HALF, BeeFace.HALF, false, 0f, 0f, true)
                        else -> face(BeeFace.OPEN, BeeFace.HALF, false, 0f, 0f, true)
                    }
                }
            }

            Phase.WAKE -> {
                // Просыпается: шмель сперва «разогревается» — дрожит всем телом и жужжит крыльями,
                // пока не прогреются мышцы, потом потягивается, оглядывается и взлетает.
                val warm = wakeWarm && phaseT < 1.7f
                var d = 1f

                if (warm) {
                    val k = (phaseT / 1.7f).coerceIn(0f, 1f)

                    pin(x, y, z)
                    flying = false
                    shiver = 0.25f + 0.75f * k
                    flapT = 0.15f + 0.3f * k
                    flapRate = 34f
                    foldT = 1f - k
                    reachT = 1f
                    antWigT = 0.6f
                    antPerkT = -0.5f + 0.9f * k
                    slowBlink = true
                } else {
                    val since = phaseT - (if (wakeWarm) 1.7f else 0f)

                    d = fly(sleepX, sleepY + 0.8f, sleepZ, 1.4f, 4f, dt)
                    flapRate = 19f
                    pitchBias = -0.35f * sin(min(since / 1.2f, 1f) * PI_F)
                    reachT = if (wakeWarm) (1f - since / 0.5f).coerceIn(0f, 1f) else 0f
                    antWigT = 0.4f
                    antPerkT = 0.4f
                }

                animSpeed = 2.0f

                // Веки открываются постепенно.
                val wk = if (wakeWarm) phaseT / 1.7f else phaseT / 0.9f

                face(
                    when {
                        wk < 0.35f -> BeeFace.CLOSED
                        wk < 0.55f -> BeeFace.LID
                        wk < 0.8f -> BeeFace.HALF
                        else -> BeeFace.OPEN
                    }
                )

                if (d < 0.12f && phaseT > (if (wakeWarm) 2.9f else 1.2f)) {
                    if (dayLike) startGoto(false) else pickPlay()
                }
            }
        }

        // Столкновение с камерой.
        if (bumpArmed && z > 7.35f) triggerBump()

        // Глаза: моргание веками, пока оба глаза открыты. Веко опускается сверху ступенями,
        // держится и поднимается; иногда моргает дважды подряд, уставшая пчела моргает медленно.
        var wantL = fL
        var wantR = fR

        if (BeeFace.isOpenish(wantL) && BeeFace.isOpenish(wantR)) {
            nextBlink -= dt

            if (nextBlink <= 0f) {
                blinkTotal = if (slowBlink) 0.45f else 0.24f
                blinkT = blinkTotal
                nextBlink = if (!slowBlink && Random.nextFloat() < 0.14f) 0.28f else rnd(2.4f, 6f)
            }

            if (blinkT > 0f) {
                blinkT -= dt

                val k = 1f - (blinkT / blinkTotal).coerceIn(0f, 1f)

                val shape = when {
                    k < 0.17f -> BeeFace.HALF
                    k < 0.33f -> BeeFace.LID
                    k < 0.55f -> BeeFace.CLOSED
                    k < 0.72f -> BeeFace.LID
                    k < 0.88f -> BeeFace.HALF
                    else -> -1
                }

                if (shape >= 0) {
                    wantL = shape
                    wantR = shape
                }
            }
        }

        eyeL = wantL
        eyeR = wantR
        blush = fB
        browsHidden = fNoBrows

        val browK = min(1f, dt * 8f)
        browTilt += (fTilt - browTilt) * browK
        browLift += (fLift - browLift) * browK

        // Курс: по заданному направлению или по скорости полёта.
        val hs = sqrt(vx * vx + vz * vz)
        val haveFace = !faceYaw.isNaN()

        val targetYaw = when {
            haveFace -> faceYaw
            hs > 0.25f -> atan2(-vx, -vz)
            else -> yaw
        }

        val dyaw = angDiff(targetYaw, yaw)
        val kYaw = min(1f, dt * (if (haveFace) max(4f, turn * 0.6f) else turn))

        yaw += dyaw * kYaw

        val yawRate = if (dt > 0f) dyaw * kYaw / dt else 0f
        val bank = if (flying) (-yawRate * 0.12f).coerceIn(-0.6f, 0.6f) else 0f

        rollBase += (bank + rollWiggle - rollBase) * min(1f, dt * 6f)

        if (flipT > 0f) {
            rollSpin += dt * TWO_PI_F / 0.9f
            flipT -= dt

            if (flipT <= 0f) rollSpin = 0f
        }

        roll = rollBase + rollSpin

        // Шмель в воздухе: на малой скорости корпус задран носом вверх (хвост вниз), на скорости
        // выравнивается; при разгоне клюёт носом, при торможении задирает нос.
        val hoverK = if (flying) 1f - (hs / 1.5f).coerceIn(0f, 1f) else 0f
        val fwdAcc = if (dt > 0f) (hs - lastHs) / dt else 0f

        lastHs = hs

        val pitchTarget = (vy * 0.16f).coerceIn(-0.4f, 0.4f) + 0.34f * hoverK -
            min(hs, 3.2f) * 0.05f - (fwdAcc * 0.035f).coerceIn(-0.2f, 0.25f) + pitchBias

        pitch += (pitchTarget - pitch) * min(1f, dt * 5f)

        tilt += (tiltTarget - tilt) * min(1f, dt * 3f)

        // Тяжёлый полёт: покачивание в такт взмахам и медленный дрейф при зависании.
        bob = if (flying) sin(t * 6.3f) * 0.014f + sin(t * 1.9f + 1f) * 0.03f * hoverK else 0f

        val drift = hoverK * 0.02f

        jx = sin(t * 1.7f) * drift
        jy = 0f
        jz = cos(t * 1.3f) * drift

        if (shiver > 0.01f) {
            jx += (Random.nextFloat() - 0.5f) * 0.03f * shiver
            jy += (Random.nextFloat() - 0.5f) * 0.02f * shiver
            jz += (Random.nextFloat() - 0.5f) * 0.03f * shiver
        }

        // Поза модели: цели из фазы плавно подтягиваются, взмах крыльев идёт своей частотой.
        val kWing = min(1f, dt * 9f)
        val kLeg = min(1f, dt * 8f)
        val kAnt = min(1f, dt * 7f)

        pose.flap += (flapT - pose.flap) * kWing
        pose.fold += (foldT - pose.fold) * min(1f, dt * 6f)
        pose.legReach += (reachT - pose.legReach) * kLeg
        pose.legTuck += (tuckT - pose.legTuck) * kLeg
        pose.groom += (groomT - pose.groom) * kLeg
        pose.legStep += (stepT - pose.legStep) * kLeg
        pose.antPerk += (antPerkT - pose.antPerk) * kAnt
        pose.antWiggle += (antWigT - pose.antWiggle) * kAnt
        pose.pollen += (pollenLoad - pose.pollen) * min(1f, dt * 2f)

        pose.flapPhase = (pose.flapPhase + dt * TWO_PI_F * (flapRate + 1.5f * sin(t * 0.9f))) % TWO_PI_F
        pose.legPhase += dt * 8f
        pose.antPhase += dt * (3.5f + 6f * pose.antWiggle)

        // Анимация крыльев и лап.
        if (frozen) {
            twitchT = (twitchT - dt).coerceAtLeast(0f)
            val twitch = if (twitchT > 0f) sin(twitchT / 0.6f * PI_F) else 0f
            animTime = SLEEP_ANIM_TIME + twitch * 0.12f
        } else {
            animT = (animT + dt * animSpeed) % CLIP
            animTime = animT
        }

        // Пыльца и эмоции.
        for (p in pollen) {
            if (p.life <= 0f) continue

            p.life -= dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.z += p.vz * dt
            p.vy -= 0.12f * dt
        }

        for (e in emotes) {
            if (e.life <= 0f) continue

            e.life -= dt

            val age = e.max - e.life

            if (e.orbit) {
                // Звёздочки кружатся над головой.
                val a = age * 7f + e.ph

                e.x = x + cos(a) * 0.3f
                e.y = y + 0.42f + sin(a * 2f) * 0.04f
                e.z = z + sin(a) * 0.3f
            } else {
                e.x += e.vx * dt + sin(age * 4f + e.ph) * 0.1f * dt
                e.y += e.vy * dt
                e.size += e.grow * dt
                e.vy *= (1f - 0.3f * dt)
            }
        }

        shake = (shake * (1f - 3.5f * dt)).coerceAtLeast(0f)

        if (phase != Phase.DANCE) bounce = 0f
        swarm.beat = if (phase == Phase.DANCE) bounce else 0f

        // Ночью светлячки кружат вокруг спального цветка, в остальное время — по всему лугу.
        val night = mode == BeeTime.NIGHT
        swarm.update(
            t, dt, !dayLike, night,
            if (night) sleepX else x, if (night) sleepY else y, if (night) sleepZ else z
        )
    }
}
