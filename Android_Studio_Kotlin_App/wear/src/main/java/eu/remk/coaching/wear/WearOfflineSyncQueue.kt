package eu.remk.coaching.wear

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * RE:MK Bidirektionale Offline-Queue & Live-Sync-Engine (v34.0 — v36.3 Master-Schema)
 * - 100 % abgestimmt auf RE:MK Coaching App Master-Dokumentation (v36.3) Abschnitt 5 & 6:
 *   Endpunkte: verify / loadSync (inkl. since-Check) / saveSync (Spalte I: Cloud_Sync_JSON) / resetDevice
 * - Rückwärtskompatibel zu watchPlanCompactJson & allen WATCH_*-Events
 */
object WearOfflineSyncQueue {

    const val PREFS_NAME = "remk_wear_standalone_v260"
    private const val KEY_QUEUE = "offline_sync_queue_json"
    private const val KEY_UNSYNCED_CNT = "offline_unsynced_count"
    private const val KEY_LAST_CLOUD_UPDATED_AT = "last_cloud_updated_at_v363"
    const val DEFAULT_GOOGLE_SCRIPT_URL = "https://script.google.com/macros/s/AKfycbwdAgwJtubUPoI08ifSX3lPRgVhv5PFzq6YyZywxAB780JOZ2OFGMmojVvEj1lHz3ek/exec"
    private var eventSeq = 100

