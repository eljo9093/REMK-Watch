package eu.remk.coaching.wear

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.wear.tiles.ActionBuilders
import androidx.wear.tiles.ColorBuilders.argb
import androidx.wear.tiles.DimensionBuilders.degrees
import androidx.wear.tiles.DimensionBuilders.dp
import androidx.wear.tiles.DimensionBuilders.expand
import androidx.wear.tiles.DimensionBuilders.sp
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.ModifiersBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.StateBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.tiles.TimelineBuilders
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RE:MK 360° 4 Direct-Action Wear OS 5 Home-Kacheln (v37.22 Hyper-Chrono LuminousGlass)
 * - Echte In-Tile-Bedienung via ActionBuilders.LoadAction + StateBuilders.State:
 *   Jeder Aktions-Button (−2,5, +2,5, ✓ SATZ, 60s/90s/120s/+30s/Stopp, 💧+250 ml, Sport-Wechsel)
 *   arbeitet direkt AUF DER KACHEL — 0 App-Weiterleitung, 0 Aufblitzen, sofortige 35-ms-Vibration!
 * - Nur ein bewusster Tipp auf den Übungstitel, Kalorien-Header oder "▶ START" (GPS) öffnet die App.
 */
class TileLogSetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        RemkTileHelper.vibrateClick(this)
        RemkTileHelper.handleDirectAction(this, "tile_log_set", prefs)
        RemkTileHelper.requestAllTilesRefresh(this)
        RemkComplicationHelper.requestAllComplicationsRefresh(this)
        Toast.makeText(applicationContext, "✓ Satz abgehakt!", Toast.LENGTH_SHORT).show()
        finish()
        overridePendingTransition(0, 0)
    }
}

abstract class BaseOpenAppPageActivity(private val targetPage: Int) : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = Intent(this, WearMainActivity::class.java).apply {
            putExtra("start_page", targetPage)
            putExtra("skip_home_hub", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(i)
        finish()
        overridePendingTransition(0, 0)
    }
}

class TileOpenWorkoutActivity : BaseOpenAppPageActivity(0)
class TileOpenNutritionActivity : BaseOpenAppPageActivity(1)
class TileOpenCardioActivity : BaseOpenAppPageActivity(4)

@Suppress("DEPRECATION")
object RemkTileHelper {

    const val TILE_RES_VERSION = "v37_22_hyper_chrono"

    private val CLEAN_CARDIO_SPORTS = arrayOf(
        "Laufen", "Rad", "Indoor-Cycling", "Hybrid-Race", "Verein"
    )

