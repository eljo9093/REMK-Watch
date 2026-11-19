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
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.tiles.TimelineBuilders
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.text.NumberFormat
import java.util.Locale

/**
 * RE:MK 4 Wear OS 5 Kacheln im neuen Carbon-Dark OLED + Progress-Arc Design (v33.1 Signature Pro)
 * - 1:1 Umsetzung des neuen RE:MK Design-Systems (#05070B Hintergrund, #121722 Karten mit #26324A Rand,
 *   umlaufende farbige Progress-Bögen via LayoutElementBuilders.Arc + ArcLine).
 * - Alle Buttons arbeiten über ultraschnelle NoDisplay-Micro-Activities (< 50 ms, Haptik-Vibration,
 *   sofortiger Tile- & Zifferblatt-Complication-Refresh).
 */
abstract class BaseTileQuickActionActivity(private val actionId: String, private val toastMsg: String = "") : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vib != null && vib.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createOneShot(40L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(40L)
                }
            }
        } catch (e: Exception) {}

        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        RemkTileHelper.handleAction(this, actionId, prefs)
        RemkTileHelper.requestAllTilesRefresh(this)
        RemkComplicationHelper.requestAllComplicationsRefresh(this)

        if (toastMsg.isNotEmpty()) {
            Toast.makeText(applicationContext, toastMsg, Toast.LENGTH_SHORT).show()
        }
        finish()
        overridePendingTransition(0, 0)
    }
}

class TileOpenGpsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = Intent(this, WearMainActivity::class.java).apply {
            putExtra("start_page", 2)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(i)
        finish()
        overridePendingTransition(0, 0)
    }
}

class TileKgMinusActivity : BaseTileQuickActionActivity("tile_kg_minus")
class TileKgPlusActivity : BaseTileQuickActionActivity("tile_kg_plus")
class TileLogSetActivity : BaseTileQuickActionActivity("tile_log_set", "✓ Satz geloggt! (Pause läuft)")
class TileRest60Activity : BaseTileQuickActionActivity("tile_rest_60", "⏱️ 60s Pause gestartet")
class TileRest90Activity : BaseTileQuickActionActivity("tile_rest_90", "⏱️ 90s Pause gestartet")
class TileRest120Activity : BaseTileQuickActionActivity("tile_rest_120", "⏱️ 120s Pause gestartet")
class TileUndoSetActivity : BaseTileQuickActionActivity("tile_undo_set", "↩️ Letzter Satz rückgängig")
class TileWater250Activity : BaseTileQuickActionActivity("tile_water_250", "💧 +250 ml Wasser")
class TileWater500Activity : BaseTileQuickActionActivity("tile_water_500", "💧 +500 ml Wasser")
class TileMealCheckActivity : BaseTileQuickActionActivity("tile_meal_check", "🍎 Mahlzeit abgehakt!")
class TileCloudSyncActivity : BaseTileQuickActionActivity("tile_cloud_sync", "🔄 Cloud-Sync läuft...")

@Suppress("DEPRECATION")
object RemkTileHelper {

    const val TILE_RES_VERSION = "v33_1_carbon_arcs"