    fun getScriptUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val custom = (prefs.getString("custom_script_url", "") ?: "").trim()
        return if (custom.startsWith("https://script.google.com/")) custom else DEFAULT_GOOGLE_SCRIPT_URL
    }

    fun getOrCreateSyncKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var key = (prefs.getString("sync_license_key", "") ?: "").trim()
        if (key.isEmpty() || key.startsWith("FREE-")) {
            key = "26011990"
            prefs.edit().putString("sync_license_key", key).apply()
        }
        return key
    }

    fun setSyncKey(context: Context, newKey: String) {
        val clean = newKey.trim()
        if (clean.isNotEmpty()) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("sync_license_key", clean)
                .putLong(KEY_LAST_CLOUD_UPDATED_AT, 0L)
                .apply()
        }
    }

    fun getOrCreateWatchDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var devId = prefs.getString("watch_device_id", "") ?: ""
        if (devId.isEmpty()) {
            devId = "pixel-watch-4-" + UUID.randomUUID().toString().substring(0, 8).uppercase()
            prefs.edit().putString("watch_device_id", devId).apply()
        }
        return devId
    }

    fun getLastSyncTimestamp(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong("last_sync_ts", 0L)
    }

    fun getLastCloudUpdatedAt(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_LAST_CLOUD_UPDATED_AT, 0L)
    }

    fun setLastCloudUpdatedAt(context: Context, updatedAt: Long) {
        if (updatedAt > 0L) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_CLOUD_UPDATED_AT, updatedAt)
                .apply()
        }
    }

    fun enqueueEvent(
        context: Context,
        action: String,
        details: String,
        payloadFields: Map<String, Any>? = null,
        onSynced: ((Boolean) -> Unit)? = null
    ) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
            val arr = JSONArray(raw)
            eventSeq = (eventSeq + 1) % 9999
            val nowTs = System.currentTimeMillis()
            val obj = JSONObject()
            obj.put("id", "w_${nowTs}_$eventSeq")
            obj.put("action", action)
            obj.put("type", action)
            obj.put("details", details)
            obj.put("ts", nowTs)
            if (payloadFields != null) {
                for ((k, v) in payloadFields) {
                    obj.put(k, v)
                }
            }
            arr.put(obj)

            val trimmed = JSONArray()
            val startIdx = (arr.length() - 80).coerceAtLeast(0)
            for (i in startIdx until arr.length()) {
                trimmed.put(arr.get(i))
            }
            val unsynced = prefs.getInt(KEY_UNSYNCED_CNT, 0) + 1
            prefs.edit()
                .putString(KEY_QUEUE, trimmed.toString())
                .putInt(KEY_UNSYNCED_CNT, unsynced)
                .apply()

            val intent = Intent("eu.remk.coaching.ACTION_WEAR_SYNC_EVENT").apply {
                putExtra("action", action)
                putExtra("details", details)
                putExtra("timestamp", nowTs)
            }
            context.sendBroadcast(intent)

            pushWatchStateToCloudAsync(context, onSynced)
        } catch (e: Exception) {
            onSynced?.invoke(false)
        }
    }

    fun pruneAckedEvents(context: Context, ackIdsArray: JSONArray?) {
        if (ackIdsArray == null || ackIdsArray.length() == 0) return
        try {
            val ackSet = mutableSetOf<String>()
            for (i in 0 until ackIdsArray.length()) {
                val id = ackIdsArray.optString(i, "")
                if (id.isNotEmpty()) ackSet.add(id)
            }
            if (ackSet.isEmpty()) return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
            val arr = JSONArray(raw)
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("id", "")
                if (!ackSet.contains(id)) kept.put(item)
            }
            prefs.edit().putString(KEY_QUEUE, kept.toString()).apply()
        } catch (e: Exception) {}
    }

    fun removeLastSetEventIfUndo(context: Context, exId: String = "", setIdx: Int = 1) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
            val arr = JSONArray(raw)
            var skipIdx = -1
            for (i in (arr.length() - 1) downTo 0) {
                val item = arr.optJSONObject(i)
                if (item != null && item.optString("action") == "WATCH_SET_COMPLETED") {
                    skipIdx = i
                    break
                }
            }
            if (skipIdx >= 0) {
                val nextArr = JSONArray()
                for (i in 0 until arr.length()) {
                    if (i != skipIdx) nextArr.put(arr.get(i))
                }
                prefs.edit().putString(KEY_QUEUE, nextArr.toString()).apply()
            }
            enqueueEvent(
                context,
                "WATCH_SET_UNDO",
                "exId=$exId&setIdx=$setIdx",
                mapOf("exId" to exId, "setIdx" to setIdx)
            )
        } catch (e: Exception) {}
    }

    fun getPendingCount(context: Context): Int {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_UNSYNCED_CNT, 0).coerceAtLeast(0)
        } catch (e: Exception) {
            0
        }
    }

    fun markAllSynced(context: Context, cloudUpdatedAt: Long = 0L) {
        try {
            val ed = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(KEY_UNSYNCED_CNT, 0)
                .putLong("last_sync_ts", System.currentTimeMillis())
            if (cloudUpdatedAt > 0L) ed.putLong(KEY_LAST_CLOUD_UPDATED_AT, cloudUpdatedAt)
            ed.apply()
        } catch (e: Exception) {}
    }

    /**
     * Erzeugt das kombinierte v36.3 Cloud_Sync_JSON + Watch-Bridge-Objekt für Spalte I
     */
    fun buildWatchSyncSnapshot(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val nowMs = System.currentTimeMillis()
        val drankL = ((prefs.getFloat("drank_liters", 0f) * 100f).toInt() / 100.0)
        val targetKcal = prefs.getInt("personal_target_kcal", 2450).let { if (it in 1100..4600) it else 2450 }
        val openKcal = prefs.getInt("open_kcal", targetKcal).coerceIn(0, targetKcal)
        val consumedKcal = (targetKcal - openKcal).coerceAtLeast(0)
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date(nowMs))

        val snap = JSONObject()
        // v36.3 Standard-Schema-Felder (Abschnitt 5.3)
        snap.put("schemaVersion", "36.3")
        snap.put("updatedAt", nowMs)
        snap.put("lastDevice", "pixel-watch-4")

        val prof = JSONObject()
        prof.put("nickname", prefs.getString("profile_nickname", "Jochen") ?: "Jochen")
        prof.put("uiMode", "pro")
        snap.put("profile", prof)

        // Aktives Workout inkl. synchronisiertem Pausen-Timer (restTimerEndAt)
        val awJson = prefs.getString("v363_active_workout_json", "") ?: ""
        if (awJson.startsWith("{")) {
            try {
                snap.put("activeWorkout", JSONObject(awJson))
            } catch (e: Exception) {}
        }

        val nutObj = JSONObject()
        nutObj.put("date", todayStr)
        nutObj.put("targetKcal", targetKcal)
        nutObj.put("consumedKcal", consumedKcal)
        nutObj.put("waterLiters", drankL)
        val mealsJson = prefs.getString("v363_meals_json", "") ?: ""
        if (mealsJson.startsWith("[")) {
            try { nutObj.put("meals", JSONArray(mealsJson)) } catch (e: Exception) {}
        }
        snap.put("nutrition", nutObj)

        val shopJson = prefs.getString("v363_shopping_json", "") ?: ""
        if (shopJson.startsWith("[")) {
            try { snap.put("shoppingList", JSONArray(shopJson)) } catch (e: Exception) {}
        }

        // Rückwärtskompatible Watch-Bridge-Felder (v35.x / v36.x)
        snap.put("source", "watch_v34_0")
        snap.put("ts", nowMs)
        snap.put("dayIdx", prefs.getInt("day_idx", 0))
        snap.put("exIdx", prefs.getInt("cur_ex_idx", 0))
        snap.put("setIdx", prefs.getInt("cur_set_idx", 1))
        snap.put("curKg", prefs.getFloat("cur_kg", 40f).toDouble())
        snap.put("curReps", prefs.getInt("cur_reps", 10))
        snap.put("doneSetsToday", prefs.getInt("done_sets_today", 0))
        snap.put("todayVolumeKg", prefs.getInt("today_volume_kg", 0))
        snap.put("drankLiters", drankL)
        snap.put("hydrationMl", Math.round(drankL * 1000.0))
        snap.put("openKcal", openKcal)
        snap.put("completedSetsMap", prefs.getString("completed_sets_map_json", "{}") ?: "{}")
        snap.put("eventsQueue", JSONArray(prefs.getString(KEY_QUEUE, "[]") ?: "[]"))
        return snap
    }

    fun pushWatchStateToCloudAsync(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val appCtx = context.applicationContext
        Thread {
            var ok = false
            try {
                val syncKey = getOrCreateSyncKey(appCtx)
                val devId = getOrCreateWatchDeviceId(appCtx)
                val snapshot = buildWatchSyncSnapshot(appCtx)

                val bodyObj = JSONObject()
                bodyObj.put("action", "saveSync")
                bodyObj.put("user", appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("profile_nickname", "Admin") ?: "Admin")
                bodyObj.put("key", syncKey)
                bodyObj.put("name", appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("profile_nickname", "Admin") ?: "Admin")
                bodyObj.put("deviceId", devId)
                bodyObj.put("payload", snapshot)
                bodyObj.put("syncData", snapshot)

                val url = URL(getScriptUrl(appCtx))
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 7000
                    readTimeout = 7000
                    doOutput = true
                    instanceFollowRedirects = true
                    setRequestProperty("Content-Type", "text/plain;charset=utf-8")
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(bodyObj.toString())
                    writer.flush()
                }
                val code = conn.responseCode
                if (code in 200..399) {
                    var serverTs = 0L
                    try {
                        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                        val respTxt = reader.readText()
                        reader.close()
                        val respJson = JSONObject(respTxt)
                        serverTs = respJson.optLong("updatedAt", 0L)
                    } catch (e: Exception) {}
                    markAllSynced(appCtx, serverTs)
                    ok = true
                }
            } catch (e: Exception) {
                ok = false
            }
            onComplete?.invoke(ok)
        }.start()
    }

    /**
     * Lädt Spalte I (Cloud_Sync_JSON) via loadSync mit ?since=<updatedAt>-Optimierung.
     * Callback: (ok, unchanged, cloudObj)
     */
    fun pullCloudStateToWatchAsync(
        context: Context,
        forceFullPull: Boolean = false,
        onComplete: ((Boolean, Boolean, JSONObject?) -> Unit)? = null
    ) {
        val appCtx = context.applicationContext
        Thread {
            var ok = false
            var unchanged = false
            var cloudObj: JSONObject? = null
            try {
                val syncKey = getOrCreateSyncKey(appCtx)
                val devId = getOrCreateWatchDeviceId(appCtx)
                val sinceTs = if (forceFullPull) 0L else getLastCloudUpdatedAt(appCtx)
                val baseUrl = getScriptUrl(appCtx)
                val nick = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("profile_nickname", "Admin") ?: "Admin"
                val fullUrl = "$baseUrl?action=loadSync&key=${java.net.URLEncoder.encode(syncKey, "UTF-8")}&name=${java.net.URLEncoder.encode(nick, "UTF-8")}&deviceId=${java.net.URLEncoder.encode(devId, "UTF-8")}&since=$sinceTs&t=${System.currentTimeMillis()}"
                val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 7000
                    instanceFollowRedirects = true
                }
                val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                val respText = reader.readText()
                reader.close()
                val json = JSONObject(respText)
                val statusStr = json.optString("status", "")
                if (statusStr == "unchanged") {
                    ok = true
                    unchanged = true
                } else if (json.optBoolean("valid", false) || statusStr == "ok") {
                    cloudObj = json.optJSONObject("payload")
                        ?: json.optJSONObject("cloudSync")
                        ?: json.optJSONObject("syncData")
                    val serverUpdatedAt = json.optLong("updatedAt", cloudObj?.optLong("updatedAt", 0L) ?: 0L)
                    if (serverUpdatedAt > 0L) setLastCloudUpdatedAt(appCtx, serverUpdatedAt)
                    if (cloudObj != null) {
                        pruneAckedEvents(appCtx, cloudObj.optJSONArray("ackEventIds"))
                    }
                    ok = true
                }
            } catch (e: Exception) {
                ok = false
            }
            onComplete?.invoke(ok, unchanged, cloudObj)
        }.start()
    }

    fun resetDevicesAsync(context: Context, onComplete: ((Boolean, String) -> Unit)? = null) {
        val appCtx = context.applicationContext
        Thread {
            var ok = false
            var msg = "Fehler beim Geräte-Reset"
            try {
                val syncKey = getOrCreateSyncKey(appCtx)
                val bodyObj = JSONObject()
                bodyObj.put("action", "resetDevice")
                bodyObj.put("key", syncKey)

                val conn = (URL(getScriptUrl(appCtx)).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 7000
                    readTimeout = 7000
                    doOutput = true
                    instanceFollowRedirects = true
                    setRequestProperty("Content-Type", "text/plain;charset=utf-8")
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { w ->
                    w.write(bodyObj.toString())
                    w.flush()
                }
                val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                val resp = JSONObject(reader.readText())
                reader.close()
                ok = resp.optBoolean("valid", false) || resp.optString("status") == "reset_ok"
                msg = resp.optString("message", if (ok) "Geräte zurückgesetzt" else "Reset fehlgeschlagen")
            } catch (e: Exception) {
                ok = false
            }
            onComplete?.invoke(ok, msg)
        }.start()
    }
}