    fun vibrateClick(context: Context) {
        try {
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vib != null && vib.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createOneShot(35L, 180))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(35L)
                }
            }
        } catch (e: Exception) {}
    }

    fun getSanitizedOpenKcal(prefs: android.content.SharedPreferences): Int {
        val raw = prefs.getInt("open_kcal", 770)
        val target = prefs.getInt("personal_target_kcal", 2450).let { if (it in 1100..4600) it else 2450 }
        return if (raw > 4800) {
            prefs.edit().putInt("open_kcal", target).apply()
            target
        } else {
            raw.coerceAtLeast(0)
        }
    }

    fun formatGermanInt(value: Int): String {
        return try {
            NumberFormat.getIntegerInstance(Locale.GERMANY).format(value)
        } catch (e: Exception) {
            value.toString()
        }
    }

    fun extractClickedId(requestParams: RequestBuilders.TileRequest): String {
        val c1 = requestParams.currentState?.lastClickableId ?: ""
        if (c1.isNotEmpty()) return c1
        return requestParams.state?.lastClickableId ?: ""
    }

    fun requestAllTilesRefresh(context: Context) {
        try {
            val updater = TileService.getUpdater(context)
            updater.requestUpdate(RemkTileService::class.java)
            updater.requestUpdate(RemkTimerTileService::class.java)
            updater.requestUpdate(RemkNutritionTileService::class.java)
            updater.requestUpdate(RemkCardioShopTileService::class.java)
        } catch (e: Exception) {}
    }

    fun handleDirectAction(context: Context, actionId: String, prefs: android.content.SharedPreferences) {
        if (actionId.isEmpty()) return
        vibrateClick(context)
        val ed = prefs.edit()
        when (actionId) {
            "tile_kg_minus" -> {
                val cur = prefs.getFloat("cur_kg", 40f)
                val step = prefs.getFloat("weight_step", 2.5f)
                val nextKg = (cur - step).coerceAtLeast(0f)
                ed.putFloat("cur_kg", nextKg).commit()
                WearOfflineSyncQueue.enqueueEvent(context, "WATCH_KG_CHANGED", "kg=$nextKg")
            }
            "tile_kg_plus" -> {
                val cur = prefs.getFloat("cur_kg", 40f)
                val step = prefs.getFloat("weight_step", 2.5f)
                val nextKg = cur + step
                ed.putFloat("cur_kg", nextKg).commit()
                WearOfflineSyncQueue.enqueueEvent(context, "WATCH_KG_CHANGED", "kg=$nextKg")
            }
            "tile_log_set" -> {
                val dIdx = prefs.getInt("day_idx", 0)
                val exIdx = prefs.getInt("cur_ex_idx", 0)
                val exId = prefs.getString("cur_ex_id", "ex_0_0") ?: "ex_0_0"
                val exName = prefs.getString("cur_ex_name", "Schrägbankdrücken") ?: "Schrägbankdrücken"
                val sIdx = prefs.getInt("cur_set_idx", 1)
                val totS = prefs.getInt("cur_total_sets", 3).coerceAtLeast(1)
                val curKg = prefs.getFloat("cur_kg", 40f)
                val curReps = prefs.getInt("cur_reps", 10)
                val doneToday = prefs.getInt("done_sets_today", 0) + 1
                val movedVol = prefs.getInt("today_volume_kg", 0) + (curKg * curReps).toInt()
                ed.putInt("done_sets_today", doneToday)
                ed.putInt("today_volume_kg", movedVol)
                try {
                    val mapObj = org.json.JSONObject(prefs.getString("completed_sets_map_json", "{}") ?: "{}")
                    mapObj.put("${dIdx}_${exId}", sIdx.coerceAtMost(totS))
                    mapObj.put(exId, sIdx.coerceAtMost(totS))
                    ed.putString("completed_sets_map_json", mapObj.toString())
                } catch (e: Exception) {}
                val nextSet = if (sIdx < totS) sIdx + 1 else 1
                ed.putInt("cur_set_idx", nextSet)
                if (sIdx >= totS) {
                    ed.putInt("cur_ex_idx", (exIdx + 1) % 4)
                }
                val restSec = prefs.getInt("rest_target_sec", 90).coerceIn(30, 180)
                ed.putLong("last_rest_start_ms", System.currentTimeMillis())
                ed.putInt("rest_target_sec", restSec)
                ed.commit()

                val kgStr = if (curKg <= 0f) "BW" else if (curKg % 1f == 0f) "${curKg.toInt()}kg" else "${curKg.toString().replace(".", ",")}kg"
                RestTimerScheduler.scheduleRestEndAlarm(context, restSec, exName, nextSet, totS, kgStr, curReps)
                WearOfflineSyncQueue.enqueueEvent(
                    context,
                    "WATCH_SET_COMPLETED",
                    "exId=$exId&setIdx=$sIdx&kg=$curKg&reps=$curReps&rir=2",
                    mapOf("exId" to exId, "setIdx" to sIdx, "kg" to curKg.toDouble(), "reps" to curReps, "rir" to 2)
                )
            }
            "tile_rest_60", "tile_rest_90", "tile_rest_120" -> {
                val sec = when (actionId) { "tile_rest_60" -> 60; "tile_rest_120" -> 120; else -> 90 }
                ed.putLong("last_rest_start_ms", System.currentTimeMillis())
                ed.putInt("rest_target_sec", sec)
                ed.commit()
                RestTimerScheduler.scheduleRestEndAlarm(
                    context, sec,
                    prefs.getString("cur_ex_name", "Satzpause") ?: "Satzpause",
                    prefs.getInt("cur_set_idx", 1),
                    prefs.getInt("cur_total_sets", 3),
                    "${prefs.getFloat("cur_kg", 40f).toInt()}kg",
                    prefs.getInt("cur_reps", 10)
                )
            }
            "tile_rest_plus30" -> {
                val lastStart = prefs.getLong("last_rest_start_ms", 0L)
                val target = prefs.getInt("rest_target_sec", 90)
                val elapsed = if (lastStart > 0L) ((System.currentTimeMillis() - lastStart) / 1000L).toInt() else target
                val rem = (target - elapsed).coerceAtLeast(0) + 30
                ed.putLong("last_rest_start_ms", System.currentTimeMillis())
                ed.putInt("rest_target_sec", rem)
                ed.commit()
                RestTimerScheduler.scheduleRestEndAlarm(
                    context, rem,
                    prefs.getString("cur_ex_name", "Satzpause") ?: "Satzpause",
                    prefs.getInt("cur_set_idx", 1),
                    prefs.getInt("cur_total_sets", 3),
                    "${prefs.getFloat("cur_kg", 40f).toInt()}kg",
                    prefs.getInt("cur_reps", 10)
                )
            }
            "tile_rest_stop" -> {
                ed.putLong("last_rest_start_ms", 0L).commit()
                RestTimerScheduler.cancelRestEndAlarm(context)
            }
            "tile_water_250" -> {
                val curL = ((prefs.getFloat("drank_liters", 0.0f) + 0.25f) * 100f).toInt() / 100f
                ed.putFloat("drank_liters", curL).commit()
                WearOfflineSyncQueue.enqueueEvent(
                    context,
                    "WATCH_WATER_ADDED",
                    "addedMl=250&drankLiters=$curL",
                    mapOf("addedMl" to 250, "drankLiters" to curL.toDouble())
                )
            }
            "tile_cardio_next_sport" -> {
                val idx = (prefs.getInt("cardio_sport_idx", 0) + 1) % CLEAN_CARDIO_SPORTS.size
                ed.putInt("cardio_sport_idx", idx)
                ed.putString("cardio_sport_name", CLEAN_CARDIO_SPORTS[idx])
                ed.commit()
            }
        }
        RemkComplicationHelper.requestAllComplicationsRefresh(context)
    }

    /**
     * Erzeugt einen echten In-Tile-Button (LoadAction + StateBuilders.State) — bleibt 100% auf der Kachel!
     */
    fun buildInTileActionPill(
        label: String,
        clickId: String,
        bgColor: Int,
        txtColor: Int,
        borderColor: Int? = null,
        fontSp: Float = 13f,
        padH: Float = 14f,
        padV: Float = 8f,
        cornerRadiusDp: Float = 20f
    ): LayoutElementBuilders.LayoutElement {
        val loadClickable = ModifiersBuilders.Clickable.Builder()
            .setId(clickId)
            .setOnClick(
                ActionBuilders.LoadAction.Builder()
                    .setRequestState(StateBuilders.State.Builder().build())
                    .build()
            )
            .build()

        val mod = ModifiersBuilders.Modifiers.Builder()
            .setPadding(
                ModifiersBuilders.Padding.Builder()
                    .setStart(dp(padH)).setEnd(dp(padH)).setTop(dp(padV)).setBottom(dp(padV))
                    .build()
            )
            .setClickable(loadClickable)
            .setBackground(
                ModifiersBuilders.Background.Builder()
                    .setColor(argb(bgColor))
                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(cornerRadiusDp)).build())
                    .build()
            )
        if (borderColor != null) {
            mod.setBorder(ModifiersBuilders.Border.Builder().setWidth(dp(1.3f)).setColor(argb(borderColor)).build())
        }

        return LayoutElementBuilders.Box.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(mod.build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(label)
                    .setMaxLines(1)
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(fontSp))
                            .setColor(argb(txtColor))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .build()
    }

    /**
     * Bewusster App-Sprung (nur für Titel-Tipp oder GPS-Start)
     */
    fun buildLaunchActivityPill(
        pkg: String,
        targetClassName: String,
        label: String,
        clickId: String,
        bgColor: Int,
        txtColor: Int,
        borderColor: Int? = null,
        fontSp: Float = 13f,
        padH: Float = 18f,
        padV: Float = 8f,
        cornerRadiusDp: Float = 20f
    ): LayoutElementBuilders.LayoutElement {
        val launchClickable = ModifiersBuilders.Clickable.Builder()
            .setId(clickId)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(pkg)
                            .setClassName(targetClassName)
                            .build()
                    )
                    .build()
            )
            .build()

        val mod = ModifiersBuilders.Modifiers.Builder()
            .setPadding(
                ModifiersBuilders.Padding.Builder()
                    .setStart(dp(padH)).setEnd(dp(padH)).setTop(dp(padV)).setBottom(dp(padV))
                    .build()
            )
            .setClickable(launchClickable)
            .setBackground(
                ModifiersBuilders.Background.Builder()
                    .setColor(argb(bgColor))
                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(cornerRadiusDp)).build())
                    .build()
            )
        if (borderColor != null) {
            mod.setBorder(ModifiersBuilders.Border.Builder().setWidth(dp(1.2f)).setColor(argb(borderColor)).build())
        }

        return LayoutElementBuilders.Box.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(mod.build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(label)
                    .setMaxLines(1)
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(fontSp))
                            .setColor(argb(txtColor))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .build()
    }

    fun buildMacroProgressRow(label: String, eatenG: Int, targetG: Int, barColor: Int): LayoutElementBuilders.LayoutElement {
        val pct = (eatenG.toFloat() / targetG.coerceAtLeast(1).toFloat()).coerceIn(0.08f, 1.0f)
        val totalBarDp = 126f
        val fillDp = (totalBarDp * pct).coerceAtLeast(10f)

        val barTrack = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(totalBarDp))
            .setHeight(dp(5f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(0xFF1E293B.toInt()))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(3f)).build())
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Box.Builder()
                    .setWidth(dp(fillDp))
                    .setHeight(dp(5f))
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setBackground(
                                ModifiersBuilders.Background.Builder()
                                    .setColor(argb(barColor))
                                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(3f)).build())
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        return LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("$label ${eatenG}g / ${targetG}g")
                    .setMaxLines(1)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(9.5f))
                            .setColor(argb(0xFFCBD5E1.toInt()))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(1.5f)).build())
            .addContent(barTrack)
            .build()
    }

    fun wrapInHyperChronoRingTile(
        column: LayoutElementBuilders.Column,
        primaryArcColor: Int,
        primaryArcSweepDeg: Float,
        primaryAnchorDeg: Float = 0f,
        secondaryArcColor: Int? = null,
        secondaryArcSweepDeg: Float = 0f,
        secondaryAnchorDeg: Float = 180f,
        freshnessMs: Long = 15000L
    ): ListenableFuture<TileBuilders.Tile> {
        val trackArc = LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(degrees(0f))
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(359.5f))
                    .setThickness(dp(5f))
                    .setColor(argb(0xFF162032.toInt()))
                    .build()
            )
            .build()

        val primaryArc = LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(degrees(primaryAnchorDeg))
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(primaryArcSweepDeg.coerceIn(24f, 359.5f)))
                    .setThickness(dp(5.5f))
                    .setColor(argb(primaryArcColor))
                    .build()
            )
            .build()

        val rootBoxBuilder = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(4f)).setEnd(dp(4f)).setTop(dp(4f)).setBottom(dp(4f))
                            .build()
                    )
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(0xFF05070B.toInt()))
                            .build()
                    )
                    .build()
            )
            .addContent(trackArc)
            .addContent(primaryArc)

        if (secondaryArcColor != null && secondaryArcSweepDeg > 1f) {
            val secondaryArc = LayoutElementBuilders.Arc.Builder()
                .setAnchorAngle(degrees(secondaryAnchorDeg))
                .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
                .addContent(
                    LayoutElementBuilders.ArcLine.Builder()
                        .setLength(degrees(secondaryArcSweepDeg.coerceIn(20f, 175f)))
                        .setThickness(dp(5.5f))
                        .setColor(argb(secondaryArcColor))
                        .build()
                )
                .build()
            rootBoxBuilder.addContent(secondaryArc)
        }

        val innerBox = LayoutElementBuilders.Box.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(14f)).setEnd(dp(14f)).setTop(dp(12f)).setBottom(dp(12f))
                            .build()
                    )
                    .build()
            )
            .addContent(column)
            .build()

        rootBoxBuilder.addContent(innerBox)

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(TILE_RES_VERSION)
            .setFreshnessIntervalMillis(freshnessMs)
            .setTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(rootBoxBuilder.build())
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        return Futures.immediateFuture(tile)
    }
}