    fun getSanitizedOpenKcal(prefs: android.content.SharedPreferences): Int {
        val raw = prefs.getInt("open_kcal", 1850)
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

    fun requestAllTilesRefresh(context: Context) {
        try {
            val updater = TileService.getUpdater(context)
            updater.requestUpdate(RemkTileService::class.java)
            updater.requestUpdate(RemkTimerTileService::class.java)
            updater.requestUpdate(RemkNutritionTileService::class.java)
            updater.requestUpdate(RemkCardioShopTileService::class.java)
        } catch (e: Exception) {}
    }

    fun getActionActivityClassName(clickId: String): String {
        return when (clickId) {
            "tile_kg_minus" -> TileKgMinusActivity::class.java.name
            "tile_kg_plus" -> TileKgPlusActivity::class.java.name
            "tile_log_set" -> TileLogSetActivity::class.java.name
            "tile_rest_60" -> TileRest60Activity::class.java.name
            "tile_rest_90" -> TileRest90Activity::class.java.name
            "tile_rest_120" -> TileRest120Activity::class.java.name
            "tile_undo_set" -> TileUndoSetActivity::class.java.name
            "tile_water_250" -> TileWater250Activity::class.java.name
            "tile_water_500" -> TileWater500Activity::class.java.name
            "tile_meal_check" -> TileMealCheckActivity::class.java.name
            "tile_cloud_sync" -> TileCloudSyncActivity::class.java.name
            "tile_open_gps" -> TileOpenGpsActivity::class.java.name
            else -> WearMainActivity::class.java.name
        }
    }

    fun handleAction(context: Context, actionId: String, prefs: android.content.SharedPreferences) {
        val ed = prefs.edit()
        when (actionId) {
            "tile_kg_minus" -> {
                val cur = prefs.getFloat("cur_kg", 40f)
                val nextKg = (cur - 2.5f).coerceAtLeast(0f)
                ed.putFloat("cur_kg", nextKg)
                WearOfflineSyncQueue.enqueueEvent(context, "WATCH_KG_CHANGED", "kg=$nextKg")
            }
            "tile_kg_plus" -> {
                val cur = prefs.getFloat("cur_kg", 40f)
                val nextKg = cur + 2.5f
                ed.putFloat("cur_kg", nextKg)
                WearOfflineSyncQueue.enqueueEvent(context, "WATCH_KG_CHANGED", "kg=$nextKg")
            }
            "tile_log_set" -> {
                val dIdx = prefs.getInt("day_idx", 0)
                val exId = prefs.getString("cur_ex_id", "p1") ?: "p1"
                val sIdx = prefs.getInt("cur_set_idx", 1)
                val totS = prefs.getInt("cur_total_sets", 3)
                val curKg = prefs.getFloat("cur_kg", 40f)
                val curReps = prefs.getInt("cur_reps", 10)
                val doneToday = prefs.getInt("done_sets_today", 0) + 1
                val movedVol = prefs.getInt("today_volume_kg", 0) + (curKg * curReps).toInt()
                ed.putInt("done_sets_today", doneToday)
                ed.putInt("today_volume_kg", movedVol)
                try {
                    val mapObj = org.json.JSONObject(prefs.getString("completed_sets_map_json", "{}") ?: "{}")
                    mapObj.put("${dIdx}_${exId}", sIdx.coerceAtMost(totS))
                    ed.putString("completed_sets_map_json", mapObj.toString())
                } catch (e: Exception) {}
                if (sIdx < totS) {
                    ed.putInt("cur_set_idx", sIdx + 1)
                } else {
                    ed.putInt("cur_set_idx", 1)
                    val nextEx = (prefs.getInt("cur_ex_idx", 0) + 1) % 4
                    ed.putInt("cur_ex_idx", nextEx)
                }
                ed.putLong("last_rest_start_ms", System.currentTimeMillis())
                ed.putInt("rest_target_sec", 90)
                WearOfflineSyncQueue.enqueueEvent(
                    context,
                    "WATCH_SET_COMPLETED",
                    "exId=$exId&kg=$curKg&reps=$curReps&setIdx=$sIdx",
                    mapOf("exId" to exId, "setIdx" to sIdx, "kg" to curKg.toDouble(), "reps" to curReps, "rir" to 2)
                )
            }
            "tile_rest_60" -> {
                ed.putLong("last_rest_start_ms", System.currentTimeMillis())
                ed.putInt("rest_target_sec", 60)
            }
            "tile_rest_90" -> {
                ed.putLong("last_rest_start_ms", System.currentTimeMillis())
                ed.putInt("rest_target_sec", 90)
            }
            "tile_rest_120" -> {
                ed.putLong("last_rest_start_ms", System.currentTimeMillis())
                ed.putInt("rest_target_sec", 120)
            }
            "tile_undo_set" -> {
                val doneToday = (prefs.getInt("done_sets_today", 1) - 1).coerceAtLeast(0)
                val sIdx = (prefs.getInt("cur_set_idx", 2) - 1).coerceAtLeast(1)
                ed.putInt("done_sets_today", doneToday)
                ed.putInt("cur_set_idx", sIdx)
                WearOfflineSyncQueue.removeLastSetEventIfUndo(context)
            }
            "tile_water_250" -> {
                val curL = prefs.getFloat("drank_liters", 0.0f) + 0.25f
                ed.putFloat("drank_liters", curL)
                WearOfflineSyncQueue.enqueueEvent(
                    context,
                    "WATCH_WATER_ADDED",
                    "addedMl=250&drankLiters=$curL",
                    mapOf("addedMl" to 250, "drankLiters" to curL.toDouble())
                )
            }
            "tile_water_500" -> {
                val curL = prefs.getFloat("drank_liters", 0.0f) + 0.50f
                ed.putFloat("drank_liters", curL)
                WearOfflineSyncQueue.enqueueEvent(
                    context,
                    "WATCH_WATER_ADDED",
                    "addedMl=500&drankLiters=$curL",
                    mapOf("addedMl" to 500, "drankLiters" to curL.toDouble())
                )
            }
            "tile_meal_check" -> {
                val curOpen = getSanitizedOpenKcal(prefs)
                val nextOpen = (curOpen - 550).coerceAtLeast(0)
                ed.putInt("open_kcal", nextOpen)
                WearOfflineSyncQueue.enqueueEvent(
                    context,
                    "WATCH_MEAL_TOGGLED",
                    "slotIdx=0&done=1&openKcal=$nextOpen",
                    mapOf("slotIdx" to 0, "done" to true)
                )
            }
            "tile_cloud_sync" -> {
                WearOfflineSyncQueue.pushWatchStateToCloudAsync(context)
            }
        }
        ed.apply()
    }

    fun buildCleanPillBtn(
        pkg: String,
        label: String,
        clickId: String,
        bgColor: Int,
        txtColor: Int,
        borderColor: Int? = null,
        fontSp: Float = 13.5f,
        padH: Float = 18f,
        padV: Float = 8f,
        cornerRadiusDp: Float = 20f
    ): LayoutElementBuilders.LayoutElement {
        val targetCls = getActionActivityClassName(clickId)
        val launchClickable = ModifiersBuilders.Clickable.Builder()
            .setId(clickId)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(pkg)
                            .setClassName(targetCls)
                            .build()
                    )
                    .build()
            )
            .build()

