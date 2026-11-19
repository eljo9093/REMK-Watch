package eu.remk.coaching.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * RE:MK Native Wear OS 5 Complication Data Sources (v33.0 Signature Pro)
 * Liefert Echtzeit-Daten an das "RE:MK Iron Telemetry" System-Zifferblatt
 * sowie an jedes beliebige Standard-Zifferblatt der Pixel Watch 4:
 * 1. RemkSetsComplicationService      -> Linker Orange-Bogen (Sätze heute)
 * 2. RemkKcalComplicationService      -> Rechter Grün-Bogen (Persönliche Kalorien)
 * 3. RemkWaterComplicationService     -> Unterer Blau-Bogen (Wasserstand heute)
 * 4. RemkActiveSetComplicationService -> HUD-Kapsel (Aktiver Satz / Pause, 1-Tap App-Start)
 */
object RemkComplicationHelper {

    fun buildLaunchPendingIntent(context: Context, reqCode: Int): PendingIntent {
        val intent = Intent(context, WearMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun requestAllComplicationsRefresh(context: Context) {
        val classes = listOf(
            RemkSetsComplicationService::class.java,
            RemkKcalComplicationService::class.java,
            RemkWaterComplicationService::class.java,
            RemkActiveSetComplicationService::class.java
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
}

class RemkSetsComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val done = prefs.getInt("done_sets_today", 0).coerceAtLeast(0)
        val total = prefs.getInt("total_sets_today", 12).coerceAtLeast(1)
        val txt = "$done/$total"
        val tap = RemkComplicationHelper.buildLaunchPendingIntent(this, 301)

        return when (request.complicationType) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = done.toFloat().coerceAtMost(total.toFloat()),
                min = 0f,
                max = total.toFloat(),
                contentDescription = PlainComplicationText.Builder("RE:MK Sätze $txt").build()
            )
                .setText(PlainComplicationText.Builder(txt).build())
                .setTitle(PlainComplicationText.Builder("SÄTZE").build())
                .setTapAction(tap)
                .build()

            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(txt).build(),
                contentDescription = PlainComplicationText.Builder("RE:MK Sätze $txt").build()
            )
                .setTitle(PlainComplicationText.Builder("SÄTZE").build())
                .setTapAction(tap)
                .build()

            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 8f, min = 0f, max = 12f,
                contentDescription = PlainComplicationText.Builder("RE:MK Sätze 8/12").build()
            )
                .setText(PlainComplicationText.Builder("8/12").build())
                .setTitle(PlainComplicationText.Builder("SÄTZE").build())
                .build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("8/12").build(),
                contentDescription = PlainComplicationText.Builder("RE:MK Sätze 8/12").build()
            )
                .setTitle(PlainComplicationText.Builder("SÄTZE").build())
                .build()
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
        val tap = RemkComplicationHelper.buildLaunchPendingIntent(this, 302)

        return when (request.complicationType) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = eaten.toFloat(),
                min = 0f,
                max = target.toFloat(),
                contentDescription = PlainComplicationText.Builder("Offene Kalorien $openKcal").build()
            )
                .setText(PlainComplicationText.Builder(txt).build())
                .setTitle(PlainComplicationText.Builder("KCAL").build())
                .setTapAction(tap)
                .build()

            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(txt).build(),
                contentDescription = PlainComplicationText.Builder("Offene Kalorien $openKcal").build()
            )
                .setTitle(PlainComplicationText.Builder("KCAL").build())
                .setTapAction(tap)
                .build()

            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 1850f, min = 0f, max = 2450f,
                contentDescription = PlainComplicationText.Builder("Kalorien 1850").build()
            )
                .setText(PlainComplicationText.Builder("1850").build())
                .setTitle(PlainComplicationText.Builder("KCAL").build())
                .build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("1850").build(),
                contentDescription = PlainComplicationText.Builder("Kalorien 1850").build()
            )
                .setTitle(PlainComplicationText.Builder("KCAL").build())
                .build()
            else -> null
        }
    }
}

class RemkWaterComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val drankL = prefs.getFloat("drank_liters", 0.0f).coerceAtLeast(0f)
        val targetL = 3.0f
        val waterTxt = "${String.format("%.2f", drankL).replace(".", ",")}L"
        val tap = RemkComplicationHelper.buildLaunchPendingIntent(this, 303)

        return when (request.complicationType) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = drankL.coerceAtMost(targetL),
                min = 0f,
                max = targetL,
                contentDescription = PlainComplicationText.Builder("Wasser $waterTxt").build()
            )
                .setText(PlainComplicationText.Builder(waterTxt).build())
                .setTitle(PlainComplicationText.Builder("WASSER").build())
                .setTapAction(tap)
                .build()

            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(waterTxt).build(),
                contentDescription = PlainComplicationText.Builder("Wasser $waterTxt").build()
            )
                .setTitle(PlainComplicationText.Builder("WASSER").build())
                .setTapAction(tap)
                .build()

            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 1.75f, min = 0f, max = 3.0f,
                contentDescription = PlainComplicationText.Builder("Wasser 1,75L").build()
            )
                .setText(PlainComplicationText.Builder("1,75L").build())
                .setTitle(PlainComplicationText.Builder("WASSER").build())
                .build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("1,75L").build(),
                contentDescription = PlainComplicationText.Builder("Wasser 1,75L").build()
            )
                .setTitle(PlainComplicationText.Builder("WASSER").build())
                .build()
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

        val label = if (remSec > 0) {
            val m = remSec / 60
            val s = (remSec % 60).toString().padStart(2, '0')
            "⏱️ $m:$s"
        } else {
            "S$setIdx/$totSets • $kgStr"
        }
        val tap = RemkComplicationHelper.buildLaunchPendingIntent(this, 304)

        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(label).build(),
            contentDescription = PlainComplicationText.Builder("RE:MK Aktiver Satz $label").build()
        )
            .setTitle(PlainComplicationText.Builder("RE:MK").build())
            .setTapAction(tap)
            .build()
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder("S1/3 • 40kg").build(),
            contentDescription = PlainComplicationText.Builder("RE:MK Aktiver Satz").build()
        )
            .setTitle(PlainComplicationText.Builder("RE:MK").build())
            .build()
    }
}