/**
 * KACHEL 1: RE:MK 1. Kraft — Direkte In-Tile Bedienung (−2,5 | 40 kg × 10 | +2,5 & ✓ SATZ) OHNE App-Wechsel!
 */
@Suppress("DEPRECATION")
class RemkTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val clickedId = RemkTileHelper.extractClickedId(requestParams)
        if (clickedId.isNotEmpty()) RemkTileHelper.handleDirectAction(this, clickedId, prefs)

        val nowClock = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())
        val liveBpm = prefs.getInt("last_live_bpm", 118).coerceIn(45, 220)
        val exName = prefs.getString("cur_ex_name", "Schrägbankdrücken") ?: "Schrägbankdrücken"
        val prevRef = prefs.getString("cur_prev_ref", "W1: 37,5×10") ?: "W1: 37,5×10"
        val setIdx = prefs.getInt("cur_set_idx", 1)
        val totalSets = prefs.getInt("cur_total_sets", 3).coerceAtLeast(1)
        val doneToday = prefs.getInt("done_sets_today", 0)
        val totToday = prefs.getInt("total_sets_today", 12).coerceAtLeast(1)
        val curKg = prefs.getFloat("cur_kg", 40.0f)
        val curReps = prefs.getInt("cur_reps", 10)
        val kgStr = if (curKg <= 0f) "BW" else if (curKg % 1.0f == 0.0f) "${curKg.toInt()}kg" else "${curKg.toString().replace(".", ",")}kg"
        val step = prefs.getFloat("weight_step", 2.5f)
        val stepStr = if (step % 1f == 0f) "${step.toInt()}" else step.toString().replace(".", ",")

        val sweep = ((doneToday.toFloat() / totToday.toFloat()) * 340f).coerceIn(110f, 359.5f)

        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("$nowClock • ❤️ $liveBpm")
                    .setMaxLines(1)
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(10f)).setColor(argb(0xFF94A3B8.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(
                RemkTileHelper.buildLaunchActivityPill(
                    packageName, TileOpenWorkoutActivity::class.java.name,
                    exName.take(18), "open_workout_title",
                    0x00000000, 0xFFF8FAFC.toInt(), null, 13.5f, 6f, 2f, 8f
                )
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("Satz $setIdx/$totalSets • ${prevRef.take(12)}")
                    .setMaxLines(1)
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(10.5f)).setColor(argb(0xFF00D4FF.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(5f)).build())
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(
                        RemkTileHelper.buildInTileActionPill(
                            "−$stepStr", "tile_kg_minus",
                            0xFF111827.toInt(), 0xFFFF5E00.toInt(), 0xFFFF5E00.toInt(), 12.5f, 9f, 8f, 14f
                        )
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(4f)).build())
                    .addContent(
                        RemkTileHelper.buildLaunchActivityPill(
                            packageName, TileOpenWorkoutActivity::class.java.name,
                            "$kgStr × $curReps", "open_workout_val",
                            0xFF111827.toInt(), 0xFFFFFFFF.toInt(), 0xFF334155.toInt(), 13f, 9f, 8f, 14f
                        )
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(4f)).build())
                    .addContent(
                        RemkTileHelper.buildInTileActionPill(
                            "+$stepStr", "tile_kg_plus",
                            0xFF111827.toInt(), 0xFFFF5E00.toInt(), 0xFFFF5E00.toInt(), 12.5f, 9f, 8f, 14f
                        )
                    )
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(
                RemkTileHelper.buildInTileActionPill(
                    "✓ SATZ $setIdx", "tile_log_set",
                    0xFF10B981.toInt(), 0xFF05070B.toInt(), null, 13.5f, 24f, 9f, 22f
                )
            )
            .build()

        return RemkTileHelper.wrapInHyperChronoRingTile(
            column = col,
            primaryArcColor = 0xFFFF5E00.toInt(),
            primaryArcSweepDeg = sweep,
            primaryAnchorDeg = -140f
        )
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RemkTileHelper.TILE_RES_VERSION).build())
    }
}