        val modBuilder = ModifiersBuilders.Modifiers.Builder()
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
            modBuilder.setBorder(
                ModifiersBuilders.Border.Builder()
                    .setWidth(dp(1.2f))
                    .setColor(argb(borderColor))
                    .build()
            )
        }

        return LayoutElementBuilders.Box.Builder()
            .setModifiers(modBuilder.build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(label)
                    .setMaxLines(1)
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

    fun buildCarbonCardBox(
        content: LayoutElementBuilders.LayoutElement,
        padH: Float = 16f,
        padV: Float = 7f,
        borderColor: Int = 0xFF232E44.toInt()
    ): LayoutElementBuilders.LayoutElement {
        return LayoutElementBuilders.Box.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(padH)).setEnd(dp(padH)).setTop(dp(padV)).setBottom(dp(padV))
                            .build()
                    )
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(0xFF121722.toInt()))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(16f)).build())
                            .build()
                    )
                    .setBorder(
                        ModifiersBuilders.Border.Builder()
                            .setWidth(dp(1f))
                            .setColor(argb(borderColor))
                            .build()
                    )
                    .build()
            )
            .addContent(content)
            .build()
    }

    /**
     * Erzeugt das neue RE:MK Carbon-Dark Tile-Grundgerüst mit umlaufendem leuchtenden Progress-Ring (ArcLine)!
     */
    fun wrapInCarbonRingTile(
        column: LayoutElementBuilders.Column,
        primaryArcColor: Int,
        primaryArcSweepDeg: Float,
        primaryAnchorDeg: Float = 0f,
        secondaryArcColor: Int? = null,
        secondaryArcSweepDeg: Float = 0f,
        secondaryAnchorDeg: Float = 180f
    ): ListenableFuture<TileBuilders.Tile> {
        // Hintergrund-Track-Ring (360° dunkles Gunmetal #172030)
        val trackArc = LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(degrees(0f))
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(359.5f))
                    .setThickness(dp(5f))
                    .setColor(argb(0xFF172030.toInt()))
                    .build()
            )
            .build()

        // Primärer farbiger Fortschritts-Bogen
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
                            .setStart(dp(5f)).setEnd(dp(5f)).setTop(dp(5f)).setBottom(dp(5f))
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

        // Sicherer Innenabstand für Inhalt innerhalb des leuchtenden Rings
        val innerContentBox = LayoutElementBuilders.Box.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(16f)).setEnd(dp(16f)).setTop(dp(14f)).setBottom(dp(14f))
                            .build()
                    )
                    .build()
            )
            .addContent(column)
            .build()

        rootBoxBuilder.addContent(innerContentBox)

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(TILE_RES_VERSION)
            .setFreshnessIntervalMillis(20000L)
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
 * KACHEL 1: RE:MK 1. Kraft (Carbon-Dark + Orange Progress-Ring, clean wie Mockup)
 */
