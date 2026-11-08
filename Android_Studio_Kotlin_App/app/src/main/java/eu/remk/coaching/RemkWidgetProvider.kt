package eu.remk.coaching

import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.RemoteInput
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * RE:MK Coaching v25.1 – 4 Interaktive Android Pro-Widgets + Smartwatch-Brücke:
 * 1. RemkWidgetProvider (🍎 Ernährung, 4 Yazio-Mahlzeiten-Slots zum Direkt-Abhaken, Rezept-Vorschau & +250ml Wasser)
 * 2. RemkWorkoutWidgetProvider (🏋️ Live-Training: Direkt auf dem Homescreen -2,5kg / +2,5kg & ✓ Satz abhaken)
 * 3. RemkEnduranceWidgetProvider (🛒 Interaktive Einkaufsliste: Direkt auf dem Homescreen abhaken mit Auto-Nachrücken + GPS-Start)
 * 4. RemkHealthWidgetProvider (❤️ Vital, Schritte, 🗣️ Coach AN/AUS Direkt-Schalter & ⏱️ 90s Timer Direkt-Start)
 * 5. Smartwatch Set-Logger Receiver (✓ Gewicht×Wdh Touch-Button, ⚙️ Vorauswahl-Liste & +30s Pause)
 */
class RemkWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_ADD_WATER_250 = "eu.remk.coaching.ACTION_ADD_WATER_250"
        const val ACTION_TOGGLE_MEAL_SLOT = "eu.remk.coaching.ACTION_TOGGLE_MEAL_SLOT"
        const val ACTION_TOGGLE_SHOP_ITEM = "eu.remk.coaching.ACTION_TOGGLE_SHOP_ITEM"
        const val ACTION_WORKOUT_STEP_WEIGHT = "eu.remk.coaching.ACTION_WORKOUT_STEP_WEIGHT"
        const val ACTION_WORKOUT_COMPLETE_SET = "eu.remk.coaching.ACTION_WORKOUT_COMPLETE_SET"
        const val ACTION_TOGGLE_COACH_MUTE = "eu.remk.coaching.ACTION_TOGGLE_COACH_MUTE"
        const val ACTION_START_TIMER_90 = "eu.remk.coaching.ACTION_START_TIMER_90"
        const val ACTION_REFRESH_WIDGETS = "eu.remk.coaching.ACTION_REFRESH_WIDGETS"
        const val ACTION_WATCH_COMPLETE_SET = "eu.remk.coaching.ACTION_WATCH_COMPLETE_SET"
        const val ACTION_WATCH_LOG_CUSTOM_SET = "eu.remk.coaching.ACTION_WATCH_LOG_CUSTOM_SET"
        const val ACTION_WATCH_ADD_REST_30 = "eu.remk.coaching.ACTION_WATCH_ADD_REST_30"
        const val ACTION_EXECUTE_WATCH_COMMAND = "eu.remk.coaching.ACTION_EXECUTE_WATCH_COMMAND"
        const val KEY_WATCH_WEIGHT_REPS_INPUT = "remk_watch_weight_reps_input"

        fun enqueueCommandForWebView(context: Context, cmd: String, payload: String) {
            try {
                val prefs = context.getSharedPreferences("REMK_NATIVE_OS_VAULT", Context.MODE_PRIVATE)
                val rawQueue = prefs.getString("pending_commands_queue_v251", "[]") ?: "[]"
                val arr = try { JSONArray(rawQueue) } catch (e: Exception) { JSONArray() }
                val item = JSONObject().apply {
                    put("cmd", cmd)
                    put("payload", payload)
                    put("ts", System.currentTimeMillis())
                }
                arr.put(item)
                prefs.edit()
                    .putString("pending_commands_queue_v251", arr.toString())
                    .putString("pending_watch_cmd", cmd)
                    .putString("pending_watch_payload", payload)
                    .apply()
                context.sendBroadcast(Intent(ACTION_EXECUTE_WATCH_COMMAND).setPackage(context.packageName))
            } catch (e: Exception) {}
        }

        fun ensureDailyWaterReset(context: Context) {
            try {
                val prefs = context.getSharedPreferences("REMK_NATIVE_OS_VAULT", Context.MODE_PRIVATE)
                val todayStr = java.text.SimpleDateFormat("yyyyMMdd", Locale.GERMANY).format(java.util.Date())
                val lastWaterDate = prefs.getString("water_date_stamp", "") ?: ""
                if (lastWaterDate != todayStr) {
                    val targetL = prefs.getString("widget_water_target_l", "2,8") ?: "2,8"
                    prefs.edit()
                        .putString("water_date_stamp", todayStr)
                        .putInt("widget_water_ml", 0)
                        .putInt("pending_widget_water_add_ml", 0)
                        .putString("widget_water", "💧 0,00 / $targetL L")
                        .putBoolean("meal_slot_done_0", false)
                        .putBoolean("meal_slot_done_1", false)
                        .putBoolean("meal_slot_done_2", false)
                        .putBoolean("meal_slot_done_3", false)
                        .apply()
                }
            } catch (e: Exception) {}
        }

        fun broadcastUpdateAllWidgets(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context)
                val providers = arrayOf(
                    RemkWidgetProvider::class.java,
                    RemkWorkoutWidgetProvider::class.java,
                    RemkEnduranceWidgetProvider::class.java,
                    RemkHealthWidgetProvider::class.java
                )
                for (cls in providers) {
                    val ids = mgr.getAppWidgetIds(ComponentName(context, cls))
                    if (ids != null && ids.isNotEmpty()) {
                        val intent = Intent(context, cls).apply {
                            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                        }
                        context.sendBroadcast(intent)
                    }
                }
            } catch (e: Exception) {}
        }

        fun buildLaunchPendingIntent(
            context: Context,
            mode: String,
            reqCode: Int,
            sportMode: String = ""
        ): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("remk_widget_mode", mode)
                if (sportMode.isNotEmpty()) {
                    putExtra("remk_sport_mode", sportMode)
                }
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            return PendingIntent.getActivity(context, reqCode, intent, flags)
        }

        fun buildBroadcastPendingIntent(
            context: Context,
            actionStr: String,
            reqCode: Int,
            mutableForRemoteInput: Boolean = false,
            extraVal: String = ""
        ): PendingIntent {
            val intent = Intent(context, RemkWidgetProvider::class.java).apply {
                action = actionStr
                if (extraVal.isNotEmpty()) {
                    putExtra("remk_extra_val", extraVal)
                }
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or (if (mutableForRemoteInput) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or (if (mutableForRemoteInput) 0 else PendingIntent.FLAG_IMMUTABLE)
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            return PendingIntent.getBroadcast(context, reqCode, intent, flags)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val prefs = context.getSharedPreferences("REMK_NATIVE_OS_VAULT", Context.MODE_PRIVATE)
        val extraVal = intent.getStringExtra("remk_extra_val") ?: ""

        when (intent.action) {
            ACTION_ADD_WATER_250 -> {
                ensureDailyWaterReset(context)
                val curMl = prefs.getInt("widget_water_ml", 0) + 250
                val pendingAdd = prefs.getInt("pending_widget_water_add_ml", 0) + 250
                val targetL = prefs.getString("widget_water_target_l", "2,8") ?: "2,8"
                val litersStr = String.format(Locale.GERMANY, "💧 %.2f / %s L", curMl / 1000.0f, targetL)
                prefs.edit()
                    .putInt("widget_water_ml", curMl)
                    .putInt("pending_widget_water_add_ml", pendingAdd)
                    .putString("widget_water", litersStr)
                    .apply()
                broadcastUpdateAllWidgets(context)
                context.sendBroadcast(Intent(ACTION_EXECUTE_WATCH_COMMAND).setPackage(context.packageName))
            }
            ACTION_TOGGLE_MEAL_SLOT -> {
                val slotIdx = extraVal.toIntOrNull() ?: 0
                val keyDone = "meal_slot_done_$slotIdx"
                val nextDone = !prefs.getBoolean(keyDone, false)
                prefs.edit().putBoolean(keyDone, nextDone).apply()
                enqueueCommandForWebView(context, "TOGGLE_MEAL_SLOT", slotIdx.toString())
                broadcastUpdateAllWidgets(context)
            }
            ACTION_TOGGLE_SHOP_ITEM -> {
                val slotIdx = extraVal.toIntOrNull() ?: 0
                val itemText = prefs.getString("widget_shop_item_$slotIdx", "") ?: ""
                // Sofort visuell im Widget abhaken oder nächsten offenen Artikel nachrücken lassen
                try {
                    val rawAll = prefs.getString("widget_shop_open_json", "[]") ?: "[]"
                    val arr = JSONArray(rawAll)
                    val remaining = mutableListOf<String>()
                    var removedTarget = false
                    val cleanTarget = itemText.replace("☐", "").replace("☑", "").trim()
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i, "")
                        val cleanS = s.replace("☐", "").replace("☑", "").trim()
                        if (!removedTarget && cleanS == cleanTarget) {
                            removedTarget = true
                        } else {
                            remaining.add(s)
                        }
                    }
                    val newArr = JSONArray()
                    remaining.forEach { newArr.put(it) }
                    val ed = prefs.edit().putString("widget_shop_open_json", newArr.toString())
                    for (k in 0..2) {
                        ed.putString(
                            "widget_shop_item_$k",
                            if (k < remaining.size) remaining[k] else "✅ Erledigt!"
                        )
                    }
                    ed.apply()
                } catch (e: Exception) {}
                enqueueCommandForWebView(context, "TOGGLE_SHOP_ITEM_TEXT", itemText)
                broadcastUpdateAllWidgets(context)
            }
            ACTION_WORKOUT_STEP_WEIGHT -> {
                val delta = extraVal.toDoubleOrNull() ?: 2.5
                val curW = (prefs.getString("watch_target_weight", "80") ?: "80").replace(",", ".").toDoubleOrNull() ?: 80.0
                val curR = prefs.getString("watch_target_reps", "10") ?: "10"
                val nextW = (curW + delta).coerceAtLeast(0.0)
                val wFormatted = if (nextW % 1.0 == 0.0) nextW.toInt().toString() else nextW.toString().replace(".", ",")
                prefs.edit()
                    .putString("watch_target_weight", nextW.toString())
                    .putString("widget_next_ex", "⚖️ Ziel: $wFormatted kg × $curR Wdh.")
                    .apply()
                enqueueCommandForWebView(context, "STEP_WORKOUT_WEIGHT", delta.toString())
                broadcastUpdateAllWidgets(context)
            }
            ACTION_WORKOUT_COMPLETE_SET, ACTION_WATCH_COMPLETE_SET -> {
                val curW = prefs.getString("watch_target_weight", "80") ?: "80"
                val curR = prefs.getString("watch_target_reps", "10") ?: "10"
                enqueueCommandForWebView(context, "COMPLETE_SET", "${curW}x${curR}")
                dismissWatchNotification(context)
                broadcastUpdateAllWidgets(context)
            }
            ACTION_WATCH_LOG_CUSTOM_SET -> {
                val results = RemoteInput.getResultsFromIntent(intent)
                val userInput = results?.getCharSequence(KEY_WATCH_WEIGHT_REPS_INPUT)?.toString()?.trim() ?: "TARGET"
                enqueueCommandForWebView(context, "LOG_CUSTOM_SET", userInput)
                dismissWatchNotification(context)
                broadcastUpdateAllWidgets(context)
            }
            ACTION_WATCH_ADD_REST_30 -> {
                enqueueCommandForWebView(context, "ADD_REST_30", "30")
            }
            ACTION_TOGGLE_COACH_MUTE -> {
                val nextMuted = !prefs.getBoolean("coach_muted_v251", false)
                prefs.edit()
                    .putBoolean("coach_muted_v251", nextMuted)
                    .putString("widget_coach_status", if (nextMuted) "🔇 Coach: AUS" else "🗣️🔥 Coach: AN")
                    .apply()
                enqueueCommandForWebView(context, "SET_COACH_MUTE", if (nextMuted) "off" else "drill")
                broadcastUpdateAllWidgets(context)
            }
            ACTION_START_TIMER_90 -> {
                enqueueCommandForWebView(context, "START_TIMER_90", "90")
            }
            ACTION_REFRESH_WIDGETS -> {
                broadcastUpdateAllWidgets(context)
            }
        }
    }

    private fun dismissWatchNotification(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(9001)
            nm.cancel(9002)
        } catch (e: Exception) {}
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        ensureDailyWaterReset(context)
        val prefs = context.getSharedPreferences("REMK_NATIVE_OS_VAULT", Context.MODE_PRIVATE)
        val kcal = prefs.getString("widget_kcal", "2456 kcal offen") ?: "2456 kcal offen"
        val prot = prefs.getString("widget_prot", "160g EW") ?: "160g EW"
        val carbs = prefs.getString("widget_carbs", "250g KH") ?: "250g KH"
        val fat = prefs.getString("widget_fat", "70g Fett") ?: "70g Fett"
        val water = prefs.getString("widget_water", "💧 0,00 / 2,8 L") ?: "💧 0,00 / 2,8 L"
        val recipePreview = prefs.getString("widget_recipe_preview", "🍽️ Rezept: Hähnchen-Reis-Bowl (720 kcal)") ?: "🍽️ Rezept: Hähnchen-Reis-Bowl"

        val m0Done = prefs.getBoolean("meal_slot_done_0", false)
        val m1Done = prefs.getBoolean("meal_slot_done_1", false)
        val m2Done = prefs.getBoolean("meal_slot_done_2", false)
        val m3Done = prefs.getBoolean("meal_slot_done_3", false)

        val m0Kcal = prefs.getString("widget_meal0_kcal", "580 kcal") ?: "580 kcal"
        val m1Kcal = prefs.getString("widget_meal1_kcal", "720 kcal") ?: "720 kcal"
        val m2Kcal = prefs.getString("widget_meal2_kcal", "410 kcal") ?: "410 kcal"
        val m3Kcal = prefs.getString("widget_meal3_kcal", "690 kcal") ?: "690 kcal"

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.remk_widget)
            views.setTextViewText(R.id.widget_header_title, "🍎 RE:MK NUTRITION & YAZIO-SYNC")
            views.setTextViewText(R.id.widget_kpi_text, "🔥 $kcal  •  $water")
            views.setTextViewText(R.id.widget_workout_sub, "💪 $prot  |  🌾 $carbs  |  🥑 $fat")
            views.setTextViewText(R.id.widget_recipe_preview, recipePreview)

            views.setTextViewText(R.id.btn_meal_slot_0, "${if (m0Done) "✅" else "☕"} Früh\n$m0Kcal")
            views.setTextViewText(R.id.btn_meal_slot_1, "${if (m1Done) "✅" else "🍲"} Mittag\n$m1Kcal")
            views.setTextViewText(R.id.btn_meal_slot_2, "${if (m2Done) "✅" else "🍎"} Snack\n$m2Kcal")
            views.setTextViewText(R.id.btn_meal_slot_3, "${if (m3Done) "✅" else "🥗"} Abend\n$m3Kcal")

            views.setOnClickPendingIntent(
                R.id.btn_meal_slot_0,
                buildBroadcastPendingIntent(context, ACTION_TOGGLE_MEAL_SLOT, 110, false, "0")
            )
            views.setOnClickPendingIntent(
                R.id.btn_meal_slot_1,
                buildBroadcastPendingIntent(context, ACTION_TOGGLE_MEAL_SLOT, 111, false, "1")
            )
            views.setOnClickPendingIntent(
                R.id.btn_meal_slot_2,
                buildBroadcastPendingIntent(context, ACTION_TOGGLE_MEAL_SLOT, 112, false, "2")
            )
            views.setOnClickPendingIntent(
                R.id.btn_meal_slot_3,
                buildBroadcastPendingIntent(context, ACTION_TOGGLE_MEAL_SLOT, 113, false, "3")
            )

            views.setOnClickPendingIntent(
                R.id.btn_widget_water_add,
                buildBroadcastPendingIntent(context, ACTION_ADD_WATER_250, 104)
            )
            views.setOnClickPendingIntent(
                R.id.btn_widget_workout,
                buildLaunchPendingIntent(context, "nutrition", 101)
            )
            views.setOnClickPendingIntent(
                R.id.btn_widget_maps,
                buildLaunchPendingIntent(context, "bls_search", 105)
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

/**
 * Widget 2: 🏋️ RE:MK Live-Workout & Direkt-Satz-Logger (-2,5kg / +2,5kg / ✓ Satz abhaken)
 */
class RemkWorkoutWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences("REMK_NATIVE_OS_VAULT", Context.MODE_PRIVATE)
        val workout = prefs.getString("widget_workout", "Bankdrücken • Satz 1") ?: "Bankdrücken • Satz 1"
        val nextEx = prefs.getString("widget_next_ex", "⚖️ Ziel: 80 kg × 10 Wdh.") ?: "⚖️ Ziel: 80 kg × 10 Wdh."

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.remk_widget_workout)
            views.setTextViewText(R.id.w_workout_title, "🎯 $workout")
            views.setTextViewText(R.id.w_workout_next_ex, nextEx)

            views.setOnClickPendingIntent(
                R.id.btn_w_minus_weight,
                RemkWidgetProvider.buildBroadcastPendingIntent(context, RemkWidgetProvider.ACTION_WORKOUT_STEP_WEIGHT, 210, false, "-2.5")
            )
            views.setOnClickPendingIntent(
                R.id.btn_w_plus_weight,
                RemkWidgetProvider.buildBroadcastPendingIntent(context, RemkWidgetProvider.ACTION_WORKOUT_STEP_WEIGHT, 211, false, "2.5")
            )
            views.setOnClickPendingIntent(
                R.id.btn_w_complete_set_direct,
                RemkWidgetProvider.buildBroadcastPendingIntent(context, RemkWidgetProvider.ACTION_WORKOUT_COMPLETE_SET, 212)
            )
            views.setOnClickPendingIntent(
                R.id.btn_w_start_workout,
                RemkWidgetProvider.buildLaunchPendingIntent(context, "workout", 201)
            )
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