/**
 * KACHEL 2: RE:MK 2. Timer — Direkte In-Tile Steuerung (60s / 90s / 120s / +30s / Stopp) OHNE App-Wechsel!
 */
@Suppress("DEPRECATION")
class RemkTimerTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val clickedId = RemkTileHelper.extractClickedId(requestParams)
        if (clickedId.isNotEmpty()) RemkTileHelper.handleDirectAction(this, clickedId, prefs)

        val lastStart = prefs.getLong("last_rest_start_ms", 0L)
        val targetSec = prefs.getInt("rest_target_sec", 90).coerceAtLeast(30)
        val elapsed = if (lastStart > 0L) ((System.currentTimeMillis() - lastStart) / 1000L).toInt() else targetSec
        val remSec = (targetSec - elapsed).coerceAtLeast(0)
        val displaySec = if (remSec > 0) remSec else targetSec
        val m = (displaySec / 60).toString().padStart(2, '0')
        val s = (displaySec % 60).toString().padStart(2, '0')
        val liveBpm = prefs.getInt("last_live_bpm", 116).coerceIn(45, 220)
        val isReady = (liveBpm < 122)

        val sweep = if (remSec > 0) ((remSec.toFloat() / targetSec.toFloat()) * 330f).coerceIn(30f, 330f) else 240f

        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(if (remSec > 0) "SATZPAUSE LÄUFT" else "PAUSEN-TIMER")
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(10.5f)).setColor(argb(0xFFFF5E00.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("$m:$s")
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(33f)).setColor(argb(0xFFF8FAFC.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(if (isReady) "💚 $liveBpm BPM • Bereit" else "❤️ $liveBpm BPM • Erholung")
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(10.5f)).setColor(argb(if (isReady) 0xFF10B981.toInt() else 0xFFF59E0B.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .addContent(RemkTileHelper.buildInTileActionPill("60s", "tile_rest_60", 0xFF111827.toInt(), 0xFFF8FAFC.toInt(), if (targetSec == 60) 0xFFFF5E00.toInt() else 0xFF334155.toInt(), 11.5f, 9f, 7f, 15f))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(4f)).build())
                    .addContent(RemkTileHelper.buildInTileActionPill("90s", "tile_rest_90", 0xFF111827.toInt(), 0xFFF8FAFC.toInt(), if (targetSec == 90) 0xFFFF5E00.toInt() else 0xFF334155.toInt(), 11.5f, 9f, 7f, 15f))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(4f)).build())
                    .addContent(RemkTileHelper.buildInTileActionPill("120s", "tile_rest_120", 0xFF111827.toInt(), 0xFFF8FAFC.toInt(), if (targetSec == 120) 0xFFFF5E00.toInt() else 0xFF334155.toInt(), 11.5f, 8f, 7f, 15f))
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(5f)).build())
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .addContent(RemkTileHelper.buildInTileActionPill("+30s", "tile_rest_plus30", 0xFF111827.toInt(), 0xFF00D4FF.toInt(), 0xFF00D4FF.toInt(), 11f, 12f, 6f, 14f))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(5f)).build())
                    .addContent(RemkTileHelper.buildInTileActionPill("Stopp", "tile_rest_stop", 0xFF1F1218.toInt(), 0xFFEF4444.toInt(), 0xFFEF4444.toInt(), 11f, 12f, 6f, 14f))
                    .build()
            )
            .build()

        return RemkTileHelper.wrapInHyperChronoRingTile(
            column = col,
            primaryArcColor = 0xFFFF5E00.toInt(),
            primaryArcSweepDeg = sweep,
            primaryAnchorDeg = -160f,
            secondaryArcColor = 0xFF00D4FF.toInt(),
            secondaryArcSweepDeg = 85f,
            secondaryAnchorDeg = 45f,
            freshnessMs = 5000L
        )
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RemkTileHelper.TILE_RES_VERSION).build())
    }
}