@Suppress("DEPRECATION")
class RemkTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val exName = prefs.getString("cur_ex_name", "Schrägbankdrücken") ?: "Schrägbankdrücken"
        val setIdx = prefs.getInt("cur_set_idx", 1)
        val totalSets = prefs.getInt("cur_total_sets", 3).coerceAtLeast(1)
        val doneToday = prefs.getInt("done_sets_today", 0)
        val totToday = prefs.getInt("total_sets_today", 12).coerceAtLeast(1)
        val curKg = prefs.getFloat("cur_kg", 40.0f)
        val kgStr = if (curKg <= 0f) "BW" else if (curKg % 1.0f == 0.0f) "${curKg.toInt()} kg" else "${curKg.toString().replace(".", ",")} kg"

        val progressSweep = ((doneToday.toFloat() / totToday.toFloat()) * 360f).coerceIn(110f, 359.5f)

        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("🏋️")
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(15f)).setColor(argb(0xFFFF5E00.toInt())).build())
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(2f)).build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(exName.take(18))
                    .setMaxLines(1)
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(14.5f))
                            .setColor(argb(0xFFF8FAFC.toInt()))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(2f)).build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("Satz $setIdx/$totalSets • $kgStr")
                    .setMaxLines(1)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(12f))
                            .setColor(argb(0xFF94A3B8.toInt()))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_MEDIUM)
                            .build()
                    )
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(10f)).build())
            .addContent(
                RemkTileHelper.buildCleanPillBtn(
                    pkg = packageName,
                    label = "✓  SATZ",
                    clickId = "tile_log_set",
                    bgColor = 0xFF10B981.toInt(),
                    txtColor = 0xFF05070B.toInt(),
                    fontSp = 14f,
                    padH = 24f,
                    padV = 9f,
                    cornerRadiusDp = 22f
                )
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(7f)).build())
            .addContent(
                RemkTileHelper.buildCleanPillBtn(
                    pkg = packageName,
                    label = "Öffnen",
                    clickId = "open_main_watch",
                    bgColor = 0xFF121722.toInt(),
                    txtColor = 0xFFF8FAFC.toInt(),
                    borderColor = 0xFF28354D.toInt(),
                    fontSp = 12.5f,
                    padH = 26f,
                    padV = 7f,
                    cornerRadiusDp = 20f
                )
            )
            .build()

        return RemkTileHelper.wrapInCarbonRingTile(
            column = col,
            primaryArcColor = 0xFFFF5E00.toInt(),
            primaryArcSweepDeg = progressSweep,
            primaryAnchorDeg = 0f
        )
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RemkTileHelper.TILE_RES_VERSION).build())
    }
}

