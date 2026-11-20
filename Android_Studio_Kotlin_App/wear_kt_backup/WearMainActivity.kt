package eu.remk.coaching.wear

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * RE:MK Watch App (Wear OS 5 / Pixel Watch 4) – v33.0 Signature Pro
 * Vollständige Zusammenführung der RE:MK Watch UI-, Handhabungs- & Sync-Spezifikation (v35.8 Vertrag):
 * - 0-Scroll 2-Spalten-Satz-Cockpit (Seite 0) mit Drehkrone & 1-Tap Auto-Close Zahlenstrahl
 * - Automatisches Pausen-Timer-Ring-Overlay (Canvas-Ring + HR-Recovery < 122 BPM + 3-2-1 Vibration)
 * - 5 saubere Swipe- & 1-Tap-Screens (Satz | Plan/Tagesform/Ort | GPS & Tanaka-Puls | Ernährung/Wasser | Einkauf/Sync)
 * - Workout-Complete-Abschluss-Overlay mit Tonnage & 1-Tap-Gefühls-Check (Top / Ok / Schwer)
 * - Scroll-Lock-Guard (3,5 s), FOCUS_BLOCK_DESCENDANTS, 9833-kcal/Duplikat/Bodyweight-Bereinigung
 * - Live-Updates an alle 4 Kacheln + alle 4 Complication-Provider für das "RE:MK Iron Telemetry" System-Zifferblatt
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

    /**
     * Umlaufender OLED-Countdown-Ring für das automatische Pausen-Overlay (analog zum RE:MK Design-System)
     */
    inner class RestRingCanvasView(
        context: Context,
        var progressPct: Float = 1f,
        var ringColorHex: String = "#FF5E00"
    ) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#182030")
            strokeCap = Paint.Cap.ROUND
        }
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val size = min(width, height).toFloat()
            val stroke = dp(6).toFloat()
            trackPaint.strokeWidth = stroke
            arcPaint.strokeWidth = stroke
            arcPaint.color = Color.parseColor(ringColorHex)
            val pad = stroke / 2f + dp(3)
            rect.set(pad, pad, size - pad, size - pad)
            canvas.drawArc(rect, -90f, 360f, false, trackPaint)
            canvas.drawArc(rect, -90f, 360f * progressPct.coerceIn(0.02f, 1f), false, arcPaint)
        }
    }

    /**
     * Konflikt-freier Horizontal-Swipe-Container:
     * Fängt nur eindeutige horizontale Wischgesten (> 38dp und 1.6x horizontaler als vertikal) ab,
     * sodass Buttons und vertikale Listen niemals gestört werden!
     */
    inner class SmartSwipeFrameLayout(context: Context) : FrameLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var swipingHoriz = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (inlineRulerMode != "none" || restSecondsLeft > 0 || showWorkoutCompleteOverlay) {
                return false
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    swipingHoriz = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > dp(38) && abs(dx) > abs(dy) * 1.6f) {
                        swipingHoriz = true
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                val dx = ev.x - downX
                if (swipingHoriz && abs(dx) > dp(42)) {
                    triggerVibration(18)
                    activePage = if (dx < 0) (activePage + 1) % totalPages else (activePage - 1 + totalPages) % totalPages
                    renderCurrentScreen()
                    swipingHoriz = false
                    return true
                }
                swipingHoriz = false
            }
            return swipingHoriz || super.onTouchEvent(ev)
        }
    }

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var rootFrame: SmartSwipeFrameLayout

    // Aktiver Screen (0..4) + Overlays
    private var activePage = 0
    private val totalPages = 5
    private var showWorkoutCompleteOverlay = false
    private var workoutFeelingTag = "Top"

    // Inline Auto-Close Picker ("none", "kg", "reps") & Kronen-Fokus
    private var inlineRulerMode = "none"
    private var focusedField = "kg" // "kg" oder "reps" (wird durch Drehkrone auf Seite 0 gesteuert!)
    private var weightStep = 2.5f

    // Training State
    private var activeEnv = "gym" // "gym" | "homegym" | "bodyweight"
    private var activePartnerSlot = "p1"
    private var athleteAge = 36 // Für Tanaka-Formel: HFmax = 208 - 0.7 * Alter
    private var targetKcal = 2450
    private var manualSnackDeltaKcal = 0

    private var currentDayIdx = 0
    private var currentExIdx = 0
    private var currentSetIdx = 1
    private var unilateralPhaseRight = false
    private var todayMovedVolumeKg = 0
    private var readinessMode = 0 // 0 = 100%, 1 = -5%, 2 = -15%
    private var currentRir = 2

    // Undo-Speicher
    private var canUndoLastSet = false
    private var undoDayIdx = 0
    private var undoExIdx = 0
    private var undoSetIdx = 1
    private var undoKg = 40.0f
    private var undoReps = 10
    private var showUndoBannerUntilMs = 0L

    private var liveKg = 40.0f
    private var liveReps = 10

    // Pausen-Timer Auto-Overlay + HR-Recovery
    private var restTimer: CountDownTimer? = null
    private var restSecondsLeft = 0
    private var restTotalSeconds = 90
    private var restEndTimestampMs = 0L
    private var hrRecoveryNotifiedThisRest = false
    private val hrRecoveryThresholdBpm = 122

    private var restRingView: RestRingCanvasView? = null
    private var restCountdownTv: TextView? = null
    private var restHrBadgeTv: TextView? = null
    private var cardioLiveTv: TextView? = null
    private var statusLiveTv: TextView? = null

    // Sensoren & GPS (Seite 2)
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
    private var activeSportLabel = "🏃 Laufen"

    // Smart-Diff Cloud-Sync mit 3,5s Scroll-Lock-Guard
    private val syncHandler = Handler(Looper.getMainLooper())
    private var isSyncInProgress = false
    private var lastCloudSyncOk = true
    private var lastTouchTimestampMs = 0L
    private var lastCloudDataSignature = ""

    private val autoSyncRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                if (activePage == 0 && restSecondsLeft <= 0 && !showWorkoutCompleteOverlay) {
                    val curEx = trainingDays[currentDayIdx].second[currentExIdx]
                    val pendingCnt = WearOfflineSyncQueue.getPendingCount(this@WearMainActivity)
                    val liveTxt = if (pendingCnt > 0) "⚡$pendingCnt" else if (lastCloudSyncOk) "● Live" else "● Offline"
                    val setDots = (1..curEx.sets).joinToString("") { s ->
                        when {
                            s <= curEx.doneSets -> "●"
                            s == currentSetIdx -> "🟠"
                            else -> "○"
                        }
                    }
                    val nowClock = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())
                    statusLiveTv?.text = "$nowClock • $liveTxt • ❤️$liveBpm • $setDots"
                }
                val idleMs = System.currentTimeMillis() - lastTouchTimestampMs
                if (idleMs > 3500L) {
                    triggerBackgroundCloudPull(silent = true)
                }
                syncHandler.postDelayed(this, 9500L)
            }
        }
    }

    private var trainingDays = mutableListOf(
        "Tag 1 Push" to mutableListOf(
            WatchExercise("p1", "Schrägbankdrücken", 3, 10, 40.0f, "40 × 10"),
            WatchExercise("pu2", "Latzug breit", 3, 10, 65.0f, "65 × 10"),
            WatchExercise("p8", "Seitheben", 3, 12, 12.5f, "12,5 × 12"),
            WatchExercise("l1", "Kniebeugen", 3, 10, 90.0f, "87,5 × 10")
        ),
        "Tag 2 Pull" to mutableListOf(
            WatchExercise("pu7", "Rumänisches Kreuzheben", 3, 10, 90.0f, "87,5 × 10"),
            WatchExercise("pu1", "Rudern am Kabelzug", 3, 10, 70.0f, "67,5 × 10"),
            WatchExercise("l6", "Bulgarian Split Squats", 3, 10, 20.0f, "20 × 10", unilateral = true),
            WatchExercise("c1", "Plank Hold", 3, 45, 0.0f, "BW × 45s")
        ),
        "Tag 3 Hybrid" to mutableListOf(
            WatchExercise("p2", "Bankdrücken (LH)", 3, 8, 80.0f, "77,5 × 8"),
            WatchExercise("p5", "KH-Schulterdrücken", 3, 12, 22.5f, "22,5 × 12"),
            WatchExercise("a1", "SZ-Curls & Pushdowns", 3, 12, 32.5f, "32,5 × 12")
        ),
        "Tag 4 Park" to mutableListOf(
            WatchExercise("ca1", "Klimmzüge", 3, 8, 0.0f, "BW × 8"),
            WatchExercise("ca2", "Dips", 3, 12, 0.0f, "BW × 12"),
            WatchExercise("ca3", "Pistol Squats", 3, 8, 0.0f, "BW × 8", unilateral = true)
        ),
        "Tag 5 Athletik" to mutableListOf(
            WatchExercise("at1", "Box Jumps", 3, 6, 0.0f, "BW × 6"),
            WatchExercise("at2", "Ab Wheel Rollout", 3, 15, 0.0f, "BW × 15"),
            WatchExercise("at4", "Mobility Flow", 2, 45, 0.0f, "BW × 45s")
        )
    )

    private val dailyMeals = mutableListOf(
        WatchMeal("Frühstück", "Skyr-Bowl", 520, false),
        WatchMeal("Mittag", "Hähnchen-Reis", 740, false),
        WatchMeal("Snack", "Quark & Beeren", 390, false),
        WatchMeal("Abend", "Ofen-Lachs & Kartoffeln", 680, false)
    )

    private val shoppingItems = mutableListOf(
        WatchShopItem(0, "1 kg Skyr", false),
        WatchShopItem(1, "500 g Haferflocken", false),
        WatchShopItem(2, "1,2 kg Hähnchenbrust", false),
        WatchShopItem(3, "800 g Basmatireis", false)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lastTouchTimestampMs = System.currentTimeMillis()

        prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        checkAndPerformMidnightDailyReset()
        loadPersistedWatchState()

        rootFrame = SmartSwipeFrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#05070B"))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(rootFrame)
        rootFrame.requestFocus()

        val reqPage = intent?.getIntExtra("start_page", -1) ?: -1
        if (reqPage in 0 until totalPages) activePage = reqPage

        renderCurrentScreen()
        rootFrame.postDelayed({ initWatchHardwareSensors() }, 350L)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        lastTouchTimestampMs = System.currentTimeMillis()
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Drehkrone (Rotary Input):
     * - Auf Seite 0 ändert sie mit fühlbarem Haptik-Tick (CLOCK_TICK) direkt den fokussierten Wert
     *   (kg in ±2.5 / ±1.25 Schritten oder Wdh. in ±1 Schritten).
     * - Auf den Seiten 1..4 scrollt sie sauber die jeweilige Liste.
     */
    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        if (ev != null && ev.action == MotionEvent.ACTION_SCROLL &&
            ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
        ) {
            lastTouchTimestampMs = System.currentTimeMillis()
            val delta = -ev.getAxisValue(MotionEvent.AXIS_SCROLL)
            val dir = if (delta > 0) 1 else -1
            rootFrame.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

            if (activePage == 0 && restSecondsLeft <= 0 && !showWorkoutCompleteOverlay) {
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

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val reqPage = intent?.getIntExtra("start_page", -1) ?: -1
        if (reqPage in 0 until totalPages) {
            activePage = reqPage
            renderCurrentScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        lastTouchTimestampMs = System.currentTimeMillis()
        checkAndPerformMidnightDailyReset()
        loadPersistedWatchState()
        renderCurrentScreen()
        syncHandler.removeCallbacks(autoSyncRunnable)
        syncHandler.postDelayed(autoSyncRunnable, 1800L)
    }

    override fun onPause() {
        super.onPause()
        syncHandler.removeCallbacks(autoSyncRunnable)
        saveWatchStateAndRefreshAll()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            inlineRulerMode != "none" -> {
                inlineRulerMode = "none"
                renderCurrentScreen()
            }
            restSecondsLeft > 0 -> {
                stopRestCountdown()
            }
            showWorkoutCompleteOverlay -> {
                showWorkoutCompleteOverlay = false
                renderCurrentScreen()
            }
            activePage != 0 -> {
                activePage = 0
                renderCurrentScreen()
            }
            else -> {
                renderCurrentScreen()
            }
        }
    }

    // =========================================================================
    // HAUPT-RENDERER: 5 SWIPE-/TAP-SCREENS + AUTO-OVERLAYS
    // =========================================================================
    private fun renderCurrentScreen() {
        if (!::rootFrame.isInitialized || isFinishing || isDestroyed) return
        rootFrame.removeAllViews()
        restRingView = null
        restCountdownTv = null
        restHrBadgeTv = null
        cardioLiveTv = null

        // 1. AUTO-OVERLAY: WORKOUT COMPLETE
        if (showWorkoutCompleteOverlay) {
            renderWorkoutCompleteOverlay()
            return
        }

        // 2. AUTO-OVERLAY AUF SEITE 0: PAUSEN-TIMER MIT UMLAUFENDEM RING
        if (activePage == 0 && restSecondsLeft > 0) {
            renderAutoRestTimerOverlay()
            return
        }

        when (activePage) {
            0 -> renderPage0ZeroScrollActiveSet()
            1 -> renderPage1PlanAndReadiness()
            2 -> renderPage2GpsAndTanakaPulse()
            3 -> renderPage3NutritionAndWater()
            4 -> renderPage4ShoppingAndSync()
        }
    }

    // =========================================================================
    // SEITE 0 (START): 0-SCROLL 2-SPALTEN SATZ-COCKPIT (EXAKT WIE MOCKUP!)
    // =========================================================================
    private fun renderPage0ZeroScrollActiveSet() {
        val dayPair = trainingDays[currentDayIdx]
        val exList = dayPair.second
        val curEx = exList[currentExIdx.coerceIn(0, (exList.size - 1).coerceAtLeast(0))]
        val isTimedEx = isIsometricOrTimedExercise(curEx.name)

        val (sanKg, sanReps) = sanitizeExerciseWeightAndReps(curEx.name, liveKg, liveReps)
        liveKg = sanKg
        liveReps = sanReps
        curEx.kg = sanKg
        curEx.targetReps = sanReps

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#05070B"))
            // 11% Runddisplay-Inset-Schutz (oben/unten/seitlich)
            setPadding(dp(16), dp(10), dp(16), dp(12))
        }

        // 1. Kompakte 1-Zeilen-Statusleiste + 5-Seiten-Schnellwahl-Indikator
        val pendingCnt = WearOfflineSyncQueue.getPendingCount(this)
        val liveTxt = if (pendingCnt > 0) "⚡$pendingCnt" else if (lastCloudSyncOk) "● Live" else "● Offline"
        val setDots = (1..curEx.sets).joinToString("") { s ->
            when {
                s <= curEx.doneSets -> "●"
                s == currentSetIdx -> "🟠"
                else -> "○"
            }
        }
        statusLiveTv = TextView(this).apply {
            val nowClock = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())
            text = "$nowClock • $liveTxt • ❤️$liveBpm • $setDots"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 10.5f
            setSingleLine(true)
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), dp(2))
            setOnClickListener {
                // 1-Tap auf Statusleiste springt sauber durch die Seiten (Alternative zum Wischen)
                triggerVibration(16)
                activePage = (activePage + 1) % totalPages
                renderCurrentScreen()
            }
        }
        container.addView(statusLiveTv)

        // 2. Übungs-Switcher: < 1/6 Schrägbankdrücken > + Vorwochen-Info
        val exSwitchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val prevBtn = TextView(this).apply {
            text = "‹"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 20f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(1), dp(8), dp(1))
            setOnClickListener {
                triggerVibration(18)
                switchExerciseSafely(currentExIdx - 1)
            }
        }
        val uniBadge = if (curEx.unilateral) (if (unilateralPhaseRight) " (R)" else " (L)") else ""
        val exNameTv = TextView(this).apply {
            text = "${currentExIdx + 1}/${exList.size} ${curEx.name}$uniBadge"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 13.5f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                triggerVibration(18)
                activePage = 1
                renderCurrentScreen()
            }
        }
        val nextBtn = TextView(this).apply {
            text = "›"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 20f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(1), dp(8), dp(1))
            setOnClickListener {
                triggerVibration(18)
                switchExerciseSafely(currentExIdx + 1)
            }
        }
        exSwitchRow.addView(prevBtn, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT))
        exSwitchRow.addView(exNameTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        exSwitchRow.addView(nextBtn, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(exSwitchRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        container.addView(TextView(this).apply {
            text = "Vorher: ${curEx.prevRef}"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            setSingleLine(true)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(5))
        })

        // 3. Wenn Auto-Close Picker offen ist -> Kompakten Zahlenstrahl direkt inline anzeigen!
        if (inlineRulerMode == "kg") {
            container.addView(buildAutoCloseNumberLine(isKg = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else if (inlineRulerMode == "reps") {
            container.addView(buildAutoCloseNumberLine(isKg = false), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            // 2-SPALTEN-WERTZEILE NEBENEINANDER: [ - 40 kg + ] | [ - 10 Wdh. + ]
            val twoColRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            // Linke Karte: GEWICHT (mit aktivem orangenem Fokus-Rahmen #FF5E00)
            val kgCard = buildValueStepperCard(
                valueStr = if (liveKg <= 0f) "BW" else fmtKg(liveKg),
                unitStr = if (liveKg <= 0f) "0 kg" else "kg",
                isFocused = (focusedField == "kg"),
                accentHex = "#FF5E00",
                onMinus = {
                    focusedField = "kg"
                    stepWeight(-weightStep)
                },
                onCenterTap = {
                    triggerVibration(22)
                    focusedField = "kg"
                    inlineRulerMode = "kg"
                    renderCurrentScreen()
                },
                onPlus = {
                    focusedField = "kg"
                    stepWeight(weightStep)
                }
            )

            // Rechte Karte: WIEDERHOLUNGEN / SEKUNDEN
            val repsCard = buildValueStepperCard(
                valueStr = "$liveReps",
                unitStr = if (isTimedEx) "Sek." else "Wdh.",
                isFocused = (focusedField == "reps"),
                accentHex = "#10B981",
                onMinus = {
                    focusedField = "reps"
                    stepReps(if (isTimedEx) -5 else -1)
                },
                onCenterTap = {
                    triggerVibration(22)
                    focusedField = "reps"
                    inlineRulerMode = "reps"
                    renderCurrentScreen()
                },
                onPlus = {
                    focusedField = "reps"
                    stepReps(if (isTimedEx) 5 else 1)
                }
            )

            twoColRow.addView(kgCard, LinearLayout.LayoutParams(0, dp(64), 1f).apply { rightMargin = dp(4) })
            twoColRow.addView(repsCard, LinearLayout.LayoutParams(0, dp(64), 1f).apply { leftMargin = dp(4) })
            container.addView(twoColRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(7)
            })
        }

        // 4. Großer runder Hauptbutton unten: ✓ SATZ X ABHAKEN (mind. 48dp Touch-Höhe!)
        val showUndo = canUndoLastSet && (System.currentTimeMillis() < showUndoBannerUntilMs)
        val btnText = if (curEx.unilateral && !unilateralPhaseRight) {
            "✓ LINKS FERTIG ➔ RECHTS"
        } else {
            "✓ SATZ $currentSetIdx ABHAKEN"
        }

        val logBtn = Button(this).apply {
            text = btnText
            setTextColor(Color.parseColor("#05070B"))
            textSize = 13.5f
            setSingleLine(true)
            setTypeface(null, Typeface.BOLD)
            background = roundedBg("#10B981", "#10B981", 24)
            minimumHeight = dp(44)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                completeCurrentSet()
            }
        }
        container.addView(logBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))

        // Dezente 5-Punkte-Seitenleiste ganz unten (oder kurzes Undo-Banner nach Satz)
        if (showUndo) {
            val undoTv = TextView(this).apply {
                text = "↩️ Satz rückgängig"
                setTextColor(Color.parseColor("#EF4444"))
                textSize = 10.5f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(8), dp(3), dp(8), 0)
                setOnClickListener { undoLastCompletedSet() }
            }
            container.addView(undoTv)
        } else {
            container.addView(buildBottomPageDotsStrip())
        }

        rootFrame.addView(
            container,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    private fun buildValueStepperCard(
        valueStr: String,
        unitStr: String,
        isFocused: Boolean,
        accentHex: String,
        onMinus: () -> Unit,
        onCenterTap: () -> Unit,
        onPlus: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg("#121722", if (isFocused) accentHex else "#243047", 16, strokeDp = if (isFocused) 2 else 1)
            setPadding(dp(3), dp(4), dp(3), dp(4))

            val minusTv = TextView(context).apply {
                text = "−"
                setTextColor(Color.parseColor(if (isFocused) accentHex else "#94A3B8"))
                textSize = 18f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setOnClickListener {
                    triggerVibration(18)
                    onMinus()
                }
            }

            val centerCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { onCenterTap() }
            }
            centerCol.addView(TextView(context).apply {
                text = valueStr
                setTextColor(Color.parseColor("#F8FAFC"))
                textSize = 20f
                setSingleLine(true)
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
            })
            centerCol.addView(TextView(context).apply {
                text = unitStr
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 10.5f
                setSingleLine(true)
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
            })

            val plusTv = TextView(context).apply {
                text = "+"
                setTextColor(Color.parseColor(if (isFocused) accentHex else "#94A3B8"))
                textSize = 18f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setOnClickListener {
                    triggerVibration(18)
                    onPlus()
                }
            }

            addView(minusTv, LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(centerCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(plusTv, LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun buildAutoCloseNumberLine(isKg: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBg("#121722", if (isKg) "#FF5E00" else "#10B981", 16, strokeDp = 2)
            setPadding(dp(6), dp(5), dp(6), dp(5))
        }

        val hScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        var activeChip: View? = null
        if (isKg) {
            val stops = mutableListOf(0f, 2.5f, 5f, 7.5f, 10f, 12.5f, 15f, 17.5f)
            var w = 20f
            while (w <= 120f) { stops.add(w); w += 2.5f }
            var wb = 125f
            while (wb <= 200f) { stops.add(wb); wb += 5f }

            for (v in stops) {
                val sel = abs(v - liveKg) < 0.2f
                val chip = TextView(this).apply {
                    text = if (v == 0f) "BW" else fmtKg(v)
                    setTextColor(Color.parseColor(if (sel) "#05070B" else "#F8FAFC"))
                    textSize = if (sel) 15f else 13f
                    setSingleLine(true)
                    gravity = Gravity.CENTER
                    setTypeface(null, Typeface.BOLD)
                    background = roundedBg(if (sel) "#FF5E00" else "#1A2233", if (sel) "#FFFFFF" else "#2A364F", 11)
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    setOnClickListener {
                        triggerVibration(22)
                        setExactWeight(v, autoClose = true)
                    }
                }
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(dp(2), 0, dp(2), 0)
                track.addView(chip, lp)
                if (sel) activeChip = chip
            }
        } else {
            val stops = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 15, 16, 18, 20, 25, 30, 45, 60)
            for (r in stops) {
                val sel = (r == liveReps)
                val chip = TextView(this).apply {
                    text = "$r"
                    setTextColor(Color.parseColor(if (sel) "#05070B" else "#F8FAFC"))
                    textSize = if (sel) 15f else 13f
                    setSingleLine(true)
                    gravity = Gravity.CENTER
                    setTypeface(null, Typeface.BOLD)
                    background = roundedBg(if (sel) "#10B981" else "#1A2233", if (sel) "#FFFFFF" else "#2A364F", 11)
                    setPadding(dp(11), dp(7), dp(11), dp(7))
                    setOnClickListener {
                        triggerVibration(22)
                        setExactReps(r, autoClose = true)
                    }
                }
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(dp(2), 0, dp(2), 0)
                track.addView(chip, lp)
                if (sel) activeChip = chip
            }
        }

        hScroll.addView(track)
        box.addView(hScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (activeChip != null) {
            hScroll.post {
                val sx = (activeChip.left - hScroll.width / 2 + activeChip.width / 2).coerceAtLeast(0)
                hScroll.scrollTo(sx, 0)
            }
        }

        val hintRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        }
        if (isKg) {
            hintRow.addView(buildSmallPill("±${fmtKg(weightStep)}kg", "#182235", "#38BDF8") {
                weightStep = if (weightStep == 2.5f) 1.25f else 2.5f
                renderCurrentScreen()
            })
        }
        hintRow.addView(buildSmallPill("✕ Schließen", "#182235", "#94A3B8") {
            inlineRulerMode = "none"
            renderCurrentScreen()
        })
        box.addView(hintRow)

        val lpOuter = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpOuter.bottomMargin = dp(5)
        box.layoutParams = lpOuter
        return box
    }

    // =========================================================================
    // AUTO-OVERLAY AUF SEITE 0: PAUSEN-TIMER MIT GLOW-RING & HR-RECOVERY
    // =========================================================================
    private fun renderAutoRestTimerOverlay() {
        val isRec = (liveBpm < hrRecoveryThresholdBpm)
        val pct = (restSecondsLeft.toFloat() / restTotalSeconds.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        val ringHex = if (restSecondsLeft <= 5) "#EF4444" else if (isRec) "#10B981" else "#FF5E00"

        restRingView = RestRingCanvasView(this, pct, ringHex)
        rootFrame.addView(
            restRingView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(18), dp(22), dp(16))
        }

        content.addView(TextView(this).apply {
            text = "SATZPAUSE"
            setTextColor(Color.parseColor("#FF5E00"))
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                if (canUndoLastSet) undoLastCompletedSet()
            }
        })

        val m = (restSecondsLeft / 60).toString().padStart(2, '0')
        val s = (restSecondsLeft % 60).toString().padStart(2, '0')
        restCountdownTv = TextView(this).apply {
            text = "$m:$s"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 34f
            gravity = Gravity.CENTER
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
        content.addView(restCountdownTv)

        restHrBadgeTv = TextView(this).apply {
            text = if (isRec) "💚 $liveBpm BPM • Bereit" else "❤️ $liveBpm BPM • Erholung"
            setTextColor(Color.parseColor(if (isRec) "#10B981" else "#FBBF24"))
            textSize = 11.5f
            setSingleLine(true)
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            background = roundedBg(if (isRec) "#0D261C" else "#1E1910", if (isRec) "#10B981" else "#FBBF24", 12)
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }
        content.addView(restHrBadgeTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })

        val adjustRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        adjustRow.addView(buildWeightedBtn("-15s", "#121722", "#F8FAFC", 12f) {
            restSecondsLeft = (restSecondsLeft - 15).coerceAtLeast(5)
            startRestCountdown(restSecondsLeft)
        }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(4) })

        adjustRow.addView(buildWeightedBtn("+30s", "#121722", "#F8FAFC", 12f) {
            restSecondsLeft += 30
            restTotalSeconds = maxOf(restTotalSeconds, restSecondsLeft)
            startRestCountdown(restSecondsLeft)
        }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { leftMargin = dp(4) })

        content.addView(adjustRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })

        val skipBtn = buildWeightedBtn("Überspringen", "#FF5E00", "#05070B", 13f) {
            triggerVibration(20)
            stopRestCountdown()
        }
        content.addView(skipBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))

        rootFrame.addView(
            content,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    // =========================================================================
    // SEITE 1: ÜBUNGS- & TAG-AUSWAHL (T1-T5, READINESS-AMPEL, BESETZT, ENV)
    // =========================================================================
    private fun renderPage1PlanAndReadiness() {
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            setBackgroundColor(Color.parseColor("#05070B"))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(dp(16), dp(14), dp(16), dp(26))
        }

        // 1. Tag-Pillen (T1 - T5)
        val dayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val dayCount = min(trainingDays.size, 5)
        for (i in 0 until dayCount) {
            val sel = (i == currentDayIdx)
            val pill = buildWeightedBtn("T${i + 1}", if (sel) "#FF5E00" else "#121722", if (sel) "#05070B" else "#94A3B8", 11.5f) {
                triggerVibration(20)
                currentDayIdx = i
                switchExerciseSafely(0)
            }
            dayRow.addView(pill, LinearLayout.LayoutParams(0, dp(30), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        box.addView(dayRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(5)
        })

        // 2. Readiness-Ampel (100% / -5% / -15%)
        val readRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val rSpecs = listOf(
            Triple(0, "100%", "#10B981"),
            Triple(1, "-5%", "#FBBF24"),
            Triple(2, "-15%", "#EF4444")
        )
        for ((mode, lbl, col) in rSpecs) {
            val sel = (readinessMode == mode)
            val btn = buildWeightedBtn(
                lbl,
                if (sel) col else "#121722",
                if (sel) "#05070B" else col,
                11f
            ) {
                triggerVibration(22)
                applyReadinessAutoregulation(mode)
            }
            readRow.addView(btn, LinearLayout.LayoutParams(0, dp(30), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        box.addView(readRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })

        // 3. Liste aller Tagesübungen mit Satz-Gedächtnis (doneSets)
        val exList = trainingDays[currentDayIdx].second
        exList.forEachIndexed { idx, ex ->
            val isCur = (idx == currentExIdx)
            val isDone = (ex.doneSets >= ex.sets)
            val statusTxt = if (isDone) "${ex.sets}/${ex.sets} ✓" else "${ex.doneSets}/${ex.sets}"
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBg("#121722", if (isCur) "#FF5E00" else if (isDone) "#10B981" else "#232E44", 14, strokeDp = if (isCur) 2 else 1)
                setPadding(dp(10), dp(7), dp(10), dp(7))
                setOnClickListener {
                    triggerVibration(20)
                    switchExerciseSafely(idx)
                    activePage = 0
                    renderCurrentScreen()
                }
            }
            row.addView(TextView(this).apply {
                text = "${idx + 1}. ${ex.name}"
                setTextColor(Color.parseColor("#F8FAFC"))
                textSize = 12f
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(null, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(TextView(this).apply {
                text = statusTxt
                setTextColor(Color.parseColor(if (isDone) "#10B981" else if (isCur) "#FF5E00" else "#94A3B8"))
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
            })
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            })
        }

        // 4. Besetzt & Trainingsort-Switch (WATCH_SWITCH_ENV: gym | homegym | bodyweight)
        val envLabel = when (activeEnv) {
            "homegym" -> "🏠 Home"
            "bodyweight" -> "🤸 BW"
            else -> "🏋️ Gym"
        }
        val actRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actRow.addView(buildWeightedBtn("⏳ Besetzt", "#1F1A10", "#FBBF24", 11.5f) {
            postponeOccupiedExercise()
        }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(3) })

        actRow.addView(buildWeightedBtn(envLabel, "#121722", "#38BDF8", 11.5f) {
            triggerVibration(22)
            activeEnv = when (activeEnv) {
                "gym" -> "homegym"
                "homegym" -> "bodyweight"
                else -> "gym"
            }
            prefs.edit().putString("active_env", activeEnv).apply()
            WearOfflineSyncQueue.enqueueEvent(
                this,
                "WATCH_SWITCH_ENV",
                "targetEnv=$activeEnv",
                mapOf("targetEnv" to activeEnv)
            )
            Toast.makeText(this, "Ort gewechselt: $envLabel", Toast.LENGTH_SHORT).show()
            renderCurrentScreen()
        }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { leftMargin = dp(3) })

        box.addView(actRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        })

        box.addView(buildBottomPageDotsStrip())
        scroll.addView(box)
        rootFrame.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scroll.post { scroll.scrollTo(0, 0) }
    }

    // =========================================================================
    // SEITE 2: GPS & LIVE-PULS (TANAKA-FORMEL: HFmax = 208 - 0.7 * ALTER)
    // =========================================================================
    private fun renderPage2GpsAndTanakaPulse() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#05070B"))
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }

        val sportBtn = buildWeightedBtn("$activeSportLabel ⟳", "#121722", "#38BDF8", 12f) {
            triggerVibration(18)
            val list = listOf("🏃 Laufen", "🚴 Rad", "⚡ Hyrox")
            activeSportLabel = list[(list.indexOf(activeSportLabel) + 1) % list.size]
            renderCurrentScreen()
        }
        box.addView(sportBtn, LinearLayout.LayoutParams(dp(124), dp(32)).apply { bottomMargin = dp(5) })

        val (zLbl, zCol) = getTanakaHeartRateZone(liveBpm)
        val m = (gpsSeconds / 60).toString().padStart(2, '0')
        val s = (gpsSeconds % 60).toString().padStart(2, '0')

        cardioLiveTv = TextView(this).apply {
            text = "❤️ $liveBpm BPM\n$zLbl\n📡 ${String.format("%.2f", gpsDistKm)} km  •  ⏱️ $m:$s"
            setTextColor(Color.parseColor(zCol))
            textSize = 15.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(8))
        }
        box.addView(cardioLiveTv)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        btnRow.addView(buildWeightedBtn(
            if (gpsRunning) "⏸️ Pause" else "▶️ Start",
            if (gpsRunning) "#FF5E00" else "#10B981",
            "#05070B",
            12.5f
        ) {
            triggerVibration(30)
            toggleGpsTracking()
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(4) })

        btnRow.addView(buildWeightedBtn("💾 Speichern", "#121722", "#38BDF8", 12f) {
            triggerVibration(35)
            val avgBpm = if (bpmSamples > 0) (bpmSum / bpmSamples).toInt() else liveBpm
            WearOfflineSyncQueue.enqueueEvent(
                this,
                "WATCH_CARDIO_SAVED",
                "sport=$activeSportLabel&durationSec=$gpsSeconds&distKm=${String.format(Locale.US, "%.2f", gpsDistKm)}&avgBpm=$avgBpm",
                mapOf(
                    "sport" to activeSportLabel,
                    "durationSec" to gpsSeconds,
                    "distKm" to ((gpsDistKm * 100f).roundToInt() / 100.0),
                    "avgBpm" to avgBpm
                )
            )
            Toast.makeText(this, "✅ Cardio gespeichert & synchronisiert!", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(4) })

        box.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(buildBottomPageDotsStrip())
        rootFrame.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun getTanakaHeartRateZone(bpm: Int): Pair<String, String> {
        val hfMax = (208.0 - 0.7 * athleteAge).roundToInt().coerceIn(150, 205)
        val pct = (bpm.toFloat() / hfMax.toFloat()) * 100f
        return when {
            pct < 60f -> "Z1 Regeneration (${pct.roundToInt()}%)" to "#38BDF8"
            pct < 70f -> "Z2 Grundlagen (${pct.roundToInt()}%)" to "#10B981"
            pct < 80f -> "Z3 Aerob (${pct.roundToInt()}%)" to "#FBBF24"
            pct < 90f -> "Z4 Schwelle (${pct.roundToInt()}%)" to "#FF5E00"
            else -> "Z5 Maximum (${pct.roundToInt()}%)" to "#EF4444"
        }
    }

    // =========================================================================
    // SEITE 3: ERNÄHRUNG & WASSER (EXAKT WIE MOCKUP – PERSÖNLICHE KCAL!)
    // =========================================================================
    private fun renderPage3NutritionAndWater() {
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            setBackgroundColor(Color.parseColor("#05070B"))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(dp(16), dp(14), dp(16), dp(26))
        }

        val drankL = prefs.getFloat("drank_liters", 0.0f)
        val openKcal = getPersonalOpenKcal()

        box.addView(TextView(this).apply {
            text = "ERNÄHRUNG & WASSER"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = "$openKcal kcal"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 21f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = "offen (Ziel: $targetKcal)"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        })

        // Zwei große Aktions-Buttons: [+250 ml / 1,75 L] | [+150 kcal / Snack]
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val waterBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBg("#38BDF8", "#38BDF8", 18)
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setOnClickListener { addWaterLiters(0.25f) }
        }
        waterBtn.addView(TextView(this).apply {
            text = "+250 ml"
            setTextColor(Color.parseColor("#05070B"))
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
        })
        waterBtn.addView(TextView(this).apply {
            text = "${String.format("%.2f", drankL).replace(".", ",")} L"
            setTextColor(Color.parseColor("#05070B"))
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
        })

        val snackBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBg("#121722", "#28354D", 18)
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setOnClickListener {
                triggerVibration(20)
                manualSnackDeltaKcal += 150
                prefs.edit().putInt("manual_snack_delta_kcal", manualSnackDeltaKcal).apply()
                saveWatchStateAndRefreshAll()
                renderCurrentScreen()
            }
        }
        snackBtn.addView(TextView(this).apply {
            text = "+150 kcal"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
        })
        snackBtn.addView(TextView(this).apply {
            text = "Snack"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
        })

        actionRow.addView(waterBtn, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(4) })
        actionRow.addView(snackBtn, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(4) })
        box.addView(actionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(7)
        })

        // Abhakbare Tagesmahlzeiten
        dailyMeals.forEachIndexed { idx, meal ->
            val mBtn = TextView(this).apply {
                text = "${if (meal.done) "✓ " else "○ "}${meal.slot} • ${meal.title.take(15)}"
                setTextColor(Color.parseColor(if (meal.done) "#10B981" else "#F8FAFC"))
                textSize = 11.5f
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                background = roundedBg(if (meal.done) "#0D241B" else "#121722", if (meal.done) "#10B981" else "#232E44", 13)
                setPadding(dp(8), dp(7), dp(8), dp(7))
                setOnClickListener {
                    triggerVibration(22)
                    meal.done = !meal.done
                    prefs.edit().putBoolean("meal_done_$idx", meal.done).apply()
                    saveWatchStateAndRefreshAll()
                    WearOfflineSyncQueue.enqueueEvent(
                        this@WearMainActivity,
                        "WATCH_MEAL_TOGGLED",
                        "slotIdx=$idx&done=${if (meal.done) 1 else 0}",
                        mapOf("slotIdx" to idx, "done" to meal.done)
                    )
                    renderCurrentScreen()
                }
            }
            box.addView(mBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            })
        }

        box.addView(buildBottomPageDotsStrip())
        scroll.addView(box)
        rootFrame.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scroll.post { scroll.scrollTo(0, 0) }
    }

    // =========================================================================
    // SEITE 4: EINKAUF & SYNC (ERLEDIGTE SOFORT AUSGEBLENDET + SYNC-ZEITSTEMPEL)
    // =========================================================================
    private fun renderPage4ShoppingAndSync() {
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            setBackgroundColor(Color.parseColor("#05070B"))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(dp(16), dp(14), dp(16), dp(26))
        }

        val openItems = shoppingItems.filter { !it.done }
        box.addView(TextView(this).apply {
            text = "🛒 ${openItems.size} Artikel offen"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(5))
        })

        if (openItems.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "✅ Alles im Einkaufskorb!"
                setTextColor(Color.parseColor("#10B981"))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(8))
            })
        } else {
            for (item in openItems) {
                val sBtn = TextView(this).apply {
                    text = "○ ${item.title}"
                    setTextColor(Color.parseColor("#F8FAFC"))
                    textSize = 12f
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setTypeface(null, Typeface.BOLD)
                    background = roundedBg("#121722", "#26344D", 13)
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    setOnClickListener {
                        triggerVibration(20)
                        item.done = true
                        prefs.edit().putBoolean("shop_done_${item.origIdx}", true).apply()
                        saveWatchStateAndRefreshAll()
                        WearOfflineSyncQueue.enqueueEvent(
                            this@WearMainActivity,
                            "WATCH_SHOP_TOGGLED",
                            "itemTitle=${item.title}&done=1",
                            mapOf("itemTitle" to item.title, "done" to true, "idx" to item.origIdx)
                        )
                        renderCurrentScreen()
                    }
                }
                box.addView(sBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(4)
                })
            }
        }

        val lastTs = WearOfflineSyncQueue.getLastSyncTimestamp(this)
        val tsStr = if (lastTs > 0L) SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(lastTs)) else "noch offen"
        val curKey = WearOfflineSyncQueue.getOrCreateSyncKey(this)

        val syncBtn = buildWeightedBtn(
            if (isSyncInProgress) "⏳ Synchronisiere..." else "🔄 Jetzt synchronisieren",
            "#38BDF8",
            "#05070B",
            12.5f
        ) {
            triggerVibration(25)
            triggerBackgroundCloudPull(silent = false)
        }
        box.addView(syncBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply {
            topMargin = dp(4)
            bottomMargin = dp(3)
        })

        box.addView(TextView(this).apply {
            text = "Sync: $tsStr • Key: $curKey (${activePartnerSlot.uppercase()})"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        })

        // 1-Tap Key- & Partner-Wechsel ohne Mini-Tastatur!
        val keyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        keyRow.addView(buildWeightedBtn("👑 26011990", "#121722", "#FF5E00", 10.5f) {
            WearOfflineSyncQueue.setSyncKey(this, "26011990")
            triggerBackgroundCloudPull(silent = false)
        }, LinearLayout.LayoutParams(0, dp(30), 1f).apply { rightMargin = dp(3) })
        keyRow.addView(buildWeightedBtn("🔑 Key: 1", "#121722", "#38BDF8", 10.5f) {
            WearOfflineSyncQueue.setSyncKey(this, "1")
            triggerBackgroundCloudPull(silent = false)
        }, LinearLayout.LayoutParams(0, dp(30), 1f).apply { leftMargin = dp(3) })
        box.addView(keyRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        box.addView(buildBottomPageDotsStrip())
        scroll.addView(box)
        rootFrame.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scroll.post { scroll.scrollTo(0, 0) }
    }

    // =========================================================================
    // ABSCHLUSS-OVERLAY: WORKOUT COMPLETE (TONNAGE + 1-TAP GEFÜHLS-CHECK)
    // =========================================================================
    private fun renderWorkoutCompleteOverlay() {
        val exList = trainingDays[currentDayIdx].second
        val totalSets = exList.sumOf { it.sets }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#05070B"))
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        box.addView(TextView(this).apply {
            text = "🏆 WORKOUT COMPLETE"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = "✅ $totalSets / $totalSets Sätze\n🔥 $todayMovedVolumeKg kg Tonnage"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 15.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(7))
        })

        // 1-Tap Gefühls-Check: Top / Ok / Schwer
        val feelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for ((tag, col) in listOf("Top" to "#10B981", "Ok" to "#38BDF8", "Schwer" to "#FF5E00")) {
            val sel = (workoutFeelingTag == tag)
            feelRow.addView(buildWeightedBtn(tag, if (sel) col else "#121722", if (sel) "#05070B" else col, 11.5f) {
                triggerVibration(18)
                workoutFeelingTag = tag
                renderCurrentScreen()
            }, LinearLayout.LayoutParams(0, dp(32), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        box.addView(feelRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })

        val pushBtn = buildWeightedBtn("☁️ Speichern & Syncen", "#10B981", "#05070B", 13f) {
            triggerVibration(35)
            WearOfflineSyncQueue.enqueueEvent(
                this,
                "WATCH_WORKOUT_COMPLETED",
                "dayIdx=$currentDayIdx&tonnageKg=$todayMovedVolumeKg&feeling=$workoutFeelingTag",
                mapOf("dayIdx" to currentDayIdx, "tonnageKg" to todayMovedVolumeKg, "feeling" to workoutFeelingTag)
            ) {
                runOnUiThread {
                    Toast.makeText(this, "✅ Workout an RE:MK App gesendet!", Toast.LENGTH_SHORT).show()
                }
            }
            exList.forEach { it.doneSets = 0 }
            currentDayIdx = (currentDayIdx + 1) % trainingDays.size
            currentExIdx = 0
            currentSetIdx = 1
            showWorkoutCompleteOverlay = false
            saveWatchStateAndRefreshAll()
            renderCurrentScreen()
        }
        box.addView(pushBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))

        rootFrame.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun buildBottomPageDotsStrip(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        }
        val icons = listOf("🏋️", "📋", "🏃", "🍎", "🛒")
        for (i in 0 until totalPages) {
            val active = (i == activePage)
            val dot = TextView(this).apply {
                text = if (active) icons[i] else "•"
                setTextColor(Color.parseColor(if (active) "#FF5E00" else "#475569"))
                textSize = if (active) 11.5f else 13f
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(1), dp(5), dp(1))
                setOnClickListener {
                    triggerVibration(15)
                    activePage = i
                    renderCurrentScreen()
                }
            }
            row.addView(dot)
        }
        return row
    }

    // =========================================================================
    // LOGIK, DATENBEREINIGUNG (SECTION 5) & BIDIREKTIONALER SYNC (SECTION 6)
    // =========================================================================
    private fun isIsometricOrTimedExercise(exName: String): Boolean {
        val low = exName.lowercase()
        return low.contains("plank") || low.contains("hold") || low.contains("stretch") ||
            low.contains("wall sit") || low.contains("mobility") || low.contains("hollow") ||
            low.contains("hängen") || low.contains("isometr")
    }

    private fun isBodyweightExercise(exName: String): Boolean {
        val low = exName.lowercase()
        val kw = listOf(
            "ab wheel", "rollout", "plank", "crunch", "leg raise", "beinheben",
            "liegestütz", "push-up", "pushup", "klimmzug", "pull-up", "dip",
            "burpee", "pistol", "stretch", "mobility", "box jump"
        )
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
        val totalMealKcal = dailyMeals.sumOf { it.kcal }
        val scale = if (totalMealKcal > targetKcal * 1.35f && totalMealKcal > 0) {
            targetKcal.toFloat() / totalMealKcal.toFloat()
        } else 1.0f
        val openScaled = dailyMeals.filter { !it.done }.sumOf { (it.kcal * scale).roundToInt() }
        return (openScaled - manualSnackDeltaKcal).coerceIn(0, targetKcal)
    }

    private fun switchExerciseSafely(newExIdx: Int) {
        val exList = trainingDays[currentDayIdx].second
        if (exList.isEmpty()) return
        inlineRulerMode = "none"
        unilateralPhaseRight = false
        currentExIdx = (newExIdx + exList.size) % exList.size
        val curEx = exList[currentExIdx]
        currentSetIdx = (curEx.doneSets + 1).coerceAtMost(curEx.sets)
        val (k, r) = sanitizeExerciseWeightAndReps(curEx.name, curEx.kg, curEx.targetReps)
        liveKg = k
        liveReps = r
        saveWatchStateAndRefreshAll()
        renderCurrentScreen()
    }

    private fun setExactWeight(newKg: Float, autoClose: Boolean) {
        liveKg = ((newKg.coerceAtLeast(0f)) * 4f).roundToInt() / 4f
        trainingDays[currentDayIdx].second[currentExIdx].kg = liveKg
        if (autoClose) inlineRulerMode = "none"
        saveWatchStateAndRefreshAll()
        renderCurrentScreen()
    }

    private fun setExactReps(newReps: Int, autoClose: Boolean) {
        liveReps = newReps.coerceIn(1, 300)
        trainingDays[currentDayIdx].second[currentExIdx].targetReps = liveReps
        if (autoClose) inlineRulerMode = "none"
        saveWatchStateAndRefreshAll()
        renderCurrentScreen()
    }

    private fun stepWeight(delta: Float) = setExactWeight(liveKg + delta, autoClose = false)
    private fun stepReps(delta: Int) = setExactReps(liveReps + delta, autoClose = false)

    private fun addWaterLiters(deltaL: Float) {
        triggerVibration(25)
        val curL = prefs.getFloat("drank_liters", 0.0f)
        val nextL = ((curL + deltaL).coerceAtLeast(0f) * 100f).roundToInt() / 100f
        prefs.edit().putFloat("drank_liters", nextL).apply()
        saveWatchStateAndRefreshAll()
        val ml = (deltaL * 1000f).roundToInt()
        WearOfflineSyncQueue.enqueueEvent(
            this,
            "WATCH_WATER_ADDED",
            "addedMl=$ml&drankLiters=$nextL",
            mapOf("addedMl" to ml, "drankLiters" to nextL.toDouble())
        )
        renderCurrentScreen()
    }

    private fun completeCurrentSet() {
        val exList = trainingDays[currentDayIdx].second
        val curEx = exList[currentExIdx]

        // Bei unilateralen Übungen: Erst Links -> automatisch Hinweis auf Rechts!
        if (curEx.unilateral && !unilateralPhaseRight) {
            triggerVibration(35)
            unilateralPhaseRight = true
            Toast.makeText(this, "🔄 Seite wechseln: Jetzt RECHTS!", Toast.LENGTH_SHORT).show()
            renderCurrentScreen()
            return
        }
        unilateralPhaseRight = false
        triggerVibration(65)
        inlineRulerMode = "none"

        canUndoLastSet = true
        undoDayIdx = currentDayIdx
        undoExIdx = currentExIdx
        undoSetIdx = currentSetIdx
        undoKg = liveKg
        undoReps = liveReps
        showUndoBannerUntilMs = System.currentTimeMillis() + 5000L

        curEx.doneSets = currentSetIdx.coerceAtMost(curEx.sets)
        todayMovedVolumeKg += (liveKg * liveReps).roundToInt()
        val doneToday = prefs.getInt("done_sets_today", 0) + 1
        prefs.edit().putInt("done_sets_today", doneToday).apply()

        WearOfflineSyncQueue.enqueueEvent(
            this,
            "WATCH_SET_COMPLETED",
            "exId=${curEx.id}&setIdx=$currentSetIdx&kg=$liveKg&reps=$liveReps&rir=$currentRir",
            mapOf(
                "exId" to curEx.id,
                "setIdx" to currentSetIdx,
                "kg" to liveKg.toDouble(),
                "reps" to liveReps,
                "rir" to currentRir
            )
        )

        val autoRestSec = calculateOptimalRestSeconds(curEx)

        if (currentSetIdx < curEx.sets) {
            currentSetIdx += 1
        } else {
            val nextOpen = exList.indexOfFirst { it.doneSets < it.sets }
            if (nextOpen >= 0) {
                currentExIdx = nextOpen
                val nx = exList[currentExIdx]
                currentSetIdx = (nx.doneSets + 1).coerceAtMost(nx.sets)
                val (k, r) = sanitizeExerciseWeightAndReps(nx.name, nx.kg, nx.targetReps)
                liveKg = k
                liveReps = r
            } else {
                saveWatchStateAndRefreshAll()
                triggerRestAlarmPattern()
                showWorkoutCompleteOverlay = true
                renderCurrentScreen()
                return
            }
        }

        saveWatchStateAndRefreshAll()
        startRestCountdown(autoRestSec)
    }

    private fun undoLastCompletedSet() {
        if (!canUndoLastSet) return
        triggerVibration(40)
        canUndoLastSet = false
        currentDayIdx = undoDayIdx
        currentExIdx = undoExIdx
        currentSetIdx = undoSetIdx
        liveKg = undoKg
        liveReps = undoReps

        val curEx = trainingDays[currentDayIdx].second[currentExIdx]
        curEx.doneSets = (undoSetIdx - 1).coerceAtLeast(0)
        todayMovedVolumeKg = (todayMovedVolumeKg - (undoKg * undoReps).roundToInt()).coerceAtLeast(0)
        val doneToday = (prefs.getInt("done_sets_today", 1) - 1).coerceAtLeast(0)
        prefs.edit().putInt("done_sets_today", doneToday).apply()

        WearOfflineSyncQueue.removeLastSetEventIfUndo(this, curEx.id, undoSetIdx)
        stopRestCountdown()
        saveWatchStateAndRefreshAll()
        renderCurrentScreen()
    }

    private fun postponeOccupiedExercise() {
        triggerVibration(30)
        val exList = trainingDays[currentDayIdx].second
        if (exList.size > 1) {
            val moved = exList.removeAt(currentExIdx)
            exList.add(moved)
            val cur = exList[currentExIdx]
            currentSetIdx = (cur.doneSets + 1).coerceAtMost(cur.sets)
            liveKg = cur.kg
            liveReps = cur.targetReps
            saveWatchStateAndRefreshAll()
            renderCurrentScreen()
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
        saveWatchStateAndRefreshAll()
        renderCurrentScreen()
    }

    private fun calculateOptimalRestSeconds(ex: WatchExercise): Int {
        val low = ex.name.lowercase()
        if (isBodyweightExercise(ex.name) || low.contains("seitheben") || low.contains("curl") || low.contains("pushdown")) return 60
        if (low.contains("kniebeug") || low.contains("kreuzheben") || low.contains("bankdrücken") || low.contains("beinpresse")) return 120
        return 90
    }

    private fun startRestCountdown(seconds: Int) {
        restTimer?.cancel()
        restSecondsLeft = seconds
        restTotalSeconds = seconds.coerceAtLeast(1)
        hrRecoveryNotifiedThisRest = false
        restEndTimestampMs = System.currentTimeMillis() + seconds * 1000L
        prefs.edit().putLong("last_rest_start_ms", System.currentTimeMillis()).putInt("rest_target_sec", seconds).apply()
        RemkComplicationHelper.requestAllComplicationsRefresh(this)
        renderCurrentScreen()

        restTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val rem = ((restEndTimestampMs - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
                restSecondsLeft = rem
                val m = (rem / 60).toString().padStart(2, '0')
                val s = (rem % 60).toString().padStart(2, '0')
                val isRec = (liveBpm < hrRecoveryThresholdBpm)

                // Kräftige 3-2-1 Countdown-Vibration am Ende
                if (rem in 1..3) triggerVibration(85)
                if (isRec && !hrRecoveryNotifiedThisRest && rem < (seconds - 15)) {
                    hrRecoveryNotifiedThisRest = true
                    triggerVibration(45)
                }

                if (restCountdownTv != null) {
                    restCountdownTv?.text = "$m:$s"
                    restRingView?.progressPct = (rem.toFloat() / restTotalSeconds.toFloat()).coerceIn(0f, 1f)
                    restRingView?.ringColorHex = if (rem <= 5) "#EF4444" else if (isRec) "#10B981" else "#FF5E00"
                    restRingView?.invalidate()
                    restHrBadgeTv?.text = if (isRec) "💚 $liveBpm BPM • Bereit" else "❤️ $liveBpm BPM • Erholung"
                    restHrBadgeTv?.setTextColor(Color.parseColor(if (isRec) "#10B981" else "#FBBF24"))
                } else if (activePage == 0) {
                    renderCurrentScreen()
                }
            }

            override fun onFinish() {
                restSecondsLeft = 0
                triggerRestAlarmPattern()
                RemkComplicationHelper.requestAllComplicationsRefresh(this@WearMainActivity)
                renderCurrentScreen()
            }
        }.start()
    }

    private fun stopRestCountdown() {
        restTimer?.cancel()
        restSecondsLeft = 0
        prefs.edit().putLong("last_rest_start_ms", 0L).apply()
        RemkComplicationHelper.requestAllComplicationsRefresh(this)
        renderCurrentScreen()
    }

    // =========================================================================
    // SENSOREN, GPS & CLOUD-SYNC
    // =========================================================================
    private fun initWatchHardwareSensors() {
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            heartRateSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val miss = mutableListOf<String>()
                if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) miss.add(Manifest.permission.BODY_SENSORS)
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) miss.add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (miss.isNotEmpty()) requestPermissions(miss.toTypedArray(), 404)
                else heartRateSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            } else {
                heartRateSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            }
        } catch (e: Exception) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_HEART_RATE && event.values.isNotEmpty()) {
            val bpm = event.values[0].toInt()
            if (bpm in 42..220 && bpm != liveBpm) {
                liveBpm = bpm
                prefs.edit().putInt("last_live_bpm", liveBpm).apply()
                if (gpsRunning) {
                    bpmSum += bpm
                    bpmSamples += 1
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(loc: Location) {
        if (!gpsRunning) return
        val prev = lastGpsLocation
        if (prev != null) {
            val d = prev.distanceTo(loc)
            if (d in 1.5f..150f) gpsDistKm += (d / 1000f)
        }
        lastGpsLocation = loc
    }

    @SuppressLint("MissingPermission")
    private fun toggleGpsTracking() {
        if (gpsRunning) {
            gpsRunning = false
            gpsTimer?.cancel()
            try { locationManager?.removeUpdates(this) } catch (e: Exception) {}
            renderCurrentScreen()
            return
        }
        gpsRunning = true
        lastGpsLocation = null
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            ) {
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
                cardioLiveTv?.text = "❤️ $liveBpm BPM\n$zLbl\n📡 ${String.format("%.2f", gpsDistKm)} km  •  ⏱️ $m:$s"
                cardioLiveTv?.setTextColor(Color.parseColor(zCol))
            }
            override fun onFinish() { gpsRunning = false }
        }.start()
        renderCurrentScreen()
    }

    private fun checkAndPerformMidnightDailyReset() {
        try {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date())
            val last = prefs.getString("last_daily_reset_date", "") ?: ""
            if (last.isNotEmpty() && last != today) {
                val ed = prefs.edit()
                ed.putString("last_daily_reset_date", today)
                ed.putFloat("drank_liters", 0f)
                ed.putInt("done_sets_today", 0)
                ed.putInt("today_volume_kg", 0)
                ed.putInt("manual_snack_delta_kcal", 0)
                for (i in 0..10) ed.putBoolean("meal_done_$i", false)
                ed.apply()
            } else if (last.isEmpty()) {
                prefs.edit().putString("last_daily_reset_date", today).apply()
            }
        } catch (e: Exception) {}
    }

    private fun loadPersistedWatchState() {
        manualSnackDeltaKcal = prefs.getInt("manual_snack_delta_kcal", 0)
        activeEnv = prefs.getString("active_env", "gym") ?: "gym"
        val savedTarget = prefs.getInt("personal_target_kcal", 2450)
        if (savedTarget in 1100..4600) targetKcal = savedTarget

        try {
            val rawPlan = prefs.getString("custom_watch_plan_json", "") ?: ""
            if (rawPlan.isNotEmpty()) applyCompactPlanJson(rawPlan)
        } catch (e: Exception) {}

        try {
            val rawMap = prefs.getString("completed_sets_map_json", "{}") ?: "{}"
            val mapObj = JSONObject(rawMap)
            trainingDays.forEachIndexed { dIdx, pair ->
                pair.second.forEach { ex ->
                    if (mapObj.has("${dIdx}_${ex.id}")) {
                        ex.doneSets = mapObj.optInt("${dIdx}_${ex.id}", ex.doneSets).coerceIn(0, ex.sets)
                    }
                }
            }
        } catch (e: Exception) {}

        weightStep = prefs.getFloat("weight_step", 2.5f)
        currentDayIdx = prefs.getInt("day_idx", 0).coerceIn(0, (trainingDays.size - 1).coerceAtLeast(0))
        val exList = trainingDays[currentDayIdx].second
        currentExIdx = prefs.getInt("cur_ex_idx", 0).coerceIn(0, (exList.size - 1).coerceAtLeast(0))
        val curEx = exList[currentExIdx]
        currentSetIdx = prefs.getInt("cur_set_idx", (curEx.doneSets + 1).coerceAtMost(curEx.sets))
        val lastSavedExId = prefs.getString("cur_ex_id", "") ?: ""
        val usePrefsWeight = (lastSavedExId == curEx.id)
        val rawK = if (usePrefsWeight) prefs.getFloat("cur_kg", curEx.kg) else curEx.kg
        val rawR = if (usePrefsWeight) prefs.getInt("cur_reps", curEx.targetReps) else curEx.targetReps
        val (k, r) = sanitizeExerciseWeightAndReps(curEx.name, rawK, rawR)
        liveKg = k
        liveReps = r
        todayMovedVolumeKg = prefs.getInt("today_volume_kg", 0)
    }

    private fun saveWatchStateAndRefreshAll() {
        val exList = trainingDays[currentDayIdx].second
        val curEx = exList[currentExIdx.coerceIn(0, (exList.size - 1).coerceAtLeast(0))]
        val openKcal = getPersonalOpenKcal()

        val mapObj = JSONObject()
        trainingDays.forEachIndexed { dIdx, pair ->
            pair.second.forEach { ex ->
                if (ex.doneSets > 0) mapObj.put("${dIdx}_${ex.id}", ex.doneSets)
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
            .putInt("today_volume_kg", todayMovedVolumeKg)
            .putFloat("cur_kg", liveKg)
            .putInt("cur_reps", liveReps)
            .putInt("personal_target_kcal", targetKcal)
            .putInt("open_kcal", openKcal)
            .putString("completed_sets_map_json", mapObj.toString())
            .apply()

        RemkTileHelper.requestAllTilesRefresh(this)
        RemkComplicationHelper.requestAllComplicationsRefresh(this)
    }

    private fun applyCompactPlanJson(jsonStr: String): Boolean {
        return try {
            val obj = JSONObject(jsonStr)
            val incK = obj.optInt("k", targetKcal)
            targetKcal = if (incK in 1100..4600) incK else 2450
            val incAge = obj.optInt("age", athleteAge)
            if (incAge in 14..85) athleteAge = incAge
            activePartnerSlot = obj.optString("slot", activePartnerSlot).ifEmpty { "p1" }

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
                            val (wVal, rVal) = sanitizeExerciseWeightAndReps(
                                exName,
                                eObj.optDouble("w", 40.0).toFloat(),
                                eObj.optInt("r", 10)
                            )
                            val defPrev = if (isIsometricOrTimedExercise(exName)) "BW × ${rVal}s" else if (wVal <= 0f) "BW × $rVal" else "${fmtKg(wVal)} × $rVal"
                            val rawPrev = eObj.optString("p", defPrev)
                            val prevStr = if (wVal <= 0f && (rawPrev.startsWith("60") || rawPrev.startsWith("44"))) defPrev else rawPrev
                            val isUni = (eObj.optInt("u", 0) == 1) || eObj.optBoolean("u", false)
                            exList.add(
                                WatchExercise(
                                    id = eObj.optString("i", "ex_$j"),
                                    name = exName,
                                    sets = setsVal,
                                    targetReps = rVal,
                                    kg = wVal,
                                    prevRef = prevStr,
                                    unilateral = isUni,
                                    doneSets = eObj.optInt("ds", 0).coerceIn(0, setsVal)
                                )
                            )
                        }
                    }
                    if (exList.isNotEmpty()) newDays.add(dTitle to exList)
                }
                if (newDays.isNotEmpty()) trainingDays = newDays
            }

            val mArr = obj.optJSONArray("m")
            if (mArr != null && mArr.length() > 0) {
                val tempMeals = mutableListOf<WatchMeal>()
                for (i in 0 until mArr.length()) {
                    val mObj = mArr.optJSONObject(i) ?: continue
                    val mTitle = mObj.optString("t", mObj.optString("n", "Gericht"))
                    val cloudDone = mObj.optBoolean("d", false) || (mObj.optInt("dn", 0) == 1) || mObj.optBoolean("dn", false)
                    tempMeals.add(
                        WatchMeal(
                            slot = mObj.optString("s", "Mahlzeit"),
                            title = mTitle,
                            kcal = mObj.optInt("k", 550).coerceAtLeast(100),
                            done = cloudDone || prefs.getBoolean("meal_done_$i", false)
                        )
                    )
                }
                val sumK = tempMeals.sumOf { it.kcal }
                if (sumK > targetKcal * 1.35f && sumK > 0) {
                    val f = targetKcal.toFloat() / sumK.toFloat()
                    tempMeals.forEach { it.kcal = ((it.kcal * f) / 10f).roundToInt() * 10 }
                }
                if (tempMeals.isNotEmpty()) {
                    dailyMeals.clear()
                    dailyMeals.addAll(tempMeals)
                }
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
                        if (sTxt.isNotEmpty() && !sTxt.startsWith("{")) {
                            shoppingItems.add(WatchShopItem(i, sTxt, prefs.getBoolean("shop_done_$i", false)))
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun triggerBackgroundCloudPull(silent: Boolean) {
        if (isSyncInProgress) return
        isSyncInProgress = true

        WearOfflineSyncQueue.pushWatchStateToCloudAsync(this) { _ ->
            WearOfflineSyncQueue.pullCloudStateToWatchAsync(this) { ok, cloudObj ->
                runOnUiThread {
                    isSyncInProgress = false
                    lastCloudSyncOk = ok
                    if (ok && cloudObj != null) {
                        val planJson = cloudObj.optString("watchPlanCompactJson", cloudObj.optString("compactPlan", ""))
                        val cSets = cloudObj.optInt("doneSetsToday", -1)
                        val cWater = cloudObj.optDouble("drankLiters", -1.0)
                        val cKcal = cloudObj.optInt("openKcal", -1)
                        val mapStr = cloudObj.optString("completedSetsMap", "")

                        val sig = "${planJson.hashCode()}_${cSets}_${cWater}_${cKcal}_${mapStr.hashCode()}"
                        val changed = (sig != lastCloudDataSignature)
                        lastCloudDataSignature = sig

                        if (planJson.isNotEmpty() && applyCompactPlanJson(planJson)) {
                            prefs.edit().putString("custom_watch_plan_json", planJson).apply()
                        }
                        if (cSets >= 0) prefs.edit().putInt("done_sets_today", maxOf(prefs.getInt("done_sets_today", 0), cSets)).apply()
                        if (cWater >= 0.0) prefs.edit().putFloat("drank_liters", maxOf(prefs.getFloat("drank_liters", 0f), cWater.toFloat())).apply()
                        if (cKcal in 0..4600) prefs.edit().putInt("open_kcal", cKcal).apply()

                        loadPersistedWatchState()
                        saveWatchStateAndRefreshAll()

                        val idleMs = System.currentTimeMillis() - lastTouchTimestampMs
                        if ((changed && idleMs > 3500L) || !silent) {
                            renderCurrentScreen()
                        }
                        if (!silent) Toast.makeText(this, "✅ Live-Sync erfolgreich!", Toast.LENGTH_SHORT).show()
                    } else if (!silent) {
                        Toast.makeText(this, "⚠️ Offline-Puffer aktiv", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // =========================================================================
    // UI-HELPER
    // =========================================================================
    private fun buildWeightedBtn(label: String, bgHex: String, txtHex: String, fontSp: Float, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(txtHex))
            textSize = fontSp
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            background = roundedBg(bgHex, if (bgHex == "#121722") "#28354D" else bgHex, 16)
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setOnClickListener { onClick() }
        }
    }

    private fun buildSmallPill(label: String, bgHex: String, txtHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(txtHex))
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            background = roundedBg(bgHex, txtHex, 10)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(3), 0, dp(3), 0)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun roundedBg(fillHex: String, strokeHex: String, radiusDp: Int, strokeDp: Int = 1): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(strokeDp), Color.parseColor(strokeHex))
        }
    }

    private fun fmtKg(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else v.toString().replace(".", ",")
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun triggerVibration(durationMs: Long) {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vib != null && vib.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
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
                val pattern = longArrayOf(0, 260, 110, 260, 110, 420)
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
        restTimer?.cancel()
        gpsTimer?.cancel()
        try { sensorManager?.unregisterListener(this) } catch (e: Exception) {}
        try { locationManager?.removeUpdates(this) } catch (e: Exception) {}
        super.onDestroy()
    }
}
