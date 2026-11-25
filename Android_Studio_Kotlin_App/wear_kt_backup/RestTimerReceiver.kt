package eu.remk.coaching.wear

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat

object RestTimerScheduler {

    const val CHANNEL_ID = "remk_watch_rest_timer_v3722"
    const val NOTIF_ID = 37221
    private const val REQ_ALARM = 37222

    fun scheduleRestEndAlarm(context: Context, durationSec: Int, exName: String, nextSetIdx: Int, totalSets: Int, kgStr: String, reps: Int) {
        try {
            cancelRestEndAlarm(context)
            val triggerAtMs = System.currentTimeMillis() + (durationSec.coerceAtLeast(3) * 1000L)
            val intent = Intent(context.applicationContext, RestTimerReceiver::class.java).apply {
                action = "eu.remk.coaching.wear.ACTION_REST_FINISHED"
                putExtra("ex_name", exName)
                putExtra("set_idx", nextSetIdx)
                putExtra("total_sets", totalSets)
                putExtra("kg_str", kgStr)
                putExtra("reps", reps)
            }
            val pi = PendingIntent.getBroadcast(
                context.applicationContext,
                REQ_ALARM,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            }
        } catch (e: Exception) {}
    }

    fun cancelRestEndAlarm(context: Context) {
        try {
            val intent = Intent(context.applicationContext, RestTimerReceiver::class.java).apply {
                action = "eu.remk.coaching.wear.ACTION_REST_FINISHED"
            }
            val pi = PendingIntent.getBroadcast(
                context.applicationContext,
                REQ_ALARM,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            am?.cancel(pi)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIF_ID)
        } catch (e: Exception) {}
    }
}

class RestTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val exName = intent?.getStringExtra("ex_name") ?: (prefs.getString("cur_ex_name", "Training") ?: "Training")
        val setIdx = intent?.getIntExtra("set_idx", prefs.getInt("cur_set_idx", 1)) ?: 1
        val totalSets = intent?.getIntExtra("total_sets", prefs.getInt("cur_total_sets", 3)) ?: 3
        val curKg = prefs.getFloat("cur_kg", 40f)
        val defaultKgStr = if (curKg <= 0f) "BW" else if (curKg % 1f == 0f) "${curKg.toInt()}kg" else "${curKg.toString().replace(".", ",")}kg"
        val kgStr = intent?.getStringExtra("kg_str") ?: defaultKgStr
        val reps = intent?.getIntExtra("reps", prefs.getInt("cur_reps", 10)) ?: 10

        try {
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vib != null && vib.hasVibrator()) {
                val pattern = longArrayOf(0, 240, 120, 240, 120, 460)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {}

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    RestTimerScheduler.CHANNEL_ID,
                    "RE:MK Pausen-Timer",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Vibration & Hinweis nach abgelaufener Satzpause"
                    enableVibration(true)
                }
                nm.createNotificationChannel(ch)
            }

            val openIntent = Intent(context, WearMainActivity::class.java).apply {
                putExtra("start_page", 0)
                putExtra("from_rest_alarm", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPi = PendingIntent.getActivity(
                context, 37223, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val quickLogIntent = Intent(context, TileLogSetActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val quickLogPi = PendingIntent.getActivity(
                context, 37224, quickLogIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(context, RestTimerScheduler.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tile_kraft)
                .setContentTitle("⏱️ Pause vorbei! $exName")
                .setContentText("Satz $setIdx/$totalSets bereit • $kgStr × $reps")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .addAction(0, "🏋️ Eingeben", openPi)
                .addAction(0, "✓ $kgStr×$reps OK", quickLogPi)
                .build()

            nm.notify(RestTimerScheduler.NOTIF_ID, notif)
        } catch (e: Exception) {}

        RemkTileHelper.requestAllTilesRefresh(context)
        RemkComplicationHelper.requestAllComplicationsRefresh(context)
    }
}