/**
 * KACHEL 2: RE:MK 2. Timer (Carbon-Dark + Rot/Amber Countdown-Ring, clean wie Mockup)
 */
@Suppress("DEPRECATION")
class RemkTimerTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val lastStart = prefs.getLong("last_rest_start_ms", 0L)
        val targetSec = prefs.getInt("rest_target_sec", 90).coerceAtLeast(30)
        val elapsed = ((System.currentTimeMillis() - lastStart) / 1000L).toInt()
        val remSec = (targetSec - elapsed).coerceAtLeast(0)
        val displaySec = if (remSec > 0) remSec else targetSec
        val m = (displaySec / 60).toString().padStart(2, '0')
        val s = (displaySec % 60).toString().padStart(2, '0')
        val timeTxt = "$m:$s"

        val sweepDeg = if (remSec > 0) {
            ((remSec.toFloat() / targetSec.toFloat()) * 340f).coerceIn(30f, 340f)
        } else {
            245f
        }
        val ringCol = if (remSec in 1..15) 0xFFEF4444.toInt() else 0xFFFF5E00.toInt()

        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(if (remSec > 0) "PAUSE LÄUFT" else "PAUSE")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(12f))
                            .setColor(argb(0xFFEF4444.toInt()))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(timeTxt)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(33f))
                            .setColor(argb(0xFFF8FAFC.toInt()))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(10f)).build())
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(
                        RemkTileHelper.buildCleanPillBtn(
                            packageName, "60s", "tile_rest_60",
                            0xFF121722.toInt(), 0xFFF8FAFC.toInt(),
                            if (targetSec == 60) 0xFFFF5E00.toInt() else 0xFF28354D.toInt(),
                            12f, 10f, 7f, 16f
                        )
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(5f)).build())
                    .addContent(
                        RemkTileHelper.buildCleanPillBtn(
                            packageName, "90s", "tile_rest_90",
                            0xFF121722.toInt(), 0xFFF8FAFC.toInt(),
                            if (targetSec == 90) 0xFFFF5E00.toInt() else 0xFF28354D.toInt(),
                            12f, 10f, 7f, 16f
                        )
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(5f)).build())
                    .addContent(
                        RemkTileHelper.buildCleanPillBtn(
                            packageName, "120s", "tile_rest_120",
                            0xFF121722.toInt(), 0xFFF8FAFC.toInt(),
                            if (targetSec == 120) 0xFFFF5E00.toInt() else 0xFF28354D.toInt(),
                            12f, 9f, 7f, 16f
                        )
                    )
                    .build()
            )
            .build()

        return RemkTileHelper.wrapInCarbonRingTile(
            column = col,
            primaryArcColor = ringCol,
            primaryArcSweepDeg = sweepDeg,
            primaryAnchorDeg = -120f
        )
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RemkTileHelper.TILE_RES_VERSION).build())
    }
}

/**
 * KACHEL 3: RE:MK 3. Makros (Carbon-Dark + Dual-Ring Grün/Blau, 2 Clean Cards + Cyan +250 ml Pill)
 */
