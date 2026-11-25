package eu.remk.coaching.wear

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * RE:MK 360° Watch App (Wear OS 5 / Pixel Watch 4) – v37.22 Hyper-Chrono LuminousGlass
 * - Konturiertes 2×2 Saphir-Quadranten Home-Menü mit zentralem LV.-Kern & direktem +250 ml Bogen
 * - 60-fps Finger-Follow-Swipe mit Feder-Physik, Tactile Press-Depth & Swipe-Right zum Abhaken
 * - Konkave Rauchglas-Scrub-Dials (Finger-Scrub + Drehkrone + 1-Tap Auto-Close Zahlenstrahl)
 * - Doppeltipp = Satz abhaken | Long-Press auf ✓ = Undo | Hintergrund-Pausen-Benachrichtigung
 * - 100 % v37.22 Sync-Vertrag (lv, rk, xpPct, rad, ep/ec/ef, tc/tf) & Security-Hardening (0 Hardcoded Keys)
 */
class WearMainActivity : Activity(), SensorEventListener, LocationListener {

    data class WatchExercise(
        val id: String,
        var name: String,
        var sets: Int,
        var targetReps: Int,
        var kg: Float,
        var prevRef: String,
        val unilateral: Boolean = false,
        var doneSets: Int = 0
    )

    data class WatchMeal(
        val slot: String,
        val title: String,
        var kcal: Int,
        var done: Boolean = false
    )

    data class WatchShopItem(
        val origIdx: Int,
        val title: String,
        var done: Boolean = false
    )

    data class WodPreset(
        val id: String,
        val label: String,
        val workSec: Int,
        val restSec: Int,
        val totalRounds: Int,
        val met: Double,
        val defaultRpe: Int
    )

    data class ActivityPreset(
        val emoji: String,
        val title: String,
        val kind: String,
        val durationMin: Int,
        val defaultRpe: Int,
        val met: Double
    )

    // =========================================================================
    // CUSTOM CANVAS LUXURY VIEWS (HYPER-CHRONO LUMINOUSGLASS)
    // =========================================================================

    /**
     * Äußerer LuminousGlass Fortschritts-Ring mit feinen Chronographen-Ticks & Puls-Animation
     */
    inner class HyperArcRingView(
        context: Context,
        var progressPct: Float = 0.65f,
        var primaryColorHex: String = "#FF5E00",
        var secondaryColorHex: String? = null,
        var startDeg: Float = -150f,
        var maxSweepDeg: Float = 300f,
        var showMicroTicks: Boolean = true
    ) : View(context) {
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#223045")
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
        }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#162032")
            strokeCap = Paint.Cap.ROUND
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val rect = RectF()
        private var pulseBoost = 0f

