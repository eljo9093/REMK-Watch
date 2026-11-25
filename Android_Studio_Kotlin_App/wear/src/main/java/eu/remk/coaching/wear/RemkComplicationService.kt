package eu.remk.coaching.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlin.math.cos
import kotlin.math.sin

object RemkComplicationHelper {

    fun buildLaunchPendingIntent(context: Context, reqCode: Int, targetPage: Int = 0): PendingIntent {
        val intent = Intent(context, WearMainActivity::class.java).apply {
            putExtra("start_page", targetPage)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun requestAllComplicationsRefresh(context: Context) {
        val classes = listOf(
            RemkLevelXpComplicationService::class.java,
            RemkRankBadgeComplicationService::class.java,
            RemkSetsComplicationService::class.java,
            RemkKcalComplicationService::class.java,
            RemkWaterComplicationService::class.java,
            RemkActiveSetComplicationService::class.java,
            RemkRadarImageComplicationService::class.java,
            RemkStatusBarsImageComplicationService::class.java
        )
        for (cls in classes) {
            try {
                ComplicationDataSourceUpdateRequester.create(
                    context.applicationContext,
                    ComponentName(context.applicationContext, cls)
                ).requestUpdateAll()
            } catch (e: Exception) {}
        }
    }

    fun computeRankCode(lv: Int): String = when {
        lv >= 46 -> "S+"
        lv >= 37 -> "S"
        lv >= 29 -> "A"
        lv >= 21 -> "B"
        lv >= 14 -> "C"
        lv >= 8 -> "D"
        else -> "E"
    }

    fun readRadarAxes(prefs: android.content.SharedPreferences): FloatArray {
        val raw = prefs.getString("lvl_radar_csv", "72,64,58,41,86,79") ?: "72,64,58,41,86,79"
        val parts = raw.split(",")
        return FloatArray(6) { i ->
            parts.getOrNull(i)?.trim()?.toFloatOrNull()?.coerceIn(10f, 100f) ?: 65f
        }
    }

    fun renderLiveHexRadarBitmap(axes: FloatArray): Bitmap {
        val size = 180
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val maxR = size * 0.36f
        val labels = arrayOf("STR", "ATH", "END", "MOB", "REC", "CON")

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#2A3B55")
            strokeWidth = 1.6f
        }
        val spokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#1E2C42")
            strokeWidth = 1.2f
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(cx - maxR, cy - maxR, cx + maxR, cy + maxR,
                Color.parseColor("#6600D4FF"), Color.parseColor("#6610B981"), Shader.TileMode.CLAMP)
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#00D4FF")
            strokeWidth = 3.0f
        }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#10B981")
        }
        val lblPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            textAlign = Paint.Align.CENTER
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        for (sc in floatArrayOf(0.4f, 0.72f, 1.0f)) {
            val gp = Path()
            for (i in 0 until 6) {
                val a = Math.toRadians((i * 60 - 90).toDouble())
                val x = cx + (maxR * sc * cos(a)).toFloat()
                val y = cy + (maxR * sc * sin(a)).toFloat()
                if (i == 0) gp.moveTo(x, y) else gp.lineTo(x, y)
            }
            gp.close()
            c.drawPath(gp, gridPaint)
        }

        val dataPath = Path()
        for (i in 0 until 6) {
            val a = Math.toRadians((i * 60 - 90).toDouble())
            val gx = cx + (maxR * cos(a)).toFloat()
            val gy = cy + (maxR * sin(a)).toFloat()
            c.drawLine(cx, cy, gx, gy, spokePaint)

            val norm = (axes.getOrElse(i) { 65f } / 100f).coerceIn(0.2f, 1f)
            val dx = cx + (maxR * norm * cos(a)).toFloat()
            val dy = cy + (maxR * norm * sin(a)).toFloat()
            if (i == 0) dataPath.moveTo(dx, dy) else dataPath.lineTo(dx, dy)

            val lx = cx + ((maxR + 16f) * cos(a)).toFloat()
            val ly = cy + ((maxR + 16f) * sin(a)).toFloat() + 4.5f
            c.drawText(labels[i], lx, ly, lblPaint)
        }
        dataPath.close()
        c.drawPath(dataPath, fillPaint)
        c.drawPath(dataPath, strokePaint)