@Suppress("DEPRECATION")
class RemkNutritionTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val drankL = prefs.getFloat("drank_liters", 0.0f)
        val targetKcal = prefs.getInt("personal_target_kcal", 2450).let { if (it in 1100..4600) it else 2450 }
        val openKcal = RemkTileHelper.getSanitizedOpenKcal(prefs)
        val eatenKcal = (targetKcal - openKcal).coerceIn(0, targetKcal)
        val kcalStr = RemkTileHelper.formatGermanInt(openKcal)
        val waterStr = String.format("%.2f", drankL).replace(".", ",")

        val kcalArcSweep = ((eatenKcal.toFloat() / targetKcal.toFloat()) * 165f).coerceIn(55f, 165f)
        val waterArcSweep = ((drankL / 3.0f) * 165f).coerceIn(45f, 165f)

        val kcalInner = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("$kcalStr kcal")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(16f))
                            .setColor(argb(0xFFF8FAFC.toInt()))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("offen")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(11f))
                            .setColor(argb(0xFF94A3B8.toInt()))
                            .build()
                    )
                    .build()
            )
            .build()

        val waterInner = LayoutElementBuilders.Text.Builder()
            .setText("$waterStr L Wasser")
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(13f))
                    .setColor(argb(0xFFF8FAFC.toInt()))
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                    .build()
            )
            .build()

        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(RemkTileHelper.buildCarbonCardBox(kcalInner, padH = 18f, padV = 6f, borderColor = 0xFF1F352E.toInt()))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(5f)).build())
            .addContent(RemkTileHelper.buildCarbonCardBox(waterInner, padH = 16f, padV = 6f, borderColor = 0xFF1E3247.toInt()))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(7f)).build())
            .addContent(
                RemkTileHelper.buildCleanPillBtn(
                    pkg = packageName,
                    label = "+250 ml",
                    clickId = "tile_water_250",
                    bgColor = 0xFF38BDF8.toInt(),
                    txtColor = 0xFF05070B.toInt(),
                    fontSp = 13.5f,
                    padH = 22f,
                    padV = 8f,
                    cornerRadiusDp = 20f
                )
            )
            .build()

        return RemkTileHelper.wrapInCarbonRingTile(
            column = col,
            primaryArcColor = 0xFF10B981.toInt(),
            primaryArcSweepDeg = kcalArcSweep,
            primaryAnchorDeg = 190f,
            secondaryArcColor = 0xFF38BDF8.toInt(),
            secondaryArcSweepDeg = waterArcSweep,
            secondaryAnchorDeg = 10f
        )
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RemkTileHelper.TILE_RES_VERSION).build())
    }
}

/**
 * KACHEL 4: RE:MK 4. Cardio (Carbon-Dark + Cyan Ring, großer Live-Puls & GPS Start)
 */
@Suppress("DEPRECATION")
class RemkCardioShopTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val liveBpm = prefs.getInt("last_live_bpm", 118).coerceIn(45, 220)

        val hrRow = LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("$liveBpm")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(34f))
                            .setColor(argb(0xFFF8FAFC.toInt()))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(5f)).build())
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText("❤️")
                            .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(12f)).build())
                            .build()
                    )
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText("BPM")
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder()
                                    .setSize(sp(11f))
                                    .setColor(argb(0xFF94A3B8.toInt()))
                                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("LIVE PULS")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(11.5f))
                            .setColor(argb(0xFF94A3B8.toInt()))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(hrRow)
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(10f)).build())
            .addContent(
                RemkTileHelper.buildCleanPillBtn(
                    pkg = packageName,
                    label = "GPS Start",
                    clickId = "tile_open_gps",
                    bgColor = 0xFF38BDF8.toInt(),
                    txtColor = 0xFF05070B.toInt(),
                    fontSp = 13.5f,
                    padH = 22f,
                    padV = 9f,
                    cornerRadiusDp = 22f
                )
            )
            .build()

        return RemkTileHelper.wrapInCarbonRingTile(
            column = col,
            primaryArcColor = 0xFF38BDF8.toInt(),
            primaryArcSweepDeg = 325f,
            primaryAnchorDeg = -162f
        )
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RemkTileHelper.TILE_RES_VERSION).build())
    }
}