/**
 * KACHEL 3: RE:MK 3. Makros — Cyan-Emerald Ring + 3 Makro-Balken (EW/KH/F aus ep/ec/ef) + In-Tile 💧 +250 ml!
 */
@Suppress("DEPRECATION")
class RemkNutritionTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val clickedId = RemkTileHelper.extractClickedId(requestParams)
        if (clickedId.isNotEmpty()) RemkTileHelper.handleDirectAction(this, clickedId, prefs)

        val targetKcal = prefs.getInt("personal_target_kcal", 2450).let { if (it in 1100..4600) it else 2450 }
        val openKcal = RemkTileHelper.getSanitizedOpenKcal(prefs)
        val eatenKcal = (targetKcal - openKcal).coerceIn(0, targetKcal)
        val drankL = prefs.getFloat("drank_liters", 0.0f)
        val waterStr = String.format("%.2f", drankL).replace(".", ",")

        val ep = prefs.getInt("macro_eaten_p", 148).coerceAtLeast(0)
        val tp = prefs.getInt("macro_target_p", 154).coerceAtLeast(40)
        val ec = prefs.getInt("macro_eaten_c", 190).coerceAtLeast(0)
        val tc = prefs.getInt("macro_target_c", 280).coerceAtLeast(50)
        val ef = prefs.getInt("macro_eaten_f", 54).coerceAtLeast(0)
        val tf = prefs.getInt("macro_target_f", 72).coerceAtLeast(25)

        val kcalSweep = ((eatenKcal.toFloat() / targetKcal.toFloat()) * 165f).coerceIn(45f, 165f)
        val waterSweep = ((drankL / 3.0f) * 165f).coerceIn(45f, 165f)

        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("RE:MK 360° • MAKROS")
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(9.5f)).setColor(argb(0xFF94A3B8.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(
                RemkTileHelper.buildLaunchActivityPill(
                    packageName, TileOpenNutritionActivity::class.java.name,
                    "${RemkTileHelper.formatGermanInt(eatenKcal)} / ${RemkTileHelper.formatGermanInt(targetKcal)} kcal",
                    "open_nutri_header",
                    0x00000000, 0xFFF8FAFC.toInt(), null, 14f, 6f, 2f, 8f
                )
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(3f)).build())
            .addContent(RemkTileHelper.buildMacroProgressRow("EW", ep, tp, 0xFFFF5E00.toInt()))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(3f)).build())
            .addContent(RemkTileHelper.buildMacroProgressRow("KH", ec, tc, 0xFF00D4FF.toInt()))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(3f)).build())
            .addContent(RemkTileHelper.buildMacroProgressRow("F", ef, tf, 0xFF10B981.toInt()))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(
                RemkTileHelper.buildInTileActionPill(
                    "💧 +250 ml • $waterStr L", "tile_water_250",
                    0xFF00D4FF.toInt(), 0xFF05070B.toInt(), null, 12f, 16f, 8f, 20f
                )
            )
            .build()

        return RemkTileHelper.wrapInHyperChronoRingTile(
            column = col,
            primaryArcColor = 0xFF10B981.toInt(),
            primaryArcSweepDeg = kcalSweep,
            primaryAnchorDeg = 10f,
            secondaryArcColor = 0xFF00D4FF.toInt(),
            secondaryArcSweepDeg = waterSweep,
            secondaryAnchorDeg = 190f
        )
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RemkTileHelper.TILE_RES_VERSION).build())
    }
}