/**
 * Widget 3: 🛒 RE:MK Interaktive Einkaufsliste (Direkt auf dem Homescreen abhaken!) & GPS-Start
 */
class RemkEnduranceWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences("REMK_NATIVE_OS_VAULT", Context.MODE_PRIVATE)
        val item0 = prefs.getString("widget_shop_item_0", "☐ 1,2 kg Geflügelbrustfilet (Hähnchen / Pute)") ?: "☐ 1,2 kg Geflügelbrustfilet"
        val item1 = prefs.getString("widget_shop_item_1", "☐ 1,5 kg Magerquark / Skyr Natur") ?: "☐ 1,5 kg Magerquark / Skyr"
        val item2 = prefs.getString("widget_shop_item_2", "☐ 750 g Basmatireis & Haferflocken") ?: "☐ 750 g Basmatireis & Haferflocken"
        val summary = prefs.getString("widget_endurance_summary", "🛒 RE:MK LIVE-EINKAUFSLISTE (ANTIPPEN = ✓)") ?: "🛒 RE:MK LIVE-EINKAUFSLISTE"

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.remk_widget_endurance)
            views.setTextViewText(R.id.w_endurance_kpi, summary)
            views.setTextViewText(R.id.btn_shop_item_0, item0)
            views.setTextViewText(R.id.btn_shop_item_1, item1)
            views.setTextViewText(R.id.btn_shop_item_2, item2)

            views.setOnClickPendingIntent(
                R.id.btn_shop_item_0,
                RemkWidgetProvider.buildBroadcastPendingIntent(context, RemkWidgetProvider.ACTION_TOGGLE_SHOP_ITEM, 310, false, "0")
            )
            views.setOnClickPendingIntent(
                R.id.btn_shop_item_1,
                RemkWidgetProvider.buildBroadcastPendingIntent(context, RemkWidgetProvider.ACTION_TOGGLE_SHOP_ITEM, 311, false, "1")
            )
            views.setOnClickPendingIntent(
                R.id.btn_shop_item_2,
                RemkWidgetProvider.buildBroadcastPendingIntent(context, RemkWidgetProvider.ACTION_TOGGLE_SHOP_ITEM, 312, false, "2")
            )

            views.setOnClickPendingIntent(
                R.id.btn_w_run,
                RemkWidgetProvider.buildLaunchPendingIntent(context, "shopping", 301)
            )
            views.setOnClickPendingIntent(
                R.id.btn_w_walk,
                RemkWidgetProvider.buildLaunchPendingIntent(context, "maps", 302, "run")
            )
            views.setOnClickPendingIntent(
                R.id.btn_w_bike,
                RemkWidgetProvider.buildLaunchPendingIntent(context, "maps", 303, "bike")
            )
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

