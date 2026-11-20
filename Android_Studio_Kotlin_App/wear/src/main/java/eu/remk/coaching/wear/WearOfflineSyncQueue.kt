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
import java.util.UUID

/**
 * RE:MK Bidirektionale Store-&-Forward Offline-Queue & Live-Sync-Engine (v33.0 Signature Pro)
 * - 100 % konform zum RE:MK Haupt-App v35.8 Sync-Vertrag:
 *   WATCH_SET_COMPLETED (exId, setIdx, kg, reps, rir)
 *   WATCH_SET_UNDO (exId, setIdx)
 *   WATCH_WATER_ADDED (addedMl, drankLiters)
 *   WATCH_MEAL_TOGGLED (slotIdx, done)
 *   WATCH_SHOP_TOGGLED (itemTitle, done)
 *   WATCH_SWITCH_ENV (targetEnv: "gym" | "homegym" | "bodyweight")
 *   WATCH_CARDIO_SAVED (sport, durationSec, distKm, avgBpm)
 */
object WearOfflineSyncQueue {

    const val PREFS_NAME = "remk_wear_standalone_v260"
    private const val KEY_QUEUE = "offline_sync_queue_json"
    private const val KEY_UNSYNCED_CNT = "offline_unsynced_count"
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
                .apply()
        }
    }

    fun getOrCreateWatchDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var devId = prefs.getString("watch_device_id", "") ?: ""
        if (devId.isEmpty()) {
            devId = "WATCH-" + UUID.randomUUID().toString().substring(0, 8).uppercase()
            prefs.edit().putString("watch_device_id", devId).apply()
        }
        return devId
    }

    fun getLastSyncTimestamp(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong("last_sync_ts", 0L)
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

    fun markAllSynced(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(KEY_UNSYNCED_CNT, 0)
                .putLong("last_sync_ts", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {}
    }

    fun buildWatchSyncSnapshot(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val drankL = prefs.getFloat("drank_liters", 0f).toDouble()
        val snap = JSONObject()
        snap.put("source", "watch_v33_0")
        snap.put("ts", System.currentTimeMillis())
        snap.put("dayIdx", prefs.getInt("day_idx", 0))
        snap.put("exIdx", prefs.getInt("cur_ex_idx", 0))
        snap.put("setIdx", prefs.getInt("cur_set_idx", 1))
        snap.put("curKg", prefs.getFloat("cur_kg", 40f).toDouble())
        snap.put("curReps", prefs.getInt("cur_reps", 10))
        snap.put("doneSetsToday", prefs.getInt("done_sets_today", 0))
        snap.put("todayVolumeKg", prefs.getInt("today_volume_kg", 0))
        snap.put("drankLiters", drankL)
        snap.put("hydrationMl", Math.round(drankL * 1000.0))
        snap.put("openKcal", prefs.getInt("open_kcal", 2450))
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
                val bodyObj = JSONObject()
                bodyObj.put("action", "syncPush")
                bodyObj.put("user", "Admin")
                bodyObj.put("key", syncKey)
                bodyObj.put("deviceId", devId)
                bodyObj.put("syncData", buildWatchSyncSnapshot(appCtx))

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
                    markAllSynced(appCtx)
                    ok = true
                }
            } catch (e: Exception) {
                ok = false
            }
            onComplete?.invoke(ok)
        }.start()
    }

    fun pullCloudStateToWatchAsync(context: Context, onComplete: ((Boolean, JSONObject?) -> Unit)? = null) {
        val appCtx = context.applicationContext
        Thread {
            var ok = false
            var syncDataObj: JSONObject? = null
            try {
                val syncKey = getOrCreateSyncKey(appCtx)
                val devId = getOrCreateWatchDeviceId(appCtx)
                val baseUrl = getScriptUrl(appCtx)
                val fullUrl = "$baseUrl?action=syncPull&user=Admin&key=${java.net.URLEncoder.encode(syncKey, "UTF-8")}&deviceId=${java.net.URLEncoder.encode(devId, "UTF-8")}&t=${System.currentTimeMillis()}"
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
                if (json.optBoolean("valid", false)) {
                    syncDataObj = json.optJSONObject("syncData")
                    if (syncDataObj != null) {
                        pruneAckedEvents(appCtx, syncDataObj.optJSONArray("ackEventIds"))
                    }
                    ok = true
                }
            } catch (e: Exception) {
                ok = false
            }
            onComplete?.invoke(ok, syncDataObj)
        }.start()
    }
}