/**
 * KACHEL 4: RE:MK 4. Cardio — Markenfreie Sportarten (In-Tile Switch) + Live-Puls + GPS Start!
 */
@Suppress("DEPRECATION")
class RemkCardioShopTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val clickedId = RemkTileHelper.extractClickedId(requestParams)
        if (clickedId.isNotEmpty()) RemkTileHelper.handleDirectAction(this, clickedId, prefs)

        val sportName = prefs.getString("cardio_sport_name", "Laufen") ?: "Laufen"
        val liveBpm = prefs.getInt("last_live_bpm", 118).coerceIn(45, 220)
        val lastDist = prefs.getString("last_cardio_dist", "5,00 km") ?: "5,00 km"
        val lastDur = prefs.getString("last_cardio_dur", "26:10") ?: "26:10"

        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                RemkTileHelper.buildInTileActionPill(
                    "${sportName.uppercase(Locale.GERMANY)} ⟳", "tile_cardio_next_sport",
                    0xFF111827.toInt(), 0xFF38BDF8.toInt(), 0xFF3B82F6.toInt(), 11f, 14f, 5f, 14f
                )
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(5f)).build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("❤️ $liveBpm BPM")
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(25f)).setColor(argb(0xFFF8FAFC.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("$lastDist • $lastDur")
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(11.5f)).setColor(argb(0xFF94A3B8.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(
                RemkTileHelper.buildLaunchActivityPill(
                    packageName, TileOpenCardioActivity::class.java.name,
                    "▶ START", "open_cardio_gps",
                    0xFF10B981.toInt(), 0xFF05070B.toInt(), null, 13.5f, 26f, 9f, 22f
                )
            )
            .build()

        return RemkTileHelper.wrapInHyperChronoRingTile(
            column = col,
            primaryArcColor = 0xFF3B82F6.toInt(),
            primaryArcSweepDeg = 165f,
            primaryAnchorDeg = 195f,
            secondaryArcColor = 0xFF10B981.toInt(),
            secondaryArcSweepDeg = 165f,
            secondaryAnchorDeg = 15f
        )
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RemkTileHelper.TILE_RES_VERSION).build())
    }
}