        for (i in 0 until 6) {
            val a = Math.toRadians((i * 60 - 90).toDouble())
            val norm = (axes.getOrElse(i) { 65f } / 100f).coerceIn(0.2f, 1f)
            c.drawCircle(cx + (maxR * norm * cos(a)).toFloat(), cy + (maxR * norm * sin(a)).toFloat(), 3.2f, dotPaint)
        }
        return bmp
    }

    fun renderLiveStatusBarsBitmap(axes: FloatArray): Bitmap {
        val w = 282
        val h = 124
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val names = arrayOf("STR", "ATH", "END", "MOB", "REC", "CON")
        val cols = arrayOf("#00D4FF", "#FF5E00", "#10B981", "#38BDF8", "#10B981", "#F59E0B")

        val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 16f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            style = Paint.Style.FILL
        }
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val rect = RectF()

        // Left column: indices 0, 1, 2 | Right column: indices 3, 4, 5
        for (i in 0 until 6) {
            val colIdx = i / 3
            val rowIdx = i % 3
            val x0 = if (colIdx == 0) 14f else 152f
            val colW = 116f
            val yBase = 24f + rowIdx * 38f
            val score = axes.getOrElse(i) { 65f }.toInt().coerceIn(0, 99)

            c.drawText(names[i], x0, yBase, txtPaint)
            c.drawText("$score", x0 + colW, yBase, valPaint)

            val barY = yBase + 6f
            rect.set(x0, barY, x0 + colW, barY + 8f)
            c.drawRoundRect(rect, 4f, 4f, trackPaint)

            barPaint.color = Color.parseColor(cols[i])
            val fillW = (colW * (score / 100f)).coerceAtLeast(10f)
            rect.set(x0, barY, x0 + fillW, barY + 8f)
            c.drawRoundRect(rect, 4f, 4f, barPaint)
        }
        return bmp
    }
}

class RemkRadarImageComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SMALL_IMAGE) return null
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val bmp = RemkComplicationHelper.renderLiveHexRadarBitmap(RemkComplicationHelper.readRadarAxes(prefs))
        val tap = RemkComplicationHelper.buildLaunchPendingIntent(this, 320, targetPage = 2)
        return SmallImageComplicationData.Builder(
            smallImage = SmallImage.Builder(Icon.createWithBitmap(bmp), SmallImageType.PHOTO).build(),
            contentDescription = PlainComplicationText.Builder("RE:MK 6-Achsen-Radar").build()
        ).setTapAction(tap).build()
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE) return null
        val bmp = RemkComplicationHelper.renderLiveHexRadarBitmap(floatArrayOf(84f, 72f, 76f, 68f, 86f, 79f))
        return SmallImageComplicationData.Builder(
            smallImage = SmallImage.Builder(Icon.createWithBitmap(bmp), SmallImageType.PHOTO).build(),
            contentDescription = PlainComplicationText.Builder("RE:MK 6-Achsen-Radar").build()
        ).build()
    }
}

class RemkStatusBarsImageComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SMALL_IMAGE) return null
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val bmp = RemkComplicationHelper.renderLiveStatusBarsBitmap(RemkComplicationHelper.readRadarAxes(prefs))
        val tap = RemkComplicationHelper.buildLaunchPendingIntent(this, 321, targetPage = 2)
        return SmallImageComplicationData.Builder(
            smallImage = SmallImage.Builder(Icon.createWithBitmap(bmp), SmallImageType.PHOTO).build(),
            contentDescription = PlainComplicationText.Builder("RE:MK 6-Werte Statusfenster").build()
        ).setTapAction(tap).build()
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE) return null
        val bmp = RemkComplicationHelper.renderLiveStatusBarsBitmap(floatArrayOf(84f, 72f, 76f, 68f, 86f, 79f))
        return SmallImageComplicationData.Builder(
            smallImage = SmallImage.Builder(Icon.createWithBitmap(bmp), SmallImageType.PHOTO).build(),
            contentDescription = PlainComplicationText.Builder("RE:MK 6-Werte Statusfenster").build()
        ).build()
    }
}

class RemkLevelXpComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val lv = prefs.getInt("lvl_overall", 24).coerceIn(1, 60)
        val xpPct = prefs.getInt("lvl_xp_pct", 64).coerceIn(5, 100)
        val txt = "LV.$lv"
        val tap = RemkComplicationHelper.buildLaunchPendingIntent(this, 310, targetPage = 2)

        return when (request.complicationType) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = xpPct.toFloat(), min = 0f, max = 100f,
                contentDescription = PlainComplicationText.Builder("RE:MK Level $lv").build()
            ).setText(PlainComplicationText.Builder(txt).build()).setTitle(PlainComplicationText.Builder("XP").build()).setTapAction(tap).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(txt).build(),
                contentDescription = PlainComplicationText.Builder("RE:MK Level $lv").build()
            ).setTitle(PlainComplicationText.Builder("XP").build()).setTapAction(tap).build()
            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 64f, min = 0f, max = 100f,
                contentDescription = PlainComplicationText.Builder("RE:MK Level 24").build()
            ).setText(PlainComplicationText.Builder("LV.24").build()).setTitle(PlainComplicationText.Builder("XP").build()).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("LV.24").build(),
                contentDescription = PlainComplicationText.Builder("RE:MK Level 24").build()
            ).setTitle(PlainComplicationText.Builder("XP").build()).build()
            else -> null
        }
    }
}

class RemkRankBadgeComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val lv = prefs.getInt("lvl_overall", 24).coerceIn(1, 60)
        val rank = prefs.getString("lvl_rank_code", "")?.takeIf { it.isNotEmpty() } ?: RemkComplicationHelper.computeRankCode(lv)
        val label = "LV. $lv • $rank-RANG"
        val tap = RemkComplicationHelper.buildLaunchPendingIntent(this, 311, targetPage = 2)

        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(label).build(),
            contentDescription = PlainComplicationText.Builder("RE:MK $label").build()
        ).setTitle(PlainComplicationText.Builder("LV.$lv").build()).setTapAction(tap).build()
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder("LV. 24 • A-RANG").build(),
            contentDescription = PlainComplicationText.Builder("RE:MK LV. 24 A-Rang").build()
        ).setTitle(PlainComplicationText.Builder("LV.24").build()).build()
    }
}

class RemkSetsComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val done = prefs.getInt("done_sets_today", 0).coerceAtLeast(0)
        val total = prefs.getInt("total_sets_today", 12).coerceAtLeast(1)
        val txt = "$done/$total"
        val tap = RemkComplicationHelper.buildLaunchPendingIntent(this, 301, targetPage = 0)

        return when (request.complicationType) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = done.toFloat().coerceAtMost(total.toFloat()), min = 0f, max = total.toFloat(),
                contentDescription = PlainComplicationText.Builder("RE:MK Sätze $txt").build()
            ).setText(PlainComplicationText.Builder(txt).build()).setTitle(PlainComplicationText.Builder("SÄTZE").build()).setTapAction(tap).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(txt).build(),
                contentDescription = PlainComplicationText.Builder("RE:MK Sätze $txt").build()
            ).setTitle(PlainComplicationText.Builder("SÄTZE").build()).setTapAction(tap).build()
            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 8f, min = 0f, max = 12f,
                contentDescription = PlainComplicationText.Builder("RE:MK Sätze 8/12").build()
            ).setText(PlainComplicationText.Builder("8/12").build()).setTitle(PlainComplicationText.Builder("SÄTZE").build()).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("8/12").build(),
                contentDescription = PlainComplicationText.Builder("RE:MK Sätze 8/12").build()
            ).setTitle(PlainComplicationText.Builder("SÄTZE").build()).build()
            else -> null
        }
    }
}

class RemkKcalComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val target = prefs.getInt("personal_target_kcal", 2450).let { if (it in 1100..4600) it else 2450 }
        val openKcal = RemkTileHelper.getSanitizedOpenKcal(prefs)
        val eaten = (target - openKcal).coerceIn(0, target)
        val txt = "$openKcal"
        val tap = RemkComplicationHelper.buildLaunchPendingIntent(this, 302, targetPage = 1)

        return when (request.complicationType) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = eaten.toFloat(), min = 0f, max = target.toFloat(),
                contentDescription = PlainComplicationText.Builder("Kalorien $eaten/$target").build()
            ).setText(PlainComplicationText.Builder(txt).build()).setTitle(PlainComplicationText.Builder("KCAL").build()).setTapAction(tap).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(txt).build(),
                contentDescription = PlainComplicationText.Builder("Kalorien $eaten/$target").build()
            ).setTitle(PlainComplicationText.Builder("KCAL").build()).setTapAction(tap).build()
            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 1680f, min = 0f, max = 2450f,
                contentDescription = PlainComplicationText.Builder("Kalorien 1680").build()
            ).setText(PlainComplicationText.Builder("770").build()).setTitle(PlainComplicationText.Builder("KCAL").build()).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("770").build(),
                contentDescription = PlainComplicationText.Builder("Kalorien 770").build()
            ).setTitle(PlainComplicationText.Builder("KCAL").build()).build()
            else -> null
        }
    }
}

class RemkWaterComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val drankL = prefs.getFloat("drank_liters", 0.0f).coerceAtLeast(0f)
        val targetL = 3.0f
        val waterTxt = "${String.format("%.2f", drankL).replace(".", ",")} L"
        val tap = RemkComplicationHelper.buildLaunchPendingIntent(this, 303, targetPage = 1)

        return when (request.complicationType) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = drankL.coerceAtMost(targetL), min = 0f, max = targetL,
                contentDescription = PlainComplicationText.Builder("Wasser $waterTxt").build()
            ).setText(PlainComplicationText.Builder(waterTxt).build()).setTitle(PlainComplicationText.Builder("WASSER").build()).setTapAction(tap).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(waterTxt).build(),
                contentDescription = PlainComplicationText.Builder("Wasser $waterTxt").build()
            ).setTitle(PlainComplicationText.Builder("WASSER").build()).setTapAction(tap).build()
            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 1.75f, min = 0f, max = 3.0f,
                contentDescription = PlainComplicationText.Builder("Wasser 1,75 L").build()
            ).setText(PlainComplicationText.Builder("1,75 L").build()).setTitle(PlainComplicationText.Builder("WASSER").build()).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("1,75 L").build(),
                contentDescription = PlainComplicationText.Builder("Wasser 1,75 L").build()
            ).setTitle(PlainComplicationText.Builder("WASSER").build()).build()
            else -> null
        }
    }
}

class RemkActiveSetComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val lastStart = prefs.getLong("last_rest_start_ms", 0L)
        val targetSec = prefs.getInt("rest_target_sec", 90)
        val elapsed = ((System.currentTimeMillis() - lastStart) / 1000L).toInt()
        val remSec = (targetSec - elapsed).coerceAtLeast(0)

        val setIdx = prefs.getInt("cur_set_idx", 1)
        val totSets = prefs.getInt("cur_total_sets", 3)
        val kg = prefs.getFloat("cur_kg", 40.0f)
        val kgStr = if (kg <= 0f) "BW" else if (kg % 1f == 0f) "${kg.toInt()}kg" else "${kg.toString().replace(".", ",")}kg"

        val titleVal = if (remSec > 0) "⏱ PAUSE" else "S$setIdx/$totSets"
        val textVal = if (remSec > 0) {
            val m = remSec / 60
            val s = (remSec % 60).toString().padStart(2, '0')
            "$m:$s"
        } else {
            kgStr
        }
        val tap = RemkComplicationHelper.buildLaunchPendingIntent(this, 304, targetPage = 0)

        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(textVal).build(),
            contentDescription = PlainComplicationText.Builder("RE:MK $titleVal $textVal").build()
        ).setTitle(PlainComplicationText.Builder(titleVal).build()).setTapAction(tap).build()
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder("40kg").build(),
            contentDescription = PlainComplicationText.Builder("RE:MK S1/3 40kg").build()
        ).setTitle(PlainComplicationText.Builder("S1/3").build()).build()
    }
}