/**
 * Widget 4: ❤️ RE:MK Vital, Schritte, 🗣️ Coach AN/AUS Direkt-Schalter & ⏱️ 90s Timer
 */
class RemkHealthWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences("REMK_NATIVE_OS_VAULT", Context.MODE_PRIVATE)
        val todayKey = "step_baseline_" + java.text.SimpleDateFormat("yyyyMMdd", Locale.GERMANY).format(java.util.Date())
        val realSteps = if (prefs.contains(todayKey)) prefs.getInt("steps_today", 0).coerceAtLeast(0) else 0
        val stepsStr = prefs.getString("widget_steps", realSteps.toString()) ?: realSteps.toString()
        val stepKcal = prefs.getString("widget_step_kcal", "🔥 0 kcal") ?: "🔥 0 kcal"
        val sleep = prefs.getString("widget_sleep", "😴 7,5 Std.") ?: "😴 7,5 Std."
        val pulse = prefs.getString("widget_pulse", "❤️ 62 BPM") ?: "❤️ 62 BPM"
        val readiness = prefs.getString("widget_readiness", "⚡ 92% Fit") ?: "⚡ 92% Fit"
        val isMuted = prefs.getBoolean("coach_muted_v251", false)
        val coachLabel = if (isMuted) "🔇 Coach: AUS" else "🗣️🔥 Coach: AN"

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.remk_widget_health)
            views.setTextViewText(R.id.w_health_steps, "👟 $stepsStr Schritte  •  $stepKcal")
            views.setTextViewText(R.id.w_health_vitals, "$pulse  |  $sleep  |  $readiness")
            views.setTextViewText(R.id.btn_w_toggle_coach, coachLabel)

            views.setOnClickPendingIntent(
                R.id.btn_w_toggle_coach,
                RemkWidgetProvider.buildBroadcastPendingIntent(context, RemkWidgetProvider.ACTION_TOGGLE_COACH_MUTE, 410)
            )
            views.setOnClickPendingIntent(
                R.id.btn_w_start_timer,
                RemkWidgetProvider.buildBroadcastPendingIntent(context, RemkWidgetProvider.ACTION_START_TIMER_90, 411)
            )
            views.setOnClickPendingIntent(
                R.id.btn_w_open_health,
                RemkWidgetProvider.buildLaunchPendingIntent(context, "health_hub", 401)
            )
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