        fun animatePulse() {
            ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = 420L
                addUpdateListener {
                    pulseBoost = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val size = min(width, height).toFloat()
            val cx = width / 2f
            val cy = height / 2f
            val stroke = dp(4).toFloat() + pulseBoost * dp(3)
            val pad = dp(6).toFloat()
            rect.set(cx - size / 2f + pad, cy - size / 2f + pad, cx + size / 2f - pad, cy + size / 2f - pad)

            if (showMicroTicks) {
                val rOut = size / 2f - dp(2)
                val rIn = rOut - dp(4)
                for (i in 0 until 48) {
                    val a = Math.toRadians((i * 7.5 - 90.0))
                    canvas.drawLine(
                        cx + (rIn * cos(a)).toFloat(), cy + (rIn * sin(a)).toFloat(),
                        cx + (rOut * cos(a)).toFloat(), cy + (rOut * sin(a)).toFloat(),
                        tickPaint
                    )
                }
            }

            trackPaint.strokeWidth = dp(4).toFloat()
            canvas.drawArc(rect, startDeg, maxSweepDeg, false, trackPaint)

            val sweep = maxSweepDeg * progressPct.coerceIn(0.04f, 1f)
            val c1 = Color.parseColor(primaryColorHex)
            val c2 = secondaryColorHex?.let { Color.parseColor(it) } ?: c1

            glowPaint.strokeWidth = stroke + dp(4)
            glowPaint.color = Color.argb((60 + (pulseBoost * 80).toInt()).coerceIn(0, 160), Color.red(c1), Color.green(c1), Color.blue(c1))
            canvas.drawArc(rect, startDeg, sweep, false, glowPaint)

            arcPaint.strokeWidth = stroke
            if (secondaryColorHex != null) {
                arcPaint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, c1, c2, Shader.TileMode.CLAMP)
            } else {
                arcPaint.shader = null
                arcPaint.color = c1
            }
            canvas.drawArc(rect, startDeg, sweep, false, arcPaint)
        }
    }

    /**
     * Konturiertes 2×2 Saphir-Quadranten Home-Menü (exakt nach Hyper-Chrono Entwurf):
     * - 4 gebogene Rauchglas-Quadranten entlang der runden Lünette mit Thin-Line-Vektor-Icons
     * - Schwebender zentraler LV.-Kern in der Mitte
     * - Gebogene obere Status-Zeile (Uhrzeit • RE:MK 360° • ❤️ BPM)
     * - Gebogene untere Cyan-Pille (💧 +250 ml • 1,75 L) als Direkt-Aktion auf dem Home-Screen!
     */
    inner class SapphireQuadrantHubView(context: Context) : View(context) {
        private var pressedZone = -1 // 0=TL, 1=TR, 2=BL, 3=BR, 4=CENTER_LVL, 5=WATER_BOTTOM
        private var waterRipple = 0f

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        private val glowRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(5).toFloat() }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        private val arcTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        private val outerRect = RectF()
        private val innerRect = RectF()
        private val textArcPath = Path()

        private fun triggerWaterPulse() {
            ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = 460L
                addUpdateListener {
                    waterRipple = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun hitTestZone(x: Float, y: Float): Int {
            val cx = width / 2f
            val cy = height / 2f
            val dx = x - cx
            val dy = y - cy
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val size = min(width, height).toFloat()

            if (dist <= size * 0.165f) return 4 // Center LV. Core
            if (dy > size * 0.30f && abs(dx) < size * 0.30f) return 5 // Bottom +250 ml arc
            if (dy < -size * 0.33f) return 6 // Top status -> Cardio/Sync quick jump

            return when {
                dx < 0 && dy < 0 -> 0 // TL: Training
                dx >= 0 && dy < 0 -> 1 // TR: Ernährung
                dx < 0 && dy >= 0 -> 2 // BL: Cockpit
                else -> 3 // BR: Einkauf
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressedZone = hitTestZone(event.x, event.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressedZone = -1
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val z = hitTestZone(event.x, event.y)
                    val matched = (z == pressedZone)
                    pressedZone = -1
                    invalidate()
                    if (matched) {
                        triggerVibration(22)
                        when (z) {
                            0 -> navigateFromHomeHubToPage(0) // Training
                            1 -> navigateFromHomeHubToPage(1) // Ernährung
                            2, 4 -> navigateFromHomeHubToPage(2) // 360° LVL-Cockpit
                            3 -> navigateFromHomeHubToPage(3) // Einkauf & Sync
                            5 -> {
                                addWaterLiters(0.25f, reRender = false)
                                triggerWaterPulse()
                                Toast.makeText(context, "💧 +250 ml (${String.format("%.2f", prefs.getFloat("drank_liters", 0f)).replace(".", ",")} L)", Toast.LENGTH_SHORT).show()
                            }
                            6 -> navigateFromHomeHubToPage(4) // Cardio & Finisher
                        }
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun buildQuadrantPath(cx: Float, cy: Float, rOuter: Float, rInner: Float, startDeg: Float, sweepDeg: Float, gapPx: Float): Path {
            val p = Path()
            outerRect.set(cx - rOuter, cy - rOuter, cx + rOuter, cy + rOuter)
            innerRect.set(cx - rInner, cy - rInner, cx + rInner, cy + rInner)
            val a1 = startDeg + gapPx
            val a2 = startDeg + sweepDeg - gapPx
            p.arcTo(outerRect, a1, a2 - a1, true)
            p.arcTo(innerRect, a2, -(a2 - a1), false)
            p.close()
            return p
        }

        private fun drawThinIcon(canvas: Canvas, zone: Int, icx: Float, icy: Float, colorInt: Int, sc: Float) {
            iconPaint.color = colorInt
            iconPaint.strokeWidth = dp(2).toFloat()
            val u = dp(7) * sc
            when (zone) {
                0 -> { // Thin-line Dumbbell
                    canvas.drawLine(icx - u * 1.1f, icy, icx + u * 1.1f, icy, iconPaint)
                    canvas.drawRoundRect(icx - u * 1.45f, icy - u * 0.75f, icx - u * 0.95f, icy + u * 0.75f, dp(2).toFloat(), dp(2).toFloat(), iconPaint)
                    canvas.drawRoundRect(icx + u * 0.95f, icy - u * 0.75f, icx + u * 1.45f, icy + u * 0.75f, dp(2).toFloat(), dp(2).toFloat(), iconPaint)
                    canvas.drawLine(icx - u * 1.8f, icy - u * 0.45f, icx - u * 1.8f, icy + u * 0.45f, iconPaint)
                    canvas.drawLine(icx + u * 1.8f, icy - u * 0.45f, icx + u * 1.8f, icy + u * 0.45f, iconPaint)
                }
                1 -> { // Thin-line Apple
                    canvas.drawCircle(icx - u * 0.35f, icy + u * 0.15f, u * 0.78f, iconPaint)
                    canvas.drawCircle(icx + u * 0.35f, icy + u * 0.15f, u * 0.78f, iconPaint)
                    canvas.drawLine(icx, icy - u * 0.55f, icx + u * 0.25f, icy - u * 1.15f, iconPaint)
                }
                2 -> { // Thin-line Hexagon Radar
                    val hp = Path()
                    for (i in 0 until 6) {
                        val a = Math.toRadians((i * 60 - 90).toDouble())
                        val px = icx + (u * 1.15f * cos(a)).toFloat()
                        val py = icy + (u * 1.15f * sin(a)).toFloat()
                        if (i == 0) hp.moveTo(px, py) else hp.lineTo(px, py)
                    }
                    hp.close()
                    canvas.drawPath(hp, iconPaint)
                    val hp2 = Path()
                    for (i in 0 until 6) {
                        val a = Math.toRadians((i * 60 - 90).toDouble())
                        val px = icx + (u * 0.55f * cos(a)).toFloat()
                        val py = icy + (u * 0.55f * sin(a)).toFloat()
                        if (i == 0) hp2.moveTo(px, py) else hp2.lineTo(px, py)
                    }
                    hp2.close()
                    canvas.drawPath(hp2, iconPaint)
                }
                3 -> { // Thin-line Shopping Cart
                    canvas.drawLine(icx - u * 1.25f, icy - u * 0.75f, icx - u * 0.75f, icy - u * 0.75f, iconPaint)
                    canvas.drawLine(icx - u * 0.75f, icy - u * 0.75f, icx - u * 0.45f, icy + u * 0.35f, iconPaint)
                    canvas.drawLine(icx - u * 0.45f, icy + u * 0.35f, icx + u * 0.95f, icy + u * 0.35f, iconPaint)
                    canvas.drawLine(icx + u * 0.95f, icy + u * 0.35f, icx + u * 1.15f, icy - u * 0.45f, iconPaint)
                    canvas.drawLine(icx - u * 0.65f, icy - u * 0.45f, icx + u * 1.15f, icy - u * 0.45f, iconPaint)
                    canvas.drawCircle(icx - u * 0.25f, icy + u * 0.85f, u * 0.22f, iconPaint)
                    canvas.drawCircle(icx + u * 0.75f, icy + u * 0.85f, u * 0.22f, iconPaint)
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val size = min(w, h)

            canvas.drawColor(Color.parseColor("#05070B"))

            // 1. Curved Top Status Arc ("17:42 • RE:MK 360° • ❤️ 72")
            val nowClock = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())
            val topR = size * 0.435f
            textArcPath.reset()
            outerRect.set(cx - topR, cy - topR, cx + topR, cy + topR)
            textArcPath.addArc(outerRect, 208f, 124f)
            arcTextPaint.color = Color.parseColor("#CBD5E1")
            arcTextPaint.textSize = dp(10).toFloat()
            canvas.drawTextOnPath("$nowClock  •  RE:MK 360°  •  ❤️ $liveBpm", textArcPath, 0f, dp(3).toFloat(), arcTextPaint)

            // 2. 4 Sculpted Smoked-Sapphire Quadrants
            val qSpecs = listOf(
                Triple(0, 185f to 80f, "#FF5E00" to "Training"),
                Triple(1, 275f to 80f, "#10B981" to "Ernährung"),
                Triple(2, 95f to 80f, "#00D4FF" to "Cockpit"),
                Triple(3, 5f to 80f, "#3B82F6" to "Einkauf")
            )
            val baseOuterR = size * 0.372f
            val baseInnerR = size * 0.165f

            for ((z, angles, meta) in qSpecs) {
                val (colHex, lbl) = meta
                val colInt = Color.parseColor(colHex)
                val isPressed = (pressedZone == z)
                val sc = if (isPressed) 0.96f else 1.0f
                val rO = baseOuterR * sc
                val rI = baseInnerR

                val qPath = buildQuadrantPath(cx, cy, rO, rI, angles.first, angles.second, 3.5f)
                val midRad = Math.toRadians((angles.first + angles.second / 2.0))
                val midR = (rO + rI) * 0.51f
                val mcx = cx + (midR * cos(midRad)).toFloat()
                val mcy = cy + (midR * sin(midRad)).toFloat()

                // Smoked-Sapphire Fill with subtle inner sheen
                bgPaint.style = Paint.Style.FILL
                bgPaint.shader = RadialGradient(
                    mcx, mcy - dp(8), rO * 0.55f,
                    Color.argb(if (isPressed) 100 else 58, Color.red(colInt), Color.green(colInt), Color.blue(colInt)),
                    Color.parseColor("#E6111827"),
                    Shader.TileMode.CLAMP
                )
                canvas.drawPath(qPath, bgPaint)
                bgPaint.shader = null

                // Glowing Rim + Crisp 1.5dp Border
                glowRimPaint.color = Color.argb(if (isPressed) 140 else 65, Color.red(colInt), Color.green(colInt), Color.blue(colInt))
                canvas.drawPath(qPath, glowRimPaint)
                rimPaint.color = colInt
                rimPaint.strokeWidth = dp(2).toFloat()
                canvas.drawPath(qPath, rimPaint)

                // Thin-line custom vector icon + crisp label
                drawThinIcon(canvas, z, mcx, mcy - dp(7), colInt, sc)
                textPaint.color = Color.parseColor("#F8FAFC")
                textPaint.textSize = dp(10).toFloat()
                canvas.drawText(lbl, mcx, mcy + dp(13), textPaint)
            }

            // 3. Floating Center Glowing LV. Medallion
            val coreR = size * (if (pressedZone == 4) 0.132f else 0.142f)
            val cyanCol = Color.parseColor("#00D4FF")
            bgPaint.style = Paint.Style.FILL
            bgPaint.color = Color.parseColor("#0B1220")
            canvas.drawCircle(cx, cy, coreR, bgPaint)
            glowRimPaint.color = Color.argb(95, 0, 212, 255)
            canvas.drawCircle(cx, cy, coreR, glowRimPaint)
            rimPaint.color = cyanCol
            rimPaint.strokeWidth = dp(2).toFloat()
            canvas.drawCircle(cx, cy, coreR, rimPaint)

            textPaint.color = Color.WHITE
            textPaint.textSize = dp(11).toFloat()
            canvas.drawText("LV. $lvlOverall", cx, cy + dp(2), textPaint)
            textPaint.color = cyanCol
            textPaint.textSize = dp(8).toFloat()
            canvas.drawText("$lvlRankCode-RANG", cx, cy + dp(11), textPaint)

            // 4. Curved Bottom Direct-Action Cyan Pill ("💧 +250 ml • 1,75 L")
            val drankL = prefs.getFloat("drank_liters", 0.0f)
            val waterStr = String.format("%.2f", drankL).replace(".", ",")
            val pillRect = RectF(cx - dp(62), cy + size * 0.34f, cx + dp(62), cy + size * 0.455f)
            bgPaint.style = Paint.Style.FILL
            bgPaint.color = if (pressedZone == 5 || waterRipple > 0.1f) Color.parseColor("#00D4FF") else Color.parseColor("#111827")
            canvas.drawRoundRect(pillRect, dp(16).toFloat(), dp(16).toFloat(), bgPaint)
            rimPaint.color = Color.parseColor("#00D4FF")
            rimPaint.strokeWidth = (dp(1) + waterRipple * dp(2))
            canvas.drawRoundRect(pillRect, dp(16).toFloat(), dp(16).toFloat(), rimPaint)

            textPaint.color = if (pressedZone == 5 || waterRipple > 0.1f) Color.BLACK else Color.parseColor("#00D4FF")
            textPaint.textSize = dp(10).toFloat()
            canvas.drawText("💧 +250 ml • $waterStr L", cx, pillRect.centerY() + dp(3), textPaint)
        }
    }

    /**
     * Konkaver Rauchglas-Scrub-Dial für Gewicht (40 kg) & Wdh. (10 Wdh.):
     * - Finger links/rechts ziehen = stufenloses Scrubbing mit Haptik-Tick pro Schritt!
     * - Tipp links/rechts = - / + Schritt
     * - Tipp Mitte = 1-Tap Auto-Close Zahlenstrahl
     */
    inner class ConcaveScrubDialView(
        context: Context,
        private val valueText: String,
        private val unitText: String,
        private val isFocused: Boolean,
        private val accentHex: String,
        private val onStep: (Int) -> Unit,
        private val onCenterTap: () -> Unit
    ) : View(context) {
        private var startX = 0f
        private var lastScrubX = 0f
        private var didScrub = false
        private var isDown = false

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND }
        private val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            fontFeatureSettings = "tnum"
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = Color.parseColor("#94A3B8")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val rect = RectF()

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    lastScrubX = event.x
                    didScrub = false
                    isDown = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start()
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = event.x - lastScrubX
                    val stepThreshold = dp(18).toFloat()
                    if (abs(delta) >= stepThreshold) {
                        val dir = if (delta > 0) 1 else -1
                        lastScrubX = event.x
                        didScrub = true
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onStep(dir)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDown = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    animate().scaleX(1f).scaleY(1f).setDuration(140).setInterpolator(OvershootInterpolator(1.6f)).start()
                    if (event.actionMasked == MotionEvent.ACTION_UP && !didScrub) {
                        val w = width.toFloat().coerceAtLeast(1f)
                        when {
                            event.x < w * 0.27f -> { triggerVibration(16); onStep(-1) }
                            event.x > w * 0.73f -> { triggerVibration(16); onStep(1) }
                            else -> { triggerVibration(20); onCenterTap() }
                        }
                    }
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val pad = dp(3).toFloat()
            rect.set(pad, pad, w - pad, h - pad)
            val rad = dp(18).toFloat()
            val acc = Color.parseColor(accentHex)

            // Smoked-Sapphire Concave Gradient
            fillPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                Color.parseColor("#182236"),
                Color.parseColor("#0D1320"),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, rad, rad, fillPaint)
            fillPaint.shader = null

            // Soft Glow + Crisp 1.5dp Rim
            if (isFocused || isDown) {
                borderPaint.color = Color.argb(75, Color.red(acc), Color.green(acc), Color.blue(acc))
                borderPaint.strokeWidth = dp(4).toFloat()
                canvas.drawRoundRect(rect, rad, rad, borderPaint)
            }
            borderPaint.color = if (isFocused) acc else Color.parseColor("#2B3B55")
            borderPaint.strokeWidth = if (isFocused) dp(2).toFloat() else dp(1).toFloat()
            canvas.drawRoundRect(rect, rad, rad, borderPaint)

            // Micro Scrub-Scale Ticks at bottom of dial
            tickPaint.color = if (isFocused) Color.argb(150, Color.red(acc), Color.green(acc), Color.blue(acc)) else Color.parseColor("#334155")
            val tickCount = 9
            val startTx = w * 0.22f
            val endTx = w * 0.78f
            val ty = h - dp(7).toFloat()
            for (i in 0 until tickCount) {
                val tx = startTx + (endTx - startTx) * (i.toFloat() / (tickCount - 1))
                val th = if (i == 4) dp(4).toFloat() else dp(2.5f).toFloat()
                canvas.drawLine(tx, ty - th, tx, ty, tickPaint)
            }

            // Side − / + Indicators
            subPaint.textSize = dp(13).toFloat()
            subPaint.color = if (isFocused) acc else Color.parseColor("#64748B")
            canvas.drawText("−", dp(11).toFloat(), h * 0.52f, subPaint)
            canvas.drawText("+", w - dp(11).toFloat(), h * 0.52f, subPaint)

            // Monumental Tabular Value + Unit
            valPaint.textSize = dp(19).toFloat()
            canvas.drawText(valueText, w / 2f, h * 0.53f, valPaint)
            subPaint.textSize = dp(9).toFloat()
            subPaint.color = Color.parseColor("#94A3B8")
            canvas.drawText(unitText, w / 2f, h * 0.73f, subPaint)
        }
    }

    /**
     * Juwelen-artiges 6-Achsen-Radar für Screen 2 (360° LVL-Cockpit)
     */
    inner class JewelHexRadarView(
        context: Context,
        private val axes: FloatArray
    ) : View(context) {
        private val labels = arrayOf("STR", "ATH", "END", "MOB", "REC", "CON")
        private var animProgress = 0.25f

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#24334C"); strokeWidth = 1.5f }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#00D4FF"); strokeWidth = 2.6f }
        private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#10B981") }
        private val lblPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CBD5E1"); textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }

        init {
            ValueAnimator.ofFloat(0.25f, 1.0f).apply {
                duration = 380L
                interpolator = OvershootInterpolator(1.2f)
                addUpdateListener { animProgress = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val s = dp(86)
            setMeasuredDimension(s, s)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val maxR = min(cx, cy) - dp(12)
            lblPaint.textSize = dp(7).toFloat()

            for (sc in floatArrayOf(0.5f, 1.0f)) {
                val p = Path()
                for (i in 0 until 6) {
                    val a = Math.toRadians((i * 60 - 90).toDouble())
                    val x = cx + (maxR * sc * cos(a)).toFloat()
                    val y = cy + (maxR * sc * sin(a)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                canvas.drawPath(p, gridPaint)
            }

            fillPaint.shader = LinearGradient(cx - maxR, cy - maxR, cx + maxR, cy + maxR,
                Color.parseColor("#5500D4FF"), Color.parseColor("#5510B981"), Shader.TileMode.CLAMP)

            val dataPath = Path()
            for (i in 0 until 6) {
                val a = Math.toRadians((i * 60 - 90).toDouble())
                val norm = ((axes.getOrElse(i) { 65f } / 100f) * animProgress).coerceIn(0.15f, 1f)
                val dx = cx + (maxR * norm * cos(a)).toFloat()
                val dy = cy + (maxR * norm * sin(a)).toFloat()
                if (i == 0) dataPath.moveTo(dx, dy) else dataPath.lineTo(dx, dy)

                val lx = cx + ((maxR + dp(7)) * cos(a)).toFloat()
                val ly = cy + ((maxR + dp(7)) * sin(a)).toFloat() + dp(2)
                canvas.drawText(labels[i], lx, ly, lblPaint)
            }
            dataPath.close()
            canvas.drawPath(dataPath, fillPaint)
            canvas.drawPath(dataPath, strokePaint)

            for (i in 0 until 6) {
                val a = Math.toRadians((i * 60 - 90).toDouble())
                val norm = ((axes.getOrElse(i) { 65f } / 100f) * animProgress).coerceIn(0.15f, 1f)
                canvas.drawCircle(cx + (maxR * norm * cos(a)).toFloat(), cy + (maxR * norm * sin(a)).toFloat(), dp(2).toFloat(), nodePaint)
            }
        }
    }

    /**
     * 60-fps Finger-Follow-Swipe Container mit Tiefen-Skalierung & Feder-Snap
     */
    inner class KineticSwipeFrameLayout(context: Context) : FrameLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var swipingHoriz = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (showHomeHub || showPairingScreen || inlineRulerMode != "none" || restSecondsLeft > 0 || showWorkoutCompleteOverlay) {
                return false
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; swipingHoriz = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > dp(34) && abs(dx) > abs(dy) * 1.55f) {
                        swipingHoriz = true
                        return true
                    }
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val child = if (childCount > 0) getChildAt(0) else null
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (swipingHoriz && child != null) {
                        val dx = ev.x - downX
                        val frac = (abs(dx) / width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                        child.translationX = dx * 0.65f
                        val sc = 1f - (frac * 0.06f)
                        child.scaleX = sc; child.scaleY = sc
                        child.alpha = 1f - (frac * 0.25f)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = ev.x - downX
                    if (swipingHoriz && abs(dx) > dp(46) && ev.actionMasked == MotionEvent.ACTION_UP) {
                        triggerVibration(18)
                        val goNext = (dx < 0)
                        activePage = if (goNext) (activePage + 1) % totalPages else (activePage - 1 + totalPages) % totalPages
                        renderCurrentScreen(slideFromDir = if (goNext) 1 else -1)
                        swipingHoriz = false
                        return true
                    } else if (child != null) {
                        child.animate().translationX(0f).scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(200).setInterpolator(OvershootInterpolator(1.3f)).start()
                    }
                    swipingHoriz = false
                }
            }
            return swipingHoriz || super.onTouchEvent(ev)
        }
    }

    // =========================================================================
    // STATE VARIABLES
    // =========================================================================
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var rootFrame: KineticSwipeFrameLayout

    private var showPairingScreen = false
    private var showHomeHub = true
    private var activePage = 0 // 0=Training, 1=Ernährung, 2=360° Cockpit, 3=Einkauf & Sync, 4=Cardio & Finisher
    private val totalPages = 5
    private var showWorkoutCompleteOverlay = false
    private var workoutFeelingTag = "Top"

    // Workout State
    private var inlineRulerMode = "none"
    private var focusedField = "kg"
    private var activeEnv = "gym" // gym | homegym | bodyweight | calisthenics
    private var weightStep = 2.5f
    private var activeSetType = "N" // N | W | D | F
    private var currentRir = 2
    private var currentDayIdx = 0
    private var currentExIdx = 0
    private var currentSetIdx = 1
    private var unilateralPhaseRight = false
    private var todayMovedVolumeKg = 0
    private var readinessMode = 0 // 0=100%, 1=-5%, 2=-15%
    private var seatPinsMap = mutableMapOf<String, String>()

    // Undo
    private var canUndoLastSet = false
    private var undoDayIdx = 0
    private var undoExIdx = 0
    private var undoSetIdx = 1
    private var undoKg = 40.0f
    private var undoReps = 10
    private var showUndoBannerUntilMs = 0L

    private var liveKg = 40.0f
    private var liveReps = 10

    // Rest Timer
    private var restTimer: CountDownTimer? = null
    private var restSecondsLeft = 0
    private var restTotalSeconds = 90
    private var restEndTimestampMs = 0L
    private var hrRecoveryNotifiedThisRest = false
    private val hrRecoveryThresholdBpm = 122

    private var workoutRingView: HyperArcRingView? = null
    private var restRingView: HyperArcRingView? = null
    private var restCountdownTv: TextView? = null
    private var restHrBadgeTv: TextView? = null
    private var wodRingView: HyperArcRingView? = null
    private var wodCountdownTv: TextView? = null
    private var cardioLiveTv: TextView? = null

    // Macros (v37.22 ep/ec/ef + p/tc/tf)
    private var targetKcal = 2450
    private var targetProt = 154
    private var targetCarbs = 280
    private var targetFat = 72
    private var eatenProt = 148
    private var eatenCarbs = 190
    private var eatenFat = 54
    private var manualSnackDeltaKcal = 0
    private var activePartnerSlot = "p1"

    // 360° LVL-Cockpit (v37.22 lv, rk, xpPct, rad)
    private var lvlOverall = 24
    private var lvlRankCode = "B"
    private var lvlXpPct = 64
    private var lvlStreakDays = 9
    private var lvlAcwr = 1.08f
    private var radarAxes = floatArrayOf(72f, 64f, 58f, 41f, 86f, 79f)
    private val questIds = arrayOf("q_water", "q_protein", "q_workout")
    private val questTitles = arrayOf("Wasser-Ziel", "Protein-Anker", "Training / 8k")
    private val questDone = booleanArrayOf(false, false, false)

    // Cardio & Finisher (100% Marken-Clean!)
    private var screen4Mode = "gps" // "gps" | "finisher" | "courses"
    private val wodPresets = listOf(
        WodPreset("box", "🥊 Box-Runden", 180, 60, 4, 9.0, 8),
        WodPreset("rope", "🪢 Seilsprung", 60, 30, 8, 11.0, 8),
        WodPreset("hiit_mat", "🔥 HIIT-Matte", 20, 10, 8, 10.5, 9),
        WodPreset("emom", "⏱ EMOM 60s", 60, 0, 10, 8.5, 7)
    )
    private var wodPresetIdx = 0
    private var wodRunning = false
    private var wodIsWorkPhase = true
    private var wodRound = 1
    private var wodSecLeft = 180
    private var wodElapsedTotalSec = 0
    private var wodTimer: CountDownTimer? = null

    private val cleanActivityPresets = listOf(
        ActivityPreset("🚴", "Indoor-Cycling", "course", 50, 8, 8.5),
        ActivityPreset("⚽", "Vereinssport", "club", 90, 7, 7.5),
        ActivityPreset("🏆", "Punktspiel", "club", 90, 9, 9.0),
        ActivityPreset("⚡", "Hybrid-Race", "course", 45, 9, 9.5),
        ActivityPreset("🏋️", "Langhantel-Kurs", "course", 60, 7, 6.5),
        ActivityPreset("🥊", "Boxsack", "course", 30, 8, 9.0),
        ActivityPreset("🪢", "Seilhüpfen", "course", 12, 8, 11.0),
        ActivityPreset("🏃", "Laufen", "course", 40, 7, 9.0),
        ActivityPreset("🧘", "Mobility / Yoga", "mobility", 25, 4, 3.0)
    )

    private var sensorManager: SensorManager? = null
    private var heartRateSensor: Sensor? = null
    private var locationManager: LocationManager? = null
    private var lastGpsLocation: Location? = null
    private var gpsRunning = false
    private var gpsTimer: CountDownTimer? = null
    private var gpsSeconds = 0
    private var gpsDistKm = 0.0f
    private var bpmSum = 0L
    private var bpmSamples = 0
    private var liveBpm = 118
    private var activeSportLabel = "Laufen"
    private var athleteAge = 36

    // Live-Sync & 3.5s Scroll-Lock-Guard
    private val syncHandler = Handler(Looper.getMainLooper())
    private var isSyncInProgress = false
    private var lastCloudSyncOk = true
    private var lastTouchTimestampMs = 0L
    private var lastCloudDataSignature = ""

    private val autoSyncRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                val idleMs = System.currentTimeMillis() - lastTouchTimestampMs
                if (idleMs > 3500L && WearOfflineSyncQueue.isPaired(this@WearMainActivity)) {
                    triggerBackgroundCloudPull(silent = true)
                }
                syncHandler.postDelayed(this, 4500L)
            }
        }
    }

    private var trainingDays = mutableListOf(
        "Tag 1 - Push" to mutableListOf(
            WatchExercise("ex_0_0", "Schrägbankdrücken", 3, 10, 40.0f, "W1: 37,5×10"),
            WatchExercise("ex_0_1", "Bankdrücken", 3, 10, 80.0f, "W1: 77,5×10"),
            WatchExercise("ex_0_2", "Seitheben", 3, 12, 12.5f, "W1: 12,5×12"),
            WatchExercise("ex_0_3", "Trizepsdrücken", 3, 12, 25.0f, "W1: 25×12")
        ),
        "Tag 2 - Pull" to mutableListOf(
            WatchExercise("ex_1_0", "Latzug breit", 3, 10, 65.0f, "W1: 65×10"),
            WatchExercise("ex_1_1", "Rudern am Kabel", 3, 10, 70.0f, "W1: 67,5×10"),
            WatchExercise("ex_1_2", "SZ-Curls", 3, 12, 30.0f, "W1: 30×12")
        ),
        "Tag 3 - Beine & Core" to mutableListOf(
            WatchExercise("ex_2_0", "Kniebeugen", 3, 10, 90.0f, "W1: 87,5×10"),
            WatchExercise("ex_2_1", "Bulgarian Split Squats", 3, 10, 20.0f, "W1: 20×10", unilateral = true),
            WatchExercise("ex_2_2", "Ab Wheel Rollout", 3, 15, 0.0f, "BW×15")
        )
    )

    private val dailyMeals = mutableListOf(
        WatchMeal("Frühstück", "High-Protein Porridge", 590, false),
        WatchMeal("Mittag", "Hähnchen-Reis-Bowl", 740, false),
        WatchMeal("Snack", "Skyr & Beeren", 390, false),
        WatchMeal("Abend", "Ofen-Lachs & Kartoffeln", 680, false)
    )

    private val shoppingItems = mutableListOf(
        WatchShopItem(0, "1,2 kg Hähnchenbrust", false),
        WatchShopItem(1, "1 kg Skyr Natur", false),
        WatchShopItem(2, "500 g Haferflocken", false),
        WatchShopItem(3, "800 g Basmatireis", false)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lastTouchTimestampMs = System.currentTimeMillis()

        prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        checkAndPerformLocalMidnightReset()
        loadPersistedWatchState()

        // Erst-Kopplung prüfen (0 Hardcoded Keys!)
        val skippedDemo = prefs.getBoolean("pairing_skipped_demo", false)
        showPairingScreen = (!WearOfflineSyncQueue.isPaired(this) && !skippedDemo)

        // Smart-Override: Bei laufender Pause oder direktem Start über Kachel 1 -> sofort ins Satz-Cockpit!
        val lastRestStart = prefs.getLong("last_rest_start_ms", 0L)
        val restTarget = prefs.getInt("rest_target_sec", 90)
        val remRest = if (lastRestStart > 0L) (restTarget - ((System.currentTimeMillis() - lastRestStart) / 1000L).toInt()) else 0
        if (remRest in 3..300) {
            showHomeHub = false
            activePage = 0
        }

        handleLaunchIntentExtras(intent)

        rootFrame = KineticSwipeFrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#05070B"))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(rootFrame)
        rootFrame.requestFocus()

        renderCurrentScreen(slideFromDir = 0)
        rootFrame.postDelayed({ initWatchHardwareSensorsAndPermissions() }, 350L)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleLaunchIntentExtras(intent)) {
            renderCurrentScreen(slideFromDir = 0)
        }
    }

    private fun handleLaunchIntentExtras(i: Intent?): Boolean {
        if (i == null) return false
        var changed = false
        val reqPage = i.getIntExtra("start_page", -1)
        if (reqPage in 0 until totalPages) {
            activePage = reqPage
            showHomeHub = false
            changed = true
        }
        if (i.getBooleanExtra("skip_home_hub", false)) {
            showHomeHub = false
            changed = true
        }
        if (i.getBooleanExtra("from_rest_alarm", false)) {
            stopRestCountdown(silent = true)
            showHomeHub = false
            activePage = 0
            changed = true
        }
        return changed
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        lastTouchTimestampMs = System.currentTimeMillis()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        if (ev != null && ev.action == MotionEvent.ACTION_SCROLL &&
            ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
        ) {
            lastTouchTimestampMs = System.currentTimeMillis()
            val delta = -ev.getAxisValue(MotionEvent.AXIS_SCROLL)
            val dir = if (delta > 0) 1 else -1
            rootFrame.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

            if (!showHomeHub && !showPairingScreen && activePage == 0 && restSecondsLeft <= 0 && !showWorkoutCompleteOverlay) {
                if (focusedField == "reps") {
                    val isTimed = isIsometricOrTimedExercise(trainingDays[currentDayIdx].second[currentExIdx].name)
                    stepReps(if (isTimed) dir * 5 else dir)
                } else {
                    stepWeight(dir * weightStep)
                }
                return true
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        lastTouchTimestampMs = System.currentTimeMillis()
        checkAndPerformLocalMidnightReset()
        loadPersistedWatchState()
        if (restSecondsLeft > 0 && System.currentTimeMillis() >= restEndTimestampMs) {
            restSecondsLeft = 0
        }
        renderCurrentScreen(slideFromDir = 0)
        syncHandler.removeCallbacks(autoSyncRunnable)
        syncHandler.postDelayed(autoSyncRunnable, 1600L)
    }

    override fun onPause() {
        super.onPause()
        syncHandler.removeCallbacks(autoSyncRunnable)
        saveWatchStateAndRefreshAll()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            inlineRulerMode != "none" -> { inlineRulerMode = "none"; renderCurrentScreen() }
            restSecondsLeft > 0 -> stopRestCountdown(silent = false)
            showWorkoutCompleteOverlay -> { showWorkoutCompleteOverlay = false; renderCurrentScreen() }
            !showHomeHub && !showPairingScreen -> { showHomeHub = true; renderCurrentScreen() }
            else -> super.onBackPressed()
        }
    }

    private fun navigateFromHomeHubToPage(pageIdx: Int) {
        activePage = pageIdx.coerceIn(0, totalPages - 1)
        showHomeHub = false
        renderCurrentScreen(slideFromDir = 0, zoomEntrance = true)
    }

    // =========================================================================
    // HAUPT-RENDERER MIT 60-FPS EINGANGS-ANIMATION
    // =========================================================================
    private fun renderCurrentScreen(slideFromDir: Int = 0, zoomEntrance: Boolean = false) {
        if (!::rootFrame.isInitialized || isFinishing || isDestroyed) return
        rootFrame.removeAllViews()
        workoutRingView = null; restRingView = null; restCountdownTv = null; restHrBadgeTv = null
        wodRingView = null; wodCountdownTv = null; cardioLiveTv = null

        val screenView: View = when {
            showPairingScreen -> buildPairingScreenView()
            showWorkoutCompleteOverlay -> buildWorkoutCompleteOverlayView()
            !showHomeHub && activePage == 0 && restSecondsLeft > 0 -> buildAutoRestTimerOverlayView()
            showHomeHub -> SapphireQuadrantHubView(this)
            else -> when (activePage) {
                0 -> buildScreen0KineticWorkoutView()
                1 -> buildScreen1NutritionMacrosView()
                2 -> buildScreen2LvlCockpitAndPlanView()
                3 -> buildScreen3ShoppingAndSyncView()
                else -> buildScreen4CardioAndFinisherView()
            }
        }

        rootFrame.addView(screenView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 60-fps GPU-Animation (kein Flackern, butterweich!)
        if (slideFromDir != 0) {
            screenView.translationX = slideFromDir * dp(60).toFloat()
            screenView.scaleX = 0.94f
            screenView.scaleY = 0.94f
            screenView.alpha = 0.65f
            screenView.animate()
                .translationX(0f).scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(220L)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
        } else if (zoomEntrance) {
            screenView.scaleX = 0.91f
            screenView.scaleY = 0.91f
            screenView.alpha = 0.7f
            screenView.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(210L)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        }
    }

    // =========================================================================
    // ERST-KOPPLUNG (SECURITY-HARDENED — 0 HARDCODED KEYS!)
    // =========================================================================
    private fun buildPairingScreenView(): View {
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#05070B"))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }

        box.addView(TextView(this).apply {
            text = "RE:MK 360°"
            setTextColor(Color.parseColor("#00D4FF"))
            textSize = 14f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = "Uhr mit App koppeln"
            setTextColor(Color.WHITE)
            textSize = 12f; gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(8))
        })

        val nameInput = EditText(this).apply {
            hint = "Dein Name / Nickname"
            setText(WearOfflineSyncQueue.getSyncUserName(this@WearMainActivity))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 12.5f
            setSingleLine(true)
            background = roundedBg("#111827", "#334155", 14)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val keyInput = EditText(this).apply {
            hint = "Lizenz- / Sync-Key"
            setText(WearOfflineSyncQueue.getSyncKey(this@WearMainActivity))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 12.5f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            background = roundedBg("#111827", "#00D4FF", 14)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        box.addView(nameInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        box.addView(keyInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

        val connectBtn = buildGlassPillBtn("🔗 Verbinden & Syncen", "#FF5E00", "#05070B", 12.5f) {
            val k = keyInput.text.toString().trim()
            val n = nameInput.text.toString().trim()
            if (k.isEmpty()) {
                Toast.makeText(this, "Bitte Lizenz-Key eingeben", Toast.LENGTH_SHORT).show()
                return@buildGlassPillBtn
            }
            WearOfflineSyncQueue.savePairingCredentials(this, n, k)
            showPairingScreen = false
            showHomeHub = true
            renderCurrentScreen(zoomEntrance = true)
            triggerBackgroundCloudPull(silent = false)
        }
        box.addView(connectBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { bottomMargin = dp(6) })

        val demoBtn = buildGlassPillBtn("▶ Lokal / Demo testen", "#111827", "#94A3B8", 11f, borderHex = "#334155") {
            prefs.edit().putBoolean("pairing_skipped_demo", true).apply()
            showPairingScreen = false
            showHomeHub = true
            renderCurrentScreen(zoomEntrance = true)
        }
        box.addView(demoBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))

        scroll.addView(box)
        return scroll
    }

    // =========================================================================
    // SCREEN 0: KINETIC 0-SCROLL SATZ-COCKPIT (SCRUB-DIALS + DOUBLE-TAP + LONG-PRESS UNDO)
    // =========================================================================
    @SuppressLint("ClickableViewAccessibility")
    private fun buildScreen0KineticWorkoutView(): View {
        val dayPair = trainingDays[currentDayIdx]
        val exList = dayPair.second
        val curEx = exList[currentExIdx.coerceIn(0, (exList.size - 1).coerceAtLeast(0))]
        val isTimedEx = isIsometricOrTimedExercise(curEx.name)

        detectEnvironmentFromDayTitle(dayPair.first)
        val (sanKg, sanReps) = sanitizeExerciseWeightAndReps(curEx.name, liveKg, liveReps)
        liveKg = sanKg; liveReps = sanReps; curEx.kg = sanKg; curEx.targetReps = sanReps

        val wrapper = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#05070B")) }

        val doneTotal = exList.sumOf { it.doneSets }
        val targetTotal = exList.sumOf { it.sets }.coerceAtLeast(1)
        workoutRingView = HyperArcRingView(
            this,
            progressPct = (doneTotal.toFloat() / targetTotal.toFloat()).coerceIn(0.12f, 1f),
            primaryColorHex = "#FF5E00",
            secondaryColorHex = "#FB923C",
            startDeg = -148f,
            maxSweepDeg = 296f
        )
        wrapper.addView(workoutRingView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }

        // 1. Top Status Bar (17:42 • ● Live • ❤️ 124 BPM) -> Tipp öffnet 2×2 Home-Menü
        val nowClock = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())
        val pendingCnt = WearOfflineSyncQueue.getPendingCount(this)
        val liveTag = if (!WearOfflineSyncQueue.isPaired(this)) "○ Lokal" else if (pendingCnt > 0) "⚡$pendingCnt" else if (lastCloudSyncOk) "● Live" else "○ Off"
        val topTv = TextView(this).apply {
            text = "$nowClock  •  $liveTag  •  ❤️ $liveBpm"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 10f; setSingleLine(true); gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            fontFeatureSettings = "tnum"
            setOnClickListener { triggerVibration(18); showHomeHub = true; renderCurrentScreen(zoomEntrance = true) }
        }
        container.addView(topTv)

        // 2. Übungs-Header + Doppeltipp-Geste zum Blitz-Abhaken!
        val doubleTapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                completeCurrentSet()
                return true
            }
        })
        val exRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val prevTv = TextView(this).apply {
            text = "‹"; setTextColor(Color.parseColor("#FF5E00")); textSize = 20f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            setOnClickListener { triggerVibration(16); switchExerciseSafely(currentExIdx - 1) }
        }
        val uniBadge = if (curEx.unilateral) (if (unilateralPhaseRight) " (R)" else " (L)") else ""
        val titleTv = TextView(this).apply {
            text = "${curEx.name}$uniBadge"
            setTextColor(Color.WHITE); textSize = 13f; setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            setOnTouchListener { _, ev -> doubleTapDetector.onTouchEvent(ev); false }
        }
        val nextTv = TextView(this).apply {
            text = "›"; setTextColor(Color.parseColor("#FF5E00")); textSize = 20f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            setOnClickListener { triggerVibration(16); switchExerciseSafely(currentExIdx + 1) }
        }
        exRow.addView(prevTv, LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT))
        exRow.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        exRow.addView(nextTv, LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(exRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val pin = lookupSeatPin(curEx.name)
        val subTxt = if (pin.isNotEmpty()) "Satz $currentSetIdx/${curEx.sets} • 📌 $pin" else "Satz $currentSetIdx/${curEx.sets} • ${curEx.prevRef}"
        container.addView(TextView(this).apply {
            text = subTxt
            setTextColor(Color.parseColor(if (pin.isNotEmpty()) "#00D4FF" else "#94A3B8"))
            textSize = 10f; setSingleLine(true); ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, dp(3))
        })

        // 3. Kompakte Satz-Typ & RIR Micro-Leiste
        val metaRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (st in listOf("N", "W", "D", "F")) {
            val sel = (activeSetType == st)
            val c = TextView(this).apply {
                text = st
                setTextColor(Color.parseColor(if (sel) "#05070B" else "#94A3B8"))
                textSize = 9f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                background = roundedBg(if (sel) "#FF5E00" else "#111827", if (sel) "#FF5E00" else "#26344D", 8)
                setPadding(dp(7), dp(2), dp(7), dp(2))
                setOnClickListener { triggerVibration(14); activeSetType = st; renderCurrentScreen() }
            }
            metaRow.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        val rirBtn = TextView(this).apply {
            text = "RIR $currentRir"
            setTextColor(Color.parseColor("#00D4FF"))
            textSize = 9f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            background = roundedBg("#111827", "#00D4FF", 8)
            setPadding(dp(7), dp(2), dp(7), dp(2))
            setOnClickListener { triggerVibration(14); currentRir = if (currentRir <= 0) 3 else currentRir - 1; renderCurrentScreen() }
        }
        metaRow.addView(rirBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(4) })
        container.addView(metaRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })

        // 4. Konkave Rauchglas Scrub-Dials ODER Auto-Close Zahlenstrahl
        if (inlineRulerMode == "kg") {
            container.addView(buildAutoCloseNumberLine(isKg = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else if (inlineRulerMode == "reps") {
            container.addView(buildAutoCloseNumberLine(isKg = false), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            val dialRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val kgDial = ConcaveScrubDialView(
                this,
                valueText = if (liveKg <= 0f) "BW" else fmtKg(liveKg),
                unitText = if (liveKg <= 0f) "0 kg" else "kg",
                isFocused = (focusedField == "kg"),
                accentHex = "#FF5E00",
                onStep = { dir -> focusedField = "kg"; stepWeight(dir * weightStep) },
                onCenterTap = { focusedField = "kg"; inlineRulerMode = "kg"; renderCurrentScreen() }
            )
            val repsDial = ConcaveScrubDialView(
                this,
                valueText = "$liveReps",
                unitText = if (isTimedEx) "Sek." else "Wdh.",
                isFocused = (focusedField == "reps"),
                accentHex = "#10B981",
                onStep = { dir -> focusedField = "reps"; stepReps(if (isTimedEx) dir * 5 else dir) },
                onCenterTap = { focusedField = "reps"; inlineRulerMode = "reps"; renderCurrentScreen() }
            )
            dialRow.addView(kgDial, LinearLayout.LayoutParams(0, dp(62), 1f).apply { rightMargin = dp(4) })
            dialRow.addView(repsDial, LinearLayout.LayoutParams(0, dp(62), 1f).apply { leftMargin = dp(4) })
            container.addView(dialRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(5) })
        }

        // 5. Breiter gebogener Emerald-Button (Klick = Satz abhaken | Long-Press = sofortiges Undo!)
        val btnText = if (curEx.unilateral && !unilateralPhaseRight) "✓ LINKS ➔ RECHTS" else "✓ SATZ $currentSetIdx ABHAKEN"
        val logBtn = buildGlassPillBtn(btnText, "#10B981", "#05070B", 13f) {
            workoutRingView?.animatePulse()
            completeCurrentSet()
        }.apply {
            setOnLongClickListener {
                if (canUndoLastSet) {
                    undoLastCompletedSet()
                    Toast.makeText(this@WearMainActivity, "↩ Satz rückgängig", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
        container.addView(logBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))

        if (canUndoLastSet && System.currentTimeMillis() < showUndoBannerUntilMs) {
            container.addView(TextView(this).apply {
                text = "↩ Long-Press oder Tipp = Undo"
                setTextColor(Color.parseColor("#FF5E00"))
                textSize = 9.5f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                setOnClickListener { undoLastCompletedSet() }
            })
        } else {
            container.addView(buildBottomPageDotsStrip())
        }

        wrapper.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return wrapper
    }

    // =========================================================================
    // SCREEN 1: ERNÄHRUNG & MAKROS (CYAN-EMERALD RING + 3 MAKRO-BALKEN + SWIPE-MAHLZEITEN)
    // =========================================================================
    private fun buildScreen1NutritionMacrosView(): View {
        val wrapper = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#05070B")) }
        val openKcal = getPersonalOpenKcal()
        val eatenKcal = (targetKcal - openKcal).coerceIn(0, targetKcal)
        val drankL = prefs.getFloat("drank_liters", 0.0f)

        val ring = HyperArcRingView(
            this,
            progressPct = (eatenKcal.toFloat() / targetKcal.coerceAtLeast(1000).toFloat()).coerceIn(0.08f, 1f),
            primaryColorHex = "#00D4FF",
            secondaryColorHex = "#10B981",
            startDeg = -145f,
            maxSweepDeg = 290f
        )
        wrapper.addView(ring, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(dp(18), dp(14), dp(18), dp(26))
        }

        box.addView(TextView(this).apply {
            text = "RE:MK 360° • MAKROS"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 9.5f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = "${formatDeInt(eatenKcal)} / ${formatDeInt(targetKcal)} kcal"
            setTextColor(Color.WHITE); textSize = 15.5f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            fontFeatureSettings = "tnum"
        })
        box.addView(TextView(this).apply {
            text = "${formatDeInt(openKcal)} kcal offen"
            setTextColor(Color.parseColor("#94A3B8")); textSize = 10f; gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(4))
        })

        // 3 leuchtende horizontale Makro-Balken (EW / KH / F aus v37.22 ep/ec/ef)
        box.addView(buildMacroBarRowView("EW", eatenProt, targetProt, "#FF5E00"))
        box.addView(buildMacroBarRowView("KH", eatenCarbs, targetCarbs, "#00D4FF"))
        box.addView(buildMacroBarRowView("Fett", eatenFat, targetFat, "#10B981"))

        val actRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        actRow.addView(buildGlassPillBtn("💧 +250 ml (${String.format("%.2f", drankL).replace(".", ",")}L)", "#00D4FF", "#05070B", 11f) {
            ring.animatePulse()
            addWaterLiters(0.25f, reRender = true)
        }, LinearLayout.LayoutParams(0, dp(38), 1.35f).apply { rightMargin = dp(3) })
        actRow.addView(buildGlassPillBtn("+150", "#111827", "#F8FAFC", 11f, borderHex = "#334155") {
            triggerVibration(18)
            manualSnackDeltaKcal += 150
            prefs.edit().putInt("manual_snack_delta_kcal", manualSnackDeltaKcal).apply()
            saveWatchStateAndRefreshAll()
            renderCurrentScreen()
        }, LinearLayout.LayoutParams(0, dp(38), 0.75f).apply { leftMargin = dp(3) })
        box.addView(actRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5); bottomMargin = dp(6) })

        // Mahlzeiten mit Tipp & Swipe-Right zum Abhaken!
        dailyMeals.forEachIndexed { idx, meal ->
            val mBtn = buildGlassPillBtn(
                "${if (meal.done) "✓" else "○"} ${meal.slot} • ${meal.title.take(14)}",
                if (meal.done) "#0D261C" else "#111827",
                if (meal.done) "#10B981" else "#F8FAFC",
                11f,
                borderHex = if (meal.done) "#10B981" else "#26344D"
            ) { toggleMealItem(idx) }
            attachSwipeRightAction(mBtn) { toggleMealItem(idx) }
            box.addView(mBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { bottomMargin = dp(4) })
        }

        box.addView(buildHomeReturnChip())
        box.addView(buildBottomPageDotsStrip())
        scroll.addView(box)
        wrapper.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scroll.post { scroll.scrollTo(0, 0) }
        return wrapper
    }

    private fun toggleMealItem(idx: Int) {
        if (idx !in dailyMeals.indices) return
        triggerVibration(22)
        val meal = dailyMeals[idx]
        meal.done = !meal.done
        prefs.edit().putBoolean("meal_done_$idx", meal.done).apply()
        saveWatchStateAndRefreshAll()
        WearOfflineSyncQueue.enqueueEvent(
            this,
            "WATCH_MEAL_TOGGLED",
            "slotIdx=$idx&done=${meal.done}",
            mapOf("slotIdx" to idx, "done" to meal.done)
        )
        renderCurrentScreen()
    }

    private fun buildMacroBarRowView(label: String, eatenG: Int, targetG: Int, hexCol: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(1), dp(4), dp(3))
        }
        val txtRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        txtRow.addView(TextView(this).apply {
            text = label; setTextColor(Color.parseColor(hexCol)); textSize = 9.5f; setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        txtRow.addView(TextView(this).apply {
            text = "${eatenG}g / ${targetG}g"; setTextColor(Color.parseColor("#CBD5E1")); textSize = 9.5f; setTypeface(null, Typeface.BOLD); fontFeatureSettings = "tnum"
        })
        row.addView(txtRow)

        val pct = (eatenG.toFloat() / targetG.coerceAtLeast(1).toFloat()).coerceIn(0.05f, 1f)
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBg("#182235", "#182235", 4, strokeDp = 0)
        }
        track.addView(View(this).apply { background = roundedBg(hexCol, hexCol, 4, strokeDp = 0) }, LinearLayout.LayoutParams(0, dp(4), pct))
        track.addView(View(this), LinearLayout.LayoutParams(0, dp(4), (1f - pct).coerceAtLeast(0.001f)))
        row.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(1) })
        return row
    }

    // =========================================================================
    // SCREEN 2: 360° LVL-COCKPIT (6-ACHSEN-RADAR, QUESTS & TAG-/PLAN-AUSWAHL)
    // =========================================================================
    private fun buildScreen2LvlCockpitAndPlanView(): View {
        val wrapper = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#05070B")) }
        val ring = HyperArcRingView(
            this,
            progressPct = (lvlXpPct / 100f).coerceIn(0.1f, 1f),
            primaryColorHex = "#00D4FF",
            secondaryColorHex = "#10B981",
            startDeg = -148f,
            maxSweepDeg = 296f
        )
        wrapper.addView(ring, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(dp(16), dp(13), dp(16), dp(26))
        }

        box.addView(TextView(this).apply {
            text = "LV. $lvlOverall  •  $lvlRankCode-RANG"
            setTextColor(Color.parseColor("#00D4FF"))
            textSize = 13.5f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            background = roundedBg("#111827", "#00D4FF", 15)
            setPadding(dp(12), dp(3), dp(12), dp(3))
        })
        box.addView(TextView(this).apply {
            text = "$lvlXpPct% XP  •  🔥 ${lvlStreakDays}d  •  ACWR ${String.format(Locale.GERMANY, "%.2f", lvlAcwr)}"
            setTextColor(Color.parseColor(if (lvlAcwr > 1.35f) "#F59E0B" else "#10B981"))
            textSize = 9.5f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); setPadding(0, dp(2), 0, dp(4))
        })

        // Juwelen-Radar links + 3 konkave Stat-Pillen rechts
        val radarRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        radarRow.addView(JewelHexRadarView(this, radarAxes), LinearLayout.LayoutParams(dp(86), dp(86)).apply { rightMargin = dp(6) })

        val pillsCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val statItems = listOf(
            Triple("STR ${radarAxes[0].toInt()}", "#00D4FF", "#111827"),
            Triple("REC ${radarAxes[4].toInt()}", "#10B981", "#111827"),
            Triple("CON ${radarAxes[5].toInt()}", "#FF5E00", "#111827")
        )
        for ((lbl, col, bg) in statItems) {
            pillsCol.addView(TextView(this).apply {
                text = lbl; setTextColor(Color.parseColor(col)); textSize = 10.5f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                background = roundedBg(bg, col, 11); setPadding(dp(8), dp(4), dp(8), dp(4))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(3) })
        }
        radarRow.addView(pillsCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(radarRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })

        // 3 Tages-Missionen (1-Tap)
        for (q in 0..2) {
            val done = questDone[q]
            val qBtn = buildGlassPillBtn(
                "${if (done) "✓" else "○"} ${questTitles[q]}",
                if (done) "#0D261C" else "#111827",
                if (done) "#10B981" else if (q == 2) "#FF5E00" else "#F8FAFC",
                11f,
                borderHex = if (done) "#10B981" else if (q == 2) "#FF5E00" else "#26344D"
            ) { toggleDailyQuest(q) }
            box.addView(qBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)).apply { bottomMargin = dp(3) })
        }

        // Trainingsplan, Readiness & Trainingsort (Gym / Home / BW / Calisthenics)
        box.addView(TextView(this).apply {
            text = "TRAININGSPLAN & READINESS"
            setTextColor(Color.parseColor("#94A3B8")); textSize = 9.5f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); setPadding(0, dp(5), 0, dp(3))
        })

        val dayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val dayCnt = min(trainingDays.size, 6)
        for (i in 0 until dayCnt) {
            val sel = (i == currentDayIdx)
            dayRow.addView(buildGlassPillBtn("T${i + 1}", if (sel) "#FF5E00" else "#111827", if (sel) "#05070B" else "#94A3B8", 10.5f, borderHex = if (sel) "#FF5E00" else "#26344D") {
                currentDayIdx = i; switchExerciseSafely(0)
            }, LinearLayout.LayoutParams(0, dp(28), 1f).apply { setMargins(dp(1), 0, dp(1), 0) })
        }
        box.addView(dayRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })

        val readRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for ((m, lbl, col) in listOf(Triple(0, "100%", "#10B981"), Triple(1, "-5%", "#F59E0B"), Triple(2, "-15%", "#EF4444"))) {
            val sel = (readinessMode == m)
            readRow.addView(buildGlassPillBtn(lbl, if (sel) col else "#111827", if (sel) "#05070B" else col, 10.5f, borderHex = col) {
                applyReadinessAutoregulation(m)
            }, LinearLayout.LayoutParams(0, dp(28), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        box.addView(readRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(5) })

        val exList = trainingDays[currentDayIdx].second
        exList.forEachIndexed { idx, ex ->
            val isCur = (idx == currentExIdx)
            val isDone = (ex.doneSets >= ex.sets)
            box.addView(buildGlassPillBtn(
                "${idx + 1}. ${ex.name.take(15)} (${ex.doneSets}/${ex.sets})",
                "#111827",
                if (isDone) "#10B981" else "#F8FAFC",
                11f,
                borderHex = if (isCur) "#FF5E00" else if (isDone) "#10B981" else "#26344D"
            ) {
                switchExerciseSafely(idx)
                activePage = 0
                renderCurrentScreen(zoomEntrance = true)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)).apply { bottomMargin = dp(3) })
        }

        val envRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        envRow.addView(buildGlassPillBtn("⏳ Besetzt", "#1F1A10", "#F59E0B", 10.5f, borderHex = "#F59E0B") {
            postponeOccupiedExercise()
        }, LinearLayout.LayoutParams(0, dp(32), 1f).apply { rightMargin = dp(3) })
        envRow.addView(buildGlassPillBtn("📍 ${activeEnv.uppercase()}", "#111827", "#00D4FF", 10.5f, borderHex = "#00D4FF") {
            cycleTrainingEnvironment()
        }, LinearLayout.LayoutParams(0, dp(32), 1f).apply { leftMargin = dp(3) })
        box.addView(envRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })

        box.addView(buildHomeReturnChip())
        box.addView(buildBottomPageDotsStrip())
        scroll.addView(box)
        wrapper.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scroll.post { scroll.scrollTo(0, 0) }
        return wrapper
    }

    // =========================================================================
    // SCREEN 3: EINKAUFSLISTE (SWIPE-TO-CHECK) & LIVE-SYNC + KOPPLUNG
    // =========================================================================
    private fun buildScreen3ShoppingAndSyncView(): View {
        val wrapper = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#05070B")) }
        val openShop = shoppingItems.filter { !it.done }
        val doneRatio = if (shoppingItems.isEmpty()) 1f else (shoppingItems.count { it.done }.toFloat() / shoppingItems.size.toFloat())
        wrapper.addView(HyperArcRingView(this, progressPct = doneRatio.coerceIn(0.1f, 1f), primaryColorHex = "#3B82F6", secondaryColorHex = "#00D4FF"), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(dp(18), dp(14), dp(18), dp(26))
        }

        box.addView(TextView(this).apply {
            text = "EINKAUF • ${openShop.size} OFFEN"
            setTextColor(Color.parseColor("#3B82F6"))
            textSize = 11.5f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, dp(5))
        })

        if (openShop.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "✓ Alles im Korb!"
                setTextColor(Color.parseColor("#10B981")); textSize = 12.5f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8))
            })
        } else {
            for (item in openShop) {
                val sBtn = buildGlassPillBtn("○ ${item.title}", "#111827", "#F8FAFC", 11.5f, borderHex = "#26344D") {
                    checkOffShopItem(item)
                }
                attachSwipeRightAction(sBtn) { checkOffShopItem(item) }
                box.addView(sBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { bottomMargin = dp(4) })
            }
        }

        val lastTs = WearOfflineSyncQueue.getLastSyncTimestamp(this)
        val tsStr = if (lastTs > 0L) SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(lastTs)) else "offen"
        box.addView(buildGlassPillBtn(
            if (isSyncInProgress) "⏳ Sync läuft..." else "🔄 Synchronisieren ($tsStr)",
            "#00D4FF", "#05070B", 12f
        ) {
            if (!WearOfflineSyncQueue.isPaired(this)) {
                showPairingScreen = true
                renderCurrentScreen(zoomEntrance = true)
            } else {
                triggerBackgroundCloudPull(silent = false)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(5); bottomMargin = dp(4) })

        val pairedNick = WearOfflineSyncQueue.getSyncUserName(this).ifEmpty { "Nicht gekoppelt" }
        box.addView(buildGlassPillBtn("🔑 Kopplung ($pairedNick)", "#111827", "#94A3B8", 10.5f, borderHex = "#334155") {
            showPairingScreen = true
            renderCurrentScreen(zoomEntrance = true)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))

        box.addView(buildHomeReturnChip())
        box.addView(buildBottomPageDotsStrip())
        scroll.addView(box)
        wrapper.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scroll.post { scroll.scrollTo(0, 0) }
        return wrapper
    }

    private fun checkOffShopItem(item: WatchShopItem) {
        triggerVibration(20)
        item.done = true
        prefs.edit().putBoolean("shop_done_${item.origIdx}", true).apply()
        saveWatchStateAndRefreshAll()
        WearOfflineSyncQueue.enqueueEvent(
            this,
            "WATCH_SHOP_TOGGLED",
            "idx=${item.origIdx}&done=true&name=${item.title}",
            mapOf("idx" to item.origIdx, "done" to true, "name" to item.title)
        )
        renderCurrentScreen()
    }

    // =========================================================================
    // SCREEN 4: CARDIO GPS, INTERVALL-FINISHER & 9 MARKENFREIE KURSE (sRPE)
    // =========================================================================
    private fun buildScreen4CardioAndFinisherView(): View {
        val wrapper = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#05070B")) }
        val ringCol = when (screen4Mode) { "finisher" -> "#FF5E00"; "courses" -> "#00D4FF"; else -> "#10B981" }
        val p = wodPresets[wodPresetIdx]
        val wodPct = (wodSecLeft.toFloat() / (if (wodIsWorkPhase) p.workSec else p.restSec).coerceAtLeast(1).toFloat()).coerceIn(0.05f, 1f)
        wodRingView = HyperArcRingView(this, progressPct = if (screen4Mode == "finisher") wodPct else 0.72f, primaryColorHex = ringCol, secondaryColorHex = "#3B82F6")
        wrapper.addView(wodRingView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(dp(18), dp(14), dp(18), dp(26))
        }

        // 3er Mode-Bar: GPS | Finisher | Kurse
        val mRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for ((mode, lbl, col) in listOf(Triple("gps", "GPS", "#10B981"), Triple("finisher", "Timer", "#FF5E00"), Triple("courses", "Kurse", "#00D4FF"))) {
            val sel = (screen4Mode == mode)
            mRow.addView(buildGlassPillBtn(lbl, if (sel) col else "#111827", if (sel) "#05070B" else "#94A3B8", 10.5f, borderHex = col) {
                screen4Mode = mode; renderCurrentScreen()
            }, LinearLayout.LayoutParams(0, dp(29), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        box.addView(mRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })

        when (screen4Mode) {
            "gps" -> {
                box.addView(buildGlassPillBtn("$activeSportLabel ⟳", "#111827", "#38BDF8", 11.5f, borderHex = "#3B82F6") {
                    val list = listOf("Laufen", "Rad", "Indoor-Cycling", "Hybrid-Race", "Verein")
                    activeSportLabel = list[(list.indexOf(activeSportLabel) + 1) % list.size]
                    prefs.edit().putString("cardio_sport_name", activeSportLabel).apply()
                    renderCurrentScreen()
                }, LinearLayout.LayoutParams(dp(134), dp(32)).apply { bottomMargin = dp(5) })

                val (zLbl, zCol) = getTanakaHeartRateZone(liveBpm)
                val m = (gpsSeconds / 60).toString().padStart(2, '0')
                val s = (gpsSeconds % 60).toString().padStart(2, '0')
                val pace = formatPaceMinPerKm(gpsSeconds, gpsDistKm)
                cardioLiveTv = TextView(this).apply {
                    text = "❤️ $liveBpm BPM • $zLbl\n${String.format(Locale.GERMANY, "%.2f", gpsDistKm)} km  •  $pace /km\n$m:$s"
                    setTextColor(Color.parseColor(zCol)); textSize = 13.5f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                    fontFeatureSettings = "tnum"; setPadding(0, dp(2), 0, dp(6))
                }
                box.addView(cardioLiveTv)

                val gRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                gRow.addView(buildGlassPillBtn(if (gpsRunning) "⏸ Pause" else "▶ Start", if (gpsRunning) "#FF5E00" else "#10B981", "#05070B", 12f) {
                    toggleGpsTracking()
                }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(3) })
                gRow.addView(buildGlassPillBtn("💾 Speichern", "#111827", "#00D4FF", 11.5f, borderHex = "#00D4FF") {
                    saveCardioGpsSession()
                }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(3) })
                box.addView(gRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            "finisher" -> {
                box.addView(buildGlassPillBtn("${p.label} • $wodRound/${p.totalRounds} ⟳", "#111827", "#FF5E00", 11.5f, borderHex = "#FF5E00") {
                    wodPresetIdx = (wodPresetIdx + 1) % wodPresets.size; stopWodTimer(reset = true)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)).apply { bottomMargin = dp(4) })

                val wm = (wodSecLeft / 60).toString().padStart(2, '0')
                val ws = (wodSecLeft % 60).toString().padStart(2, '0')
                wodCountdownTv = TextView(this).apply {
                    text = "$wm:$ws"
                    setTextColor(Color.WHITE); textSize = 32f; gravity = Gravity.CENTER; setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    fontFeatureSettings = "tnum"
                }
                box.addView(wodCountdownTv)

                val fRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                fRow.addView(buildGlassPillBtn(if (wodRunning) "⏸ Pause" else "▶ Start", "#111827", "#00D4FF", 12f, borderHex = "#00D4FF") {
                    toggleWodTimer()
                }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(3) })
                fRow.addView(buildGlassPillBtn("✓ sRPE Log", "#FF5E00", "#05070B", 12f) {
                    wodTimer?.cancel(); wodRunning = false
                    val dur = maxOf(5, (wodElapsedTotalSec / 60).coerceAtLeast((p.workSec * wodRound) / 60))
                    openSrpeDialog(p.label, "course", dur, p.defaultRpe, p.met)
                }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(3) })
                box.addView(fRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            }
            else -> {
                for (ap in cleanActivityPresets) {
                    box.addView(buildGlassPillBtn("${ap.emoji} ${ap.title} (${ap.durationMin}m)", "#111827", "#F8FAFC", 11.5f, borderHex = "#26344D") {
                        openSrpeDialog(ap.title, ap.kind, ap.durationMin, ap.defaultRpe, ap.met)
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { bottomMargin = dp(4) })
                }
            }
        }

        box.addView(buildHomeReturnChip())
        box.addView(buildBottomPageDotsStrip())
        scroll.addView(box)
        wrapper.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scroll.post { scroll.scrollTo(0, 0) }
        return wrapper
    }

    // =========================================================================
    // AUTO-OVERLAYS: PAUSEN-TIMER & WORKOUT COMPLETE + sRPE DIALOG
    // =========================================================================
    private fun buildAutoRestTimerOverlayView(): View {
        val wrapper = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#05070B")) }
        val isRec = (liveBpm < hrRecoveryThresholdBpm)
        val pct = (restSecondsLeft.toFloat() / restTotalSeconds.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        restRingView = HyperArcRingView(this, pct, if (restSecondsLeft <= 5) "#EF4444" else "#FF5E00", if (isRec) "#10B981" else "#00D4FF", -90f, 360f)
        wrapper.addView(restRingView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(22), dp(18), dp(22), dp(16)) }
        content.addView(TextView(this).apply {
            text = "SATZPAUSE"; setTextColor(Color.parseColor("#FF5E00")); textSize = 11f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            setOnClickListener { if (canUndoLastSet) undoLastCompletedSet() }
        })
        val m = (restSecondsLeft / 60).toString().padStart(2, '0')
        val s = (restSecondsLeft % 60).toString().padStart(2, '0')
        restCountdownTv = TextView(this).apply {
            text = "$m:$s"; setTextColor(Color.WHITE); textSize = 35f; gravity = Gravity.CENTER
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD); fontFeatureSettings = "tnum"
        }
        content.addView(restCountdownTv)

        restHrBadgeTv = TextView(this).apply {
            text = if (isRec) "💚 $liveBpm BPM • Bereit" else "❤️ $liveBpm BPM • Erholung"
            setTextColor(Color.parseColor(if (isRec) "#10B981" else "#F59E0B"))
            textSize = 11f; setSingleLine(true); gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            background = roundedBg(if (isRec) "#0D261C" else "#1E1910", if (isRec) "#10B981" else "#F59E0B", 12)
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }
        content.addView(restHrBadgeTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })

        val adjRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        adjRow.addView(buildGlassPillBtn("-15s", "#111827", "#FFFFFF", 12f, borderHex = "#334155") {
            restSecondsLeft = (restSecondsLeft - 15).coerceAtLeast(5); startRestCountdown(restSecondsLeft)
        }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(4) })
        adjRow.addView(buildGlassPillBtn("+30s", "#111827", "#FFFFFF", 12f, borderHex = "#334155") {
            restSecondsLeft += 30; restTotalSeconds = maxOf(restTotalSeconds, restSecondsLeft); startRestCountdown(restSecondsLeft)
        }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { leftMargin = dp(4) })
        content.addView(adjRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })

        content.addView(buildGlassPillBtn("Überspringen", "#FF5E00", "#05070B", 13f) {
            stopRestCountdown(silent = false)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))

        wrapper.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return wrapper
    }

    private fun buildWorkoutCompleteOverlayView(): View {
        val exList = trainingDays[currentDayIdx].second
        val totalSets = exList.sumOf { it.sets }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#05070B")); setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        box.addView(TextView(this).apply { text = "🏆 WORKOUT COMPLETE"; setTextColor(Color.parseColor("#10B981")); textSize = 13.5f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD) })
        box.addView(TextView(this).apply {
            text = "✓ $totalSets/$totalSets Sätze\n🔥 ${formatDeInt(todayMovedVolumeKg)} kg Tonnage"
            setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); setPadding(0, dp(5), 0, dp(7))
        })
        val feelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for ((tag, col) in listOf("Top" to "#10B981", "Ok" to "#00D4FF", "Schwer" to "#FF5E00")) {
            val sel = (workoutFeelingTag == tag)
            feelRow.addView(buildGlassPillBtn(tag, if (sel) col else "#111827", if (sel) "#05070B" else col, 11.5f, borderHex = col) {
                workoutFeelingTag = tag; renderCurrentScreen()
            }, LinearLayout.LayoutParams(0, dp(32), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        box.addView(feelRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        box.addView(buildGlassPillBtn("☁️ Speichern & Syncen", "#10B981", "#05070B", 13f) {
            WearOfflineSyncQueue.enqueueEvent(
                this, "WATCH_WORKOUT_COMPLETED",
                "dayIdx=$currentDayIdx&tonnageKg=$todayMovedVolumeKg&feeling=$workoutFeelingTag",
                mapOf("dayIdx" to currentDayIdx, "tonnageKg" to todayMovedVolumeKg, "feeling" to workoutFeelingTag)
            )
            exList.forEach { it.doneSets = 0 }
            currentDayIdx = (currentDayIdx + 1) % trainingDays.size
            currentExIdx = 0; currentSetIdx = 1; showWorkoutCompleteOverlay = false; showHomeHub = true
            saveWatchStateAndRefreshAll(); renderCurrentScreen(zoomEntrance = true)
            Toast.makeText(this, "🏆 Workout synchronisiert!", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        return box
    }

    private fun openSrpeDialog(title: String, kind: String, durationMin: Int, defaultRpe: Int, met: Double) {
        var selRpe = defaultRpe.coerceIn(1, 10)
        var dlg: AlertDialog? = null
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.parseColor("#05070B")); setPadding(dp(14), dp(12), dp(14), dp(14)) }
        fun rebuild() {
            box.removeAllViews()
            box.addView(TextView(this).apply {
                text = "$title\n${durationMin} Min • sRPE ${durationMin * selRpe} AU"
                setTextColor(Color.parseColor("#00D4FF")); textSize = 12f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, dp(6))
            })
            val rRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            rRow.addView(buildGlassPillBtn("−", "#111827", "#FF5E00", 16f, borderHex = "#FF5E00") { selRpe = (selRpe - 1).coerceAtLeast(1); rebuild() }, LinearLayout.LayoutParams(dp(42), dp(36)))
            rRow.addView(TextView(this).apply { text = "RPE $selRpe/10"; setTextColor(Color.WHITE); textSize = 14.5f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); setPadding(dp(8), 0, dp(8), 0) })
            rRow.addView(buildGlassPillBtn("+", "#111827", "#00D4FF", 16f, borderHex = "#00D4FF") { selRpe = (selRpe + 1).coerceAtMost(10); rebuild() }, LinearLayout.LayoutParams(dp(42), dp(36)))
            box.addView(rRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            box.addView(buildGlassPillBtn("✓ Loggen", "#10B981", "#05070B", 12.5f) {
                WearOfflineSyncQueue.enqueueEvent(
                    this, "WATCH_ACTIVITY_LOGGED",
                    "kind=$kind&title=$title&durationMin=$durationMin&rpe=$selRpe&met=$met",
                    mapOf("kind" to kind, "title" to title, "durationMin" to durationMin, "rpe" to selRpe, "met" to met, "srpeLoad" to (durationMin * selRpe))
                )
                try { dlg?.dismiss() } catch (e: Exception) {}
                Toast.makeText(this, "✓ $title geloggt!", Toast.LENGTH_SHORT).show()
                renderCurrentScreen()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        }
        rebuild()
        dlg = AlertDialog.Builder(this).setView(box).create()
        dlg.show()
    }

    // =========================================================================
    // AKTIONEN, SYNC-PARSER (v37.22) & GESTEN-HELPER
    // =========================================================================
    private fun saveCardioGpsSession() {
        if (gpsRunning) toggleGpsTracking()
        val avgBpm = if (bpmSamples > 0) (bpmSum / bpmSamples).toInt() else liveBpm
        val m = (gpsSeconds / 60).toString().padStart(2, '0')
        val s = (gpsSeconds % 60).toString().padStart(2, '0')
        val durStr = "$m:$s"
        val distStr = String.format(Locale.US, "%.2f", gpsDistKm)
        val kcalEst = (gpsDistKm * 68f + (gpsSeconds / 60f) * 5f).roundToInt()

        prefs.edit()
            .putString("last_cardio_dist", "${String.format(Locale.GERMANY, "%.2f", gpsDistKm)} km")
            .putString("last_cardio_dur", durStr)
            .apply()

        WearOfflineSyncQueue.enqueueEvent(
            this,
            "WATCH_CARDIO_SAVED",
            "sport=$activeSportLabel&distanceKm=$distStr&duration=$durStr&kcal=$kcalEst&bpm=$avgBpm",
            mapOf("sport" to activeSportLabel, "distanceKm" to distStr, "duration" to durStr, "kcal" to kcalEst.toString(), "bpm" to avgBpm.toString())
        )
        saveWatchStateAndRefreshAll()
        Toast.makeText(this, "✓ $activeSportLabel ($distStr km) gespeichert!", Toast.LENGTH_SHORT).show()
        renderCurrentScreen()
    }

    private fun detectEnvironmentFromDayTitle(title: String) {
        val low = title.lowercase()
        when {
            low.contains("home") -> { activeEnv = "homegym"; weightStep = 2.0f }
            low.contains("calisthenics") || low.contains("park") -> { activeEnv = "calisthenics"; weightStep = 2.0f }
            low.contains("bodyweight") || low.contains("hiit") -> { activeEnv = "bodyweight"; weightStep = 2.0f }
            else -> { if (activeEnv == "gym") weightStep = 2.5f }
        }
    }

    private fun cycleTrainingEnvironment() {
        val list = listOf("gym", "homegym", "bodyweight", "calisthenics")
        activeEnv = list[(list.indexOf(activeEnv) + 1) % list.size]
        weightStep = if (activeEnv == "gym") 2.5f else 2.0f
        prefs.edit().putFloat("weight_step", weightStep).putString("active_env", activeEnv).apply()
        WearOfflineSyncQueue.enqueueEvent(this, "WATCH_SWITCH_ENV", "targetEnv=$activeEnv", mapOf("targetEnv" to activeEnv))
        Toast.makeText(this, "Ort: ${activeEnv.uppercase()} (±${fmtKg(weightStep)}kg)", Toast.LENGTH_SHORT).show()
        renderCurrentScreen()
    }

    private fun lookupSeatPin(exName: String): String {
        val norm = exName.lowercase(Locale.GERMANY).trim()
        seatPinsMap[norm]?.let { if (it.isNotEmpty()) return it }
        for ((k, v) in seatPinsMap) if (norm.contains(k) || k.contains(norm)) return v
        return ""
    }

    private fun toggleDailyQuest(q: Int) {
        triggerVibration(22)
        questDone[q] = !questDone[q]
        prefs.edit().putBoolean("quest_done_$q", questDone[q]).apply()
        WearOfflineSyncQueue.enqueueEvent(this, "WATCH_QUEST_TOGGLED", "questId=${questIds[q]}&done=${if (questDone[q]) 1 else 0}", mapOf("questId" to questIds[q], "done" to (if (questDone[q]) 1 else 0)))
        saveWatchStateAndRefreshAll()
        renderCurrentScreen()
    }

    private fun completeCurrentSet() {
        val exList = trainingDays[currentDayIdx].second
        val curEx = exList[currentExIdx]
        if (curEx.unilateral && !unilateralPhaseRight) {
            triggerVibration(35); unilateralPhaseRight = true
            Toast.makeText(this, "🔄 Seite wechseln: RECHTS!", Toast.LENGTH_SHORT).show()
            renderCurrentScreen(); return
        }
        unilateralPhaseRight = false; triggerVibration(60); inlineRulerMode = "none"

        canUndoLastSet = true; undoDayIdx = currentDayIdx; undoExIdx = currentExIdx; undoSetIdx = currentSetIdx
        undoKg = liveKg; undoReps = liveReps; showUndoBannerUntilMs = System.currentTimeMillis() + 5000L

        if (activeSetType != "W") {
            curEx.doneSets = currentSetIdx.coerceAtMost(curEx.sets)
            todayMovedVolumeKg += (liveKg * liveReps).roundToInt()
            prefs.edit().putInt("done_sets_today", prefs.getInt("done_sets_today", 0) + 1).apply()
        }

        WearOfflineSyncQueue.enqueueEvent(
            this, "WATCH_SET_COMPLETED",
            "exId=${curEx.id}&setIdx=$currentSetIdx&kg=$liveKg&reps=$liveReps&rir=$currentRir",
            mapOf("exId" to curEx.id, "setIdx" to currentSetIdx, "kg" to liveKg.toDouble(), "reps" to liveReps, "rir" to currentRir, "setType" to activeSetType)
        )

        val autoRest = calculateOptimalRestSeconds(curEx, activeSetType)
        val wasWarmup = (activeSetType == "W")
        if (wasWarmup) activeSetType = "N"

        if (!wasWarmup) {
            if (currentSetIdx < curEx.sets) currentSetIdx += 1
            else {
                val nextOpen = exList.indexOfFirst { it.doneSets < it.sets }
                if (nextOpen >= 0) {
                    currentExIdx = nextOpen
                    val nx = exList[currentExIdx]
                    currentSetIdx = (nx.doneSets + 1).coerceAtMost(nx.sets)
                    val (k, r) = sanitizeExerciseWeightAndReps(nx.name, nx.kg, nx.targetReps)
                    liveKg = k; liveReps = r
                } else {
                    saveWatchStateAndRefreshAll(); triggerRestAlarmPattern()
                    showWorkoutCompleteOverlay = true; renderCurrentScreen(zoomEntrance = true); return
                }
            }
        }
        saveWatchStateAndRefreshAll()
        startRestCountdown(autoRest)
    }

    private fun undoLastCompletedSet() {
        if (!canUndoLastSet) return
        triggerVibration(40); canUndoLastSet = false
        currentDayIdx = undoDayIdx; currentExIdx = undoExIdx; currentSetIdx = undoSetIdx
        liveKg = undoKg; liveReps = undoReps
        val curEx = trainingDays[currentDayIdx].second[currentExIdx]
        curEx.doneSets = (undoSetIdx - 1).coerceAtLeast(0)
        todayMovedVolumeKg = (todayMovedVolumeKg - (undoKg * undoReps).roundToInt()).coerceAtLeast(0)
        prefs.edit().putInt("done_sets_today", (prefs.getInt("done_sets_today", 1) - 1).coerceAtLeast(0)).apply()
        WearOfflineSyncQueue.removeLastSetEventIfUndo(this, curEx.id, undoSetIdx)
        stopRestCountdown(silent = true); saveWatchStateAndRefreshAll(); renderCurrentScreen()
    }

    private fun postponeOccupiedExercise() {
        triggerVibration(25)
        val exList = trainingDays[currentDayIdx].second
        if (exList.size > 1) {
            val moved = exList.removeAt(currentExIdx)
            exList.add(moved)
            switchExerciseSafely(currentExIdx)
        }
    }

    private fun applyReadinessAutoregulation(newMode: Int) {
        val prevF = when (readinessMode) { 1 -> 0.95f; 2 -> 0.85f; else -> 1.0f }
        val nextF = when (newMode) { 1 -> 0.95f; 2 -> 0.85f; else -> 1.0f }
        readinessMode = newMode
        if (liveKg > 0f) {
            val base = liveKg / prevF
            liveKg = (((base * nextF) * 4f).roundToInt() / 4f).coerceAtLeast(0f)
            trainingDays[currentDayIdx].second[currentExIdx].kg = liveKg
        }
        saveWatchStateAndRefreshAll(); renderCurrentScreen()
    }

    private fun calculateOptimalRestSeconds(ex: WatchExercise, setType: String): Int {
        if (setType == "D") return 20
        if (setType == "W") return 60
        val low = ex.name.lowercase()
        if (isBodyweightExercise(ex.name) || low.contains("seitheben") || low.contains("curl") || low.contains("trizeps")) return 60
        if (low.contains("kniebeug") || low.contains("kreuzheben") || low.contains("bankdrücken") || low.contains("beinpresse")) return 120
        return 90
    }

    private fun startRestCountdown(seconds: Int) {
        restTimer?.cancel(); restSecondsLeft = seconds; restTotalSeconds = seconds.coerceAtLeast(1)
        hrRecoveryNotifiedThisRest = false; restEndTimestampMs = System.currentTimeMillis() + seconds * 1000L
        prefs.edit().putLong("last_rest_start_ms", System.currentTimeMillis()).putInt("rest_target_sec", seconds).apply()
        val curEx = trainingDays[currentDayIdx].second[currentExIdx]
        RestTimerScheduler.scheduleRestEndAlarm(this, seconds, curEx.name, currentSetIdx, curEx.sets, if (liveKg <= 0f) "BW" else "${fmtKg(liveKg)}kg", liveReps)
        RemkComplicationHelper.requestAllComplicationsRefresh(this)
        renderCurrentScreen(zoomEntrance = true)

        restTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val rem = ((restEndTimestampMs - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
                restSecondsLeft = rem
                val m = (rem / 60).toString().padStart(2, '0')
                val s = (rem % 60).toString().padStart(2, '0')
                val isRec = (liveBpm < hrRecoveryThresholdBpm)
                if (rem in 1..3) triggerVibration(85)
                if (isRec && !hrRecoveryNotifiedThisRest && rem < (seconds - 15)) { hrRecoveryNotifiedThisRest = true; triggerVibration(45) }
                restCountdownTv?.text = "$m:$s"
                restRingView?.progressPct = (rem.toFloat() / restTotalSeconds.toFloat()).coerceIn(0f, 1f)
                restRingView?.invalidate()
                restHrBadgeTv?.text = if (isRec) "💚 $liveBpm BPM • Bereit" else "❤️ $liveBpm BPM • Erholung"
            }
            override fun onFinish() {
                restSecondsLeft = 0; triggerRestAlarmPattern()
                RemkComplicationHelper.requestAllComplicationsRefresh(this@WearMainActivity)
                renderCurrentScreen()
            }
        }.start()
    }

    private fun stopRestCountdown(silent: Boolean) {
        restTimer?.cancel(); restSecondsLeft = 0
        prefs.edit().putLong("last_rest_start_ms", 0L).apply()
        RestTimerScheduler.cancelRestEndAlarm(this)
        RemkComplicationHelper.requestAllComplicationsRefresh(this)
        if (!silent) renderCurrentScreen()
    }

    private fun toggleWodTimer() {
        if (wodRunning) { wodRunning = false; wodTimer?.cancel(); renderCurrentScreen(); return }
        wodRunning = true; startWodPhase(wodSecLeft)
    }

    private fun startWodPhase(sec: Int) {
        wodTimer?.cancel(); wodSecLeft = sec.coerceAtLeast(1)
        val endMs = System.currentTimeMillis() + wodSecLeft * 1000L
        renderCurrentScreen()
        wodTimer = object : CountDownTimer(wodSecLeft * 1000L, 500L) {
            override fun onTick(millisUntilFinished: Long) {
                val rem = ((endMs - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
                if (rem != wodSecLeft) {
                    wodSecLeft = rem; wodElapsedTotalSec += 1
                    if (rem in 1..3) triggerVibration(70)
                    wodCountdownTv?.text = "${(rem / 60).toString().padStart(2, '0')}:${(rem % 60).toString().padStart(2, '0')}"
                    val pr = wodPresets[wodPresetIdx]
                    wodRingView?.progressPct = (rem.toFloat() / (if (wodIsWorkPhase) pr.workSec else pr.restSec).coerceAtLeast(1).toFloat()).coerceIn(0.03f, 1f)
                    wodRingView?.invalidate()
                }
            }
            override fun onFinish() {
                triggerRestAlarmPattern()
                val pr = wodPresets[wodPresetIdx]
                if (wodIsWorkPhase && pr.restSec > 0) { wodIsWorkPhase = false; startWodPhase(pr.restSec) }
                else if (wodRound < pr.totalRounds) { wodIsWorkPhase = true; wodRound += 1; startWodPhase(pr.workSec) }
                else { wodRunning = false; openSrpeDialog(pr.label, "course", maxOf(4, wodElapsedTotalSec / 60), pr.defaultRpe, pr.met) }
            }
        }.start()
    }

    private fun stopWodTimer(reset: Boolean) {
        wodTimer?.cancel(); wodRunning = false
        if (reset) { val p = wodPresets[wodPresetIdx]; wodIsWorkPhase = true; wodRound = 1; wodSecLeft = p.workSec; wodElapsedTotalSec = 0 }
        renderCurrentScreen()
    }

    private fun switchExerciseSafely(newIdx: Int) {
        val exList = trainingDays[currentDayIdx].second
        if (exList.isEmpty()) return
        inlineRulerMode = "none"; unilateralPhaseRight = false; activeSetType = "N"
        currentExIdx = (newIdx + exList.size) % exList.size
        val curEx = exList[currentExIdx]
        currentSetIdx = (curEx.doneSets + 1).coerceAtMost(curEx.sets)
        val (k, r) = sanitizeExerciseWeightAndReps(curEx.name, curEx.kg, curEx.targetReps)
        liveKg = k; liveReps = r
        saveWatchStateAndRefreshAll(); renderCurrentScreen()
    }

    private fun setExactWeight(newKg: Float, autoClose: Boolean) {
        liveKg = ((newKg.coerceAtLeast(0f)) * 4f).roundToInt() / 4f
        trainingDays[currentDayIdx].second[currentExIdx].kg = liveKg
        if (autoClose) inlineRulerMode = "none"
        saveWatchStateAndRefreshAll(); renderCurrentScreen()
    }

    private fun setExactReps(newReps: Int, autoClose: Boolean) {
        liveReps = newReps.coerceIn(1, 300)
        trainingDays[currentDayIdx].second[currentExIdx].targetReps = liveReps
        if (autoClose) inlineRulerMode = "none"
        saveWatchStateAndRefreshAll(); renderCurrentScreen()
    }

    private fun stepWeight(delta: Float) = setExactWeight(liveKg + delta, autoClose = false)
    private fun stepReps(delta: Int) = setExactReps(liveReps + delta, autoClose = false)

    private fun addWaterLiters(deltaL: Float, reRender: Boolean = true) {
        triggerVibration(35)
        val curL = prefs.getFloat("drank_liters", 0.0f)
        val nextL = ((curL + deltaL).coerceAtLeast(0f) * 100f).roundToInt() / 100f
        prefs.edit().putFloat("drank_liters", nextL).apply()
        if (nextL >= 2.25f) { questDone[0] = true; prefs.edit().putBoolean("quest_done_0", true).apply() }
        saveWatchStateAndRefreshAll()
        val ml = (deltaL * 1000f).roundToInt()
        WearOfflineSyncQueue.enqueueEvent(this, "WATCH_WATER_ADDED", "addedMl=$ml&drankLiters=$nextL", mapOf("addedMl" to ml, "drankLiters" to nextL.toDouble()))
        if (reRender) renderCurrentScreen()
    }

    private fun isIsometricOrTimedExercise(exName: String): Boolean {
        val low = exName.lowercase()
        return low.contains("plank") || low.contains("hold") || low.contains("stretch") || low.contains("mobility") || low.contains("hollow")
    }

    private fun isBodyweightExercise(exName: String): Boolean {
        val low = exName.lowercase()
        val kw = listOf("ab wheel", "rollout", "plank", "crunch", "leg raise", "beinheben", "liegestütz", "push-up", "klimmzug", "pull-up", "dip", "burpee", "pistol", "stretch", "mobility")
        return kw.any { low.contains(it) }
    }

    private fun sanitizeExerciseWeightAndReps(exName: String, rawKg: Float, rawReps: Int): Pair<Float, Int> {
        val isBw = isBodyweightExercise(exName)
        val isTimed = isIsometricOrTimedExercise(exName)
        val cleanKg = if (isBw && (rawKg == 60.0f || rawKg == 44.0f)) 0.0f else rawKg.coerceAtLeast(0f)
        val cleanReps = if (!isTimed && isBw && rawReps == 45) 15 else rawReps.coerceAtLeast(1)
        return cleanKg to cleanReps
    }

    private fun getPersonalOpenKcal(): Int {
        val sumM = dailyMeals.sumOf { it.kcal }
        val scale = if (sumM > targetKcal * 1.35f && sumM > 0) targetKcal.toFloat() / sumM.toFloat() else 1f
        val openScaled = dailyMeals.filter { !it.done }.sumOf { (it.kcal * scale).roundToInt() }
        return (openScaled - manualSnackDeltaKcal).coerceIn(0, targetKcal)
    }

    private fun checkAndPerformLocalMidnightReset() {
        try {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date())
            val last = prefs.getString("last_daily_reset_date", "") ?: ""
            if (last.isNotEmpty() && last != today) {
                val ed = prefs.edit()
                ed.putString("last_daily_reset_date", today)
                ed.putFloat("drank_liters", 0f).putInt("done_sets_today", 0).putInt("today_volume_kg", 0).putInt("manual_snack_delta_kcal", 0)
                for (i in 0..10) ed.putBoolean("meal_done_$i", false)
                for (q in 0..2) { ed.putBoolean("quest_done_$q", false); questDone[q] = false }
                ed.apply()
            } else if (last.isEmpty()) prefs.edit().putString("last_daily_reset_date", today).apply()
        } catch (e: Exception) {}
    }

    private fun initWatchHardwareSensorsAndPermissions() {
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            heartRateSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val miss = mutableListOf<String>()
                if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) miss.add(Manifest.permission.BODY_SENSORS)
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) miss.add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) miss.add(Manifest.permission.POST_NOTIFICATIONS)
                if (miss.isNotEmpty()) requestPermissions(miss.toTypedArray(), 404)
                else heartRateSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            } else heartRateSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        } catch (e: Exception) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_HEART_RATE && event.values.isNotEmpty()) {
            val bpm = event.values[0].toInt()
            if (bpm in 42..220 && bpm != liveBpm) {
                liveBpm = bpm
                prefs.edit().putInt("last_live_bpm", liveBpm).apply()
                if (gpsRunning) { bpmSum += bpm; bpmSamples += 1 }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onLocationChanged(loc: Location) {
        if (!gpsRunning) return
        val prev = lastGpsLocation
        if (prev != null) { val d = prev.distanceTo(loc); if (d in 1.5f..150f) gpsDistKm += (d / 1000f) }
        lastGpsLocation = loc
    }

    @SuppressLint("MissingPermission")
    private fun toggleGpsTracking() {
        if (gpsRunning) {
            gpsRunning = false; gpsTimer?.cancel()
            try { locationManager?.removeUpdates(this) } catch (e: Exception) {}
            renderCurrentScreen(); return
        }
        gpsRunning = true; lastGpsLocation = null; bpmSum = 0L; bpmSamples = 0
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 2.0f, this)
            }
        } catch (e: Exception) {}
        gpsTimer = object : CountDownTimer(3600 * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                gpsSeconds += 1
                if (gpsSeconds % 4 == 0 && lastGpsLocation == null) gpsDistKm += 0.014f
                val m = (gpsSeconds / 60).toString().padStart(2, '0')
                val s = (gpsSeconds % 60).toString().padStart(2, '0')
                val (zLbl, zCol) = getTanakaHeartRateZone(liveBpm)
                cardioLiveTv?.text = "❤️ $liveBpm BPM • $zLbl\n${String.format(Locale.GERMANY, "%.2f", gpsDistKm)} km  •  ${formatPaceMinPerKm(gpsSeconds, gpsDistKm)} /km\n$m:$s"
                cardioLiveTv?.setTextColor(Color.parseColor(zCol))
            }
            override fun onFinish() { gpsRunning = false }
        }.start()
        renderCurrentScreen()
    }

    private fun getTanakaHeartRateZone(bpm: Int): Pair<String, String> {
        val hfMax = (208.0 - 0.7 * athleteAge).roundToInt().coerceIn(155, 205)
        val pct = (bpm.toFloat() / hfMax.toFloat()) * 100f
        return when {
            pct < 60f -> "Z1 (${pct.roundToInt()}%)" to "#00D4FF"
            pct < 70f -> "Z2 (${pct.roundToInt()}%)" to "#10B981"
            pct < 80f -> "Z3 (${pct.roundToInt()}%)" to "#F59E0B"
            pct < 90f -> "Z4 (${pct.roundToInt()}%)" to "#FF5E00"
            else -> "Z5 (${pct.roundToInt()}%)" to "#EF4444"
        }
    }

    private fun formatPaceMinPerKm(sec: Int, distKm: Float): String {
        if (distKm < 0.03f || sec <= 0) return "5:30"
        val spk = (sec / distKm).roundToInt().coerceIn(150, 1200)
        return "${spk / 60}:${(spk % 60).toString().padStart(2, '0')}"
    }

    private fun loadPersistedWatchState() {
        manualSnackDeltaKcal = prefs.getInt("manual_snack_delta_kcal", 0)
        activeEnv = prefs.getString("active_env", "gym") ?: "gym"
        lvlOverall = prefs.getInt("lvl_overall", 24).coerceIn(1, 60)
        lvlRankCode = prefs.getString("lvl_rank_code", RemkComplicationHelper.computeRankCode(lvlOverall)) ?: "B"
        lvlXpPct = prefs.getInt("lvl_xp_pct", 64).coerceIn(0, 100)
        lvlStreakDays = prefs.getInt("lvl_streak_days", 9)
        lvlAcwr = prefs.getFloat("lvl_acwr", 1.08f)
        radarAxes = RemkComplicationHelper.readRadarAxes(prefs)

        targetKcal = prefs.getInt("personal_target_kcal", 2450).let { if (it in 1100..4600) it else 2450 }
        targetProt = prefs.getInt("macro_target_p", 154)
        targetCarbs = prefs.getInt("macro_target_c", 280)
        targetFat = prefs.getInt("macro_target_f", 72)
        eatenProt = prefs.getInt("macro_eaten_p", 148)
        eatenCarbs = prefs.getInt("macro_eaten_c", 190)
        eatenFat = prefs.getInt("macro_eaten_f", 54)
        for (q in 0..2) questDone[q] = prefs.getBoolean("quest_done_$q", false)

        try {
            val rawPins = prefs.getString("seat_pins_map_json", "{}") ?: "{}"
            val pObj = JSONObject(rawPins)
            val ks = pObj.keys()
            while (ks.hasNext()) {
                val k = ks.next(); val v = pObj.optString(k, "").trim()
                if (v.isNotEmpty()) seatPinsMap[k.lowercase(Locale.GERMANY)] = v
            }
        } catch (e: Exception) {}

        try {
            val rawPlan = prefs.getString("custom_watch_plan_json", "") ?: ""
            if (rawPlan.isNotEmpty()) applyCompactPlanV3722(rawPlan, planJustImported = false)
        } catch (e: Exception) {}

        try {
            val mapObj = JSONObject(prefs.getString("completed_sets_map_json", "{}") ?: "{}")
            trainingDays.forEachIndexed { dIdx, pair ->
                pair.second.forEach { ex ->
                    val v = maxOf(mapObj.optInt("${dIdx}_${ex.id}", 0), mapObj.optInt(ex.id, 0))
                    if (v > ex.doneSets) ex.doneSets = v.coerceIn(0, ex.sets)
                }
            }
        } catch (e: Exception) {}

        weightStep = prefs.getFloat("weight_step", 2.5f)
        currentDayIdx = prefs.getInt("day_idx", 0).coerceIn(0, (trainingDays.size - 1).coerceAtLeast(0))
        val exList = trainingDays[currentDayIdx].second
        currentExIdx = prefs.getInt("cur_ex_idx", 0).coerceIn(0, (exList.size - 1).coerceAtLeast(0))
        val curEx = exList[currentExIdx]
        currentSetIdx = prefs.getInt("cur_set_idx", (curEx.doneSets + 1).coerceAtMost(curEx.sets))
        // Regel 2 aus v37.22: Kein Zurücksetzen auf 40 kg! Übernimmt immer ex.kg/ex.targetReps bei geänderter Übung.
        val sameEx = (prefs.getString("cur_ex_id", "") == curEx.id)
        val (k, r) = sanitizeExerciseWeightAndReps(
            curEx.name,
            if (sameEx) prefs.getFloat("cur_kg", curEx.kg) else curEx.kg,
            if (sameEx) prefs.getInt("cur_reps", curEx.targetReps) else curEx.targetReps
        )
        liveKg = k; liveReps = r
        todayMovedVolumeKg = prefs.getInt("today_volume_kg", 0)
    }

    private fun saveWatchStateAndRefreshAll() {
        val dayPair = trainingDays[currentDayIdx]
        val exList = dayPair.second
        val curEx = exList[currentExIdx.coerceIn(0, (exList.size - 1).coerceAtLeast(0))]
        val openKcal = getPersonalOpenKcal()

        val mapObj = JSONObject()
        trainingDays.forEachIndexed { dIdx, pair ->
            pair.second.forEach { ex ->
                if (ex.doneSets > 0) { mapObj.put("${dIdx}_${ex.id}", ex.doneSets); mapObj.put(ex.id, ex.doneSets) }
            }
        }

        prefs.edit()
            .putFloat("weight_step", weightStep)
            .putInt("day_idx", currentDayIdx)
            .putInt("cur_ex_idx", currentExIdx)
            .putInt("cur_set_idx", currentSetIdx)
            .putInt("cur_total_sets", curEx.sets)
            .putInt("total_sets_today", exList.sumOf { it.sets })
            .putString("cur_ex_id", curEx.id)
            .putString("cur_ex_name", curEx.name)
            .putString("cur_prev_ref", curEx.prevRef)
            .putInt("today_volume_kg", todayMovedVolumeKg)
            .putFloat("cur_kg", liveKg)
            .putInt("cur_reps", liveReps)
            .putInt("personal_target_kcal", targetKcal)
            .putInt("open_kcal", openKcal)
            .putInt("macro_target_p", targetProt).putInt("macro_target_c", targetCarbs).putInt("macro_target_f", targetFat)
            .putInt("macro_eaten_p", eatenProt).putInt("macro_eaten_c", eatenCarbs).putInt("macro_eaten_f", eatenFat)
            .putInt("lvl_overall", lvlOverall).putString("lvl_rank_code", lvlRankCode).putInt("lvl_xp_pct", lvlXpPct)
            .putInt("lvl_streak_days", lvlStreakDays).putFloat("lvl_acwr", lvlAcwr)
            .putString("lvl_radar_csv", radarAxes.joinToString(",") { it.toInt().toString() })
            .putString("completed_sets_map_json", mapObj.toString())
            .apply()

        RemkTileHelper.requestAllTilesRefresh(this)
        RemkComplicationHelper.requestAllComplicationsRefresh(this)
    }

    /**
     * Vollständiger v37.22 compactPlan-Parser:
     * Liest k, p, tc, tf, ep, ec, ef, lv, rk, xpPct, rad, w, slot, d, m, sh mit sicheren Fallbacks!
     */
    private fun applyCompactPlanV3722(jsonStr: String, planJustImported: Boolean): Boolean {
        return try {
            val obj = JSONObject(jsonStr)
            val incK = obj.optInt("k", targetKcal)
            targetKcal = if (incK in 1100..4600) incK else 2450
            if (obj.has("p")) targetProt = obj.optInt("p", targetProt).coerceIn(40, 350)
            if (obj.has("tc")) targetCarbs = obj.optInt("tc", targetCarbs).coerceIn(30, 700)
            if (obj.has("tf")) targetFat = obj.optInt("tf", targetFat).coerceIn(20, 200)
            if (obj.has("ep")) eatenProt = obj.optInt("ep", eatenProt).coerceAtLeast(0)
            if (obj.has("ec")) eatenCarbs = obj.optInt("ec", eatenCarbs).coerceAtLeast(0)
            if (obj.has("ef")) eatenFat = obj.optInt("ef", eatenFat).coerceAtLeast(0)
            if (obj.has("lv")) lvlOverall = obj.optInt("lv", lvlOverall).coerceIn(1, 60)
            if (obj.has("rk")) lvlRankCode = obj.optString("rk", RemkComplicationHelper.computeRankCode(lvlOverall)).ifEmpty { RemkComplicationHelper.computeRankCode(lvlOverall) }
            if (obj.has("xpPct")) lvlXpPct = obj.optInt("xpPct", lvlXpPct).coerceIn(0, 100)
            if (obj.has("st")) lvlStreakDays = obj.optInt("st", lvlStreakDays).coerceAtLeast(0)
            activePartnerSlot = obj.optString("slot", activePartnerSlot).ifEmpty { "p1" }

            val radArr = obj.optJSONArray("rad")
            if (radArr != null && radArr.length() >= 6) {
                radarAxes = FloatArray(6) { i -> radArr.optDouble(i, 65.0).toFloat().coerceIn(10f, 100f) }
            }

            val dArr = obj.optJSONArray("d")
            if (dArr != null && dArr.length() > 0) {
                val newDays = mutableListOf<Pair<String, MutableList<WatchExercise>>>()
                for (i in 0 until dArr.length()) {
                    val dObj = dArr.optJSONObject(i) ?: continue
                    val dTitle = dObj.optString("t", "Tag ${i + 1}")
                    val eArr = dObj.optJSONArray("e")
                    val exList = mutableListOf<WatchExercise>()
                    val seen = mutableSetOf<String>()
                    if (eArr != null) {
                        for (j in 0 until eArr.length()) {
                            val eObj = eArr.optJSONObject(j) ?: continue
                            val exName = eObj.optString("n", "Übung ${j + 1}").trim()
                            val norm = exName.lowercase(Locale.GERMANY)
                            if (seen.contains(norm)) continue
                            seen.add(norm)
                            val setsVal = eObj.optInt("s", 3).coerceAtLeast(1)
                            val (wVal, rVal) = sanitizeExerciseWeightAndReps(exName, eObj.optDouble("w", 0.0).toFloat(), eObj.optInt("r", 10))
                            val defPrev = if (isIsometricOrTimedExercise(exName)) "BW×${rVal}s" else if (wVal <= 0f) "BW×$rVal" else "${fmtKg(wVal)}×$rVal"
                            val rawPrev = eObj.optString("p", defPrev)
                            val isUni = (eObj.optInt("u", 0) == 1) || eObj.optBoolean("u", false)
                            exList.add(WatchExercise(eObj.optString("i", "ex_${i}_$j"), exName, setsVal, rVal, wVal, rawPrev, isUni, eObj.optInt("ds", 0).coerceIn(0, setsVal)))
                        }
                    }
                    if (exList.isNotEmpty()) newDays.add(dTitle to exList)
                }
                if (newDays.isNotEmpty()) {
                    trainingDays = newDays
                    // Regel 2: Beim Import eines neuen Plans direkt ex.kg & ex.targetReps übernehmen!
                    if (planJustImported) {
                        val dI = currentDayIdx.coerceIn(0, trainingDays.size - 1)
                        val eI = currentExIdx.coerceIn(0, trainingDays[dI].second.size - 1)
                        val ex = trainingDays[dI].second[eI]
                        liveKg = ex.kg; liveReps = ex.targetReps
                        prefs.edit().putFloat("cur_kg", ex.kg).putInt("cur_reps", ex.targetReps).putString("cur_ex_id", ex.id).apply()
                    }
                }
            }

            val mArr = obj.optJSONArray("m")
            if (mArr != null && mArr.length() > 0) {
                val tempMeals = mutableListOf<WatchMeal>()
                for (i in 0 until mArr.length()) {
                    val mObj = mArr.optJSONObject(i) ?: continue
                    val mTitle = mObj.optString("n", mObj.optString("t", "Gericht"))
                    val cloudDone = (mObj.optInt("dn", 0) == 1) || mObj.optBoolean("dn", false) || mObj.optBoolean("d", false)
                    tempMeals.add(WatchMeal(mObj.optString("s", "Mahlzeit"), mTitle, mObj.optInt("k", 520).coerceAtLeast(100), cloudDone || prefs.getBoolean("meal_done_$i", false)))
                }
                val sumK = tempMeals.sumOf { it.kcal }
                if (sumK > targetKcal * 1.35f && sumK > 0) {
                    val f = targetKcal.toFloat() / sumK.toFloat()
                    tempMeals.forEach { it.kcal = ((it.kcal * f) / 10f).roundToInt() * 10 }
                }
                if (tempMeals.isNotEmpty()) { dailyMeals.clear(); dailyMeals.addAll(tempMeals) }
            }

            val sArr = obj.optJSONArray("sh")
            if (sArr != null && sArr.length() > 0) {
                shoppingItems.clear()
                for (i in 0 until sArr.length()) {
                    val itemObj = sArr.optJSONObject(i)
                    if (itemObj != null) {
                        val sTitle = itemObj.optString("n", itemObj.optString("title", "")).trim()
                        val sDone = (itemObj.optInt("dn", 0) == 1) || itemObj.optBoolean("dn", false) || prefs.getBoolean("shop_done_$i", false)
                        if (sTitle.isNotEmpty()) shoppingItems.add(WatchShopItem(i, sTitle, sDone))
                    } else {
                        val sTxt = sArr.optString(i, "").trim()
                        if (sTxt.isNotEmpty() && !sTxt.startsWith("{")) shoppingItems.add(WatchShopItem(i, sTxt, prefs.getBoolean("shop_done_$i", false)))
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun triggerBackgroundCloudPull(silent: Boolean) {
        if (isSyncInProgress || !WearOfflineSyncQueue.isPaired(this)) return
        isSyncInProgress = true

        WearOfflineSyncQueue.pullCloudStateToWatchAsync(this, forceFullPull = !silent) { ok, unchanged, cloudObj ->
            runOnUiThread {
                isSyncInProgress = false
                lastCloudSyncOk = ok
                if (ok && unchanged) {
                    if (!silent) {
                        WearOfflineSyncQueue.pushWatchStateToCloudAsync(this)
                        Toast.makeText(this, "✓ Alles aktuell (v37.22 Sync)", Toast.LENGTH_SHORT).show()
                    }
                    return@runOnUiThread
                }

                if (ok && cloudObj != null) {
                    val planRaw = cloudObj.opt("watchPlanCompactJson")
                    val planJson = when (planRaw) {
                        is JSONObject -> planRaw.toString()
                        is String -> planRaw
                        else -> ""
                    }
                    val pinsJson = cloudObj.optString("seatPinsMap", "")
                    val cSets = cloudObj.optInt("doneSetsToday", -1)
                    val cWater = cloudObj.optDouble("drankLiters", -1.0)
                    val cEatenKcal = cloudObj.optInt("eatenKcalToday", -1)
                    val cOpenKcal = cloudObj.optInt("openKcal", -1)

                    val sig = "${planJson.hashCode()}_${pinsJson.hashCode()}_${cSets}_${cWater}_${cEatenKcal}_${cOpenKcal}"
                    val changed = (sig != lastCloudDataSignature)
                    lastCloudDataSignature = sig

                    if (planJson.startsWith("{") && applyCompactPlanV3722(planJson, planJustImported = true)) {
                        prefs.edit().putString("custom_watch_plan_json", planJson).apply()
                    }
                    if (pinsJson.startsWith("{")) prefs.edit().putString("seat_pins_map_json", pinsJson).apply()
                    if (cSets >= 0) prefs.edit().putInt("done_sets_today", maxOf(prefs.getInt("done_sets_today", 0), cSets)).apply()
                    if (cWater >= 0.0) prefs.edit().putFloat("drank_liters", maxOf(prefs.getFloat("drank_liters", 0f), cWater.toFloat())).apply()
                    if (cEatenKcal in 0..4600) {
                        prefs.edit().putInt("open_kcal", (targetKcal - cEatenKcal).coerceAtLeast(0)).apply()
                    } else if (cOpenKcal in 0..4600) {
                        prefs.edit().putInt("open_kcal", cOpenKcal).apply()
                    }

                    loadPersistedWatchState()
                    saveWatchStateAndRefreshAll()

                    // 3,5s Scroll-Lock-Guard
                    val idleMs = System.currentTimeMillis() - lastTouchTimestampMs
                    if ((changed && idleMs > 3500L) || !silent) {
                        renderCurrentScreen()
                    }
                    if (!silent) Toast.makeText(this, "✓ Live-Sync erfolgreich!", Toast.LENGTH_SHORT).show()
                } else if (!silent) {
                    Toast.makeText(this, "⚠ Offline-Queue aktiv", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // =========================================================================
    // KINETISCHE UI-HELPER (TACTILE PRESS-DEPTH, SWIPE-RIGHT & AUTO-CLOSE RULER)
    // =========================================================================
    @SuppressLint("ClickableViewAccessibility")
    private fun attachTactilePress(view: View) {
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(75).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(150).setInterpolator(OvershootInterpolator(1.5f)).start()
            }
            false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachSwipeRightAction(view: View, onSwipeCheck: () -> Unit) {
        var sx = 0f
        var swiping = false
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = ev.x; swiping = false
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - sx
                    if (dx > dp(16)) {
                        swiping = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        v.translationX = dx.coerceAtMost(dp(72).toFloat())
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                    if (swiping && ev.actionMasked == MotionEvent.ACTION_UP && (ev.x - sx) > dp(42)) {
                        v.animate().translationX(dp(120).toFloat()).alpha(0f).setDuration(150).withEndAction {
                            onSwipeCheck()
                        }.start()
                        return@setOnTouchListener true
                    } else {
                        v.animate().translationX(0f).setDuration(160).setInterpolator(OvershootInterpolator(1.3f)).start()
                    }
                    swiping = false
                }
            }
            false
        }
    }

    private fun buildGlassPillBtn(label: String, bgHex: String, txtHex: String, fontSp: Float, borderHex: String? = null, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(txtHex))
            textSize = fontSp
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            fontFeatureSettings = "tnum"
            background = roundedBg(bgHex, borderHex ?: bgHex, 18)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            attachTactilePress(this)
            setOnClickListener { triggerVibration(18); onClick() }
        }
    }

    private fun buildHomeReturnChip(): View {
        return buildGlassPillBtn("🏠 Home-Menü", "#111827", "#00D4FF", 10.5f, borderHex = "#00D4FF") {
            showHomeHub = true
            renderCurrentScreen(zoomEntrance = true)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(118), dp(30)).apply { topMargin = dp(6) }
        }
    }

    private fun buildBottomPageDotsStrip(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) }
        for (i in 0 until totalPages) {
            val active = (i == activePage)
            row.addView(TextView(this).apply {
                text = if (active) "●" else "•"
                setTextColor(Color.parseColor(if (active) "#00D4FF" else "#334155"))
                textSize = if (active) 10f else 12f
                gravity = Gravity.CENTER
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { triggerVibration(14); activePage = i; renderCurrentScreen() }
            })
        }
        return row
    }

    private fun buildAutoCloseNumberLine(isKg: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = roundedBg("#111827", if (isKg) "#FF5E00" else "#10B981", 16, strokeDp = 2)
            setPadding(dp(5), dp(4), dp(5), dp(4))
        }
        val hScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val track = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        var activeChip: View? = null

        if (isKg) {
            val step = if (activeEnv == "gym") 2.5f else 2.0f
            val stops = mutableListOf(0f)
            var w = step
            while (w <= 120f) { stops.add(w); w += step }
            var wb = 125f
            while (wb <= 200f) { stops.add(wb); wb += 5f }
            for (v in stops) {
                val sel = abs(v - liveKg) < 0.25f
                val chip = TextView(this).apply {
                    text = if (v == 0f) "BW" else fmtKg(v)
                    setTextColor(Color.parseColor(if (sel) "#05070B" else "#FFFFFF"))
                    textSize = if (sel) 14.5f else 12.5f
                    setSingleLine(true); gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); fontFeatureSettings = "tnum"
                    background = roundedBg(if (sel) "#FF5E00" else "#162032", if (sel) "#FFFFFF" else "#2B3B55", 11)
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    setOnClickListener { triggerVibration(20); setExactWeight(v, autoClose = true) }
                }
                track.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(2), 0, dp(2), 0) })
                if (sel) activeChip = chip
            }
        } else {
            val stops = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 15, 16, 18, 20, 25, 30, 45, 60)
            for (r in stops) {
                val sel = (r == liveReps)
                val chip = TextView(this).apply {
                    text = "$r"
                    setTextColor(Color.parseColor(if (sel) "#05070B" else "#FFFFFF"))
                    textSize = if (sel) 14.5f else 12.5f
                    setSingleLine(true); gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); fontFeatureSettings = "tnum"
                    background = roundedBg(if (sel) "#10B981" else "#162032", if (sel) "#FFFFFF" else "#2B3B55", 11)
                    setPadding(dp(11), dp(7), dp(11), dp(7))
                    setOnClickListener { triggerVibration(20); setExactReps(r, autoClose = true) }
                }
                track.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(2), 0, dp(2), 0) })
                if (sel) activeChip = chip
            }
        }
        hScroll.addView(track)
        box.addView(hScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (activeChip != null) {
            hScroll.post { hScroll.scrollTo((activeChip.left - hScroll.width / 2 + activeChip.width / 2).coerceAtLeast(0), 0) }
        }
        val lpOuter = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpOuter.bottomMargin = dp(4)
        box.layoutParams = lpOuter
        return box
    }

    private fun roundedBg(fillHex: String, strokeHex: String, radiusDp: Int, strokeDp: Int = 1): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), Color.parseColor(strokeHex))
        }
    }

    private fun formatDeInt(v: Int): String = try { NumberFormat.getIntegerInstance(Locale.GERMANY).format(v) } catch (e: Exception) { v.toString() }
    private fun fmtKg(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else v.toString().replace(".", ",")
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun triggerVibration(durationMs: Long) {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vib != null && vib.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createOneShot(durationMs, 180))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(durationMs)
                }
            }
        } catch (e: Exception) {}
    }

    private fun triggerRestAlarmPattern() {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vib != null && vib.hasVibrator()) {
                val pattern = longArrayOf(0, 240, 110, 240, 110, 420)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        syncHandler.removeCallbacks(autoSyncRunnable)
        restTimer?.cancel(); wodTimer?.cancel(); gpsTimer?.cancel()
        try { sensorManager?.unregisterListener(this) } catch (e: Exception) {}
        try { locationManager?.removeUpdates(this) } catch (e: Exception) {}
        super.onDestroy()
    }
}
