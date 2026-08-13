package com.pokerhelper.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * V3.2: HUD自积累 + 云端同步 — 有网就有记忆 (纯线程版, 零额外依赖)
 *
 * 新增功能:
 * - hud_sync_upload(): 把本地数据推到 Gitee 仓库的 hud_data.json
 * - hud_sync_download(): 从 Gitee 拉取最新数据合并
 * - 合并策略: 云端优先, 本地补充 (Max取大, 不丢失数据)
 * - 每次启动自动同步一次, 每50手自动上传一次
 *
 * 用法:
 *   HudLearner.init(context, "giteeToken")
 *   HudLearner.recordHand(mapOf(...), "micro_nl2")
 *   HudLearner.getOpponentProfile("micro_nl2")
 */
object HudLearner {
    private const val TAG = "HudLearner"
    private const val PREFS_NAME = "hud_learner_data"
    private const val KEY_PREFIX = "hl_"
    private const val MIN_HANDS_FOR_TRUST = 200
    private const val MIN_HANDS_FOR_OVERRIDE = 500
    private const val MAX_HANDS_STORED = 5000
    private const val SYNC_INTERVAL_HANDS = 50

    // Gitee 云同步配置
    private const val GITEE_REPO = "juh123000/qingyun-lobster"
    private const val GITEE_FILE_PATH = "hud_data.json"
    private const val GITEE_API_BASE = "https://gitee.com/api/v5/repos/$GITEE_REPO/contents/$GITEE_FILE_PATH"

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    private var giteeToken: String? = null
    private var giteeSha: String? = null
    private var currentLevel: String = "micro_nl2"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    data class HandRecord(
        val timestamp: Long,
        val level: String,
        val vpip: Float, val pfr: Float, val threeBet: Float,
        val foldTo3Bet: Float, val cbetFlop: Float, val cbetTurn: Float,
        val foldToCBetFlop: Float, val foldToCBetTurn: Float,
        val callRiver: Float, val checkRaiseFlop: Float,
        val handsObserved: Int
    )

    data class OpponentProfile(
        val vpip: Float, val pfr: Float, val threeBet: Float, val foldTo3Bet: Float,
        val cbetFlop: Float, val cbetTurn: Float, val foldToCBetFlop: Float,
        val foldToCBetTurn: Float, val callRiver: Float, val checkRaiseFlop: Float,
        val confidence: Float, val totalHandsObserved: Int, val type: String
    )

    /**
     * 初始化 — 必须调用
     * @param context 应用上下文
     * @param token Gitee个人访问令牌 (需 projects 写权限, 可选)
     */
    @Synchronized
    fun init(context: Context, token: String? = null) {
        if (prefs != null) return
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        giteeToken = token

        // 启动时后台自动从云端拉取
        if (giteeToken != null) {
            Thread({ downloadAndMerge() }, "hud-sync-download").start()
        }
        Log.i(TAG, "HudLearner初始化: ${if (token != null) "云端模式" else "本地模式"}")
    }

    fun setToken(token: String) {
        giteeToken = token
    }

    fun recordHand(opponentStats: Map<String, Float>, level: String) {
        val p = prefs ?: return
        currentLevel = level

        try {
            val record = HandRecord(
                timestamp = System.currentTimeMillis(), level = level,
                vpip = opponentStats["vpip"] ?: -1f,
                pfr = opponentStats["pfr"] ?: -1f,
                threeBet = opponentStats["threeBet"] ?: -1f,
                foldTo3Bet = opponentStats["foldTo3Bet"] ?: -1f,
                cbetFlop = opponentStats["cbetFlop"] ?: -1f,
                cbetTurn = opponentStats["cbetTurn"] ?: -1f,
                foldToCBetFlop = opponentStats["foldToCBetFlop"] ?: -1f,
                foldToCBetTurn = opponentStats["foldToCBetTurn"] ?: -1f,
                callRiver = opponentStats["callRiver"] ?: -1f,
                checkRaiseFlop = opponentStats["checkRaiseFlop"] ?: -1f,
                handsObserved = opponentStats["handsObserved"]?.toInt() ?: 1
            )
            appendRecord(level, record)

            // 每50手自动上传
            val count = getHandCount(level)
            if (count % SYNC_INTERVAL_HANDS == 0 && giteeToken != null) {
                Thread({ uploadToCloud() }, "hud-sync-upload").start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "记录失败", e)
        }
    }

    fun getOpponentProfile(level: String): OpponentProfile {
        val p = prefs ?: return getBaselineProfile(level)
        val handCount = getHandCount(level)
        if (handCount < MIN_HANDS_FOR_TRUST) {
            Log.d(TAG, "手数不足($handCount<$MIN_HANDS_FOR_TRUST), 使用基线")
            return getBaselineProfile(level)
        }
        val profile = computeProfile(loadRecords(level), handCount)
        Log.d(TAG, "自积累: ${handCount}手 VPIP=${String.format("%.1f", profile.vpip * 100)}% type=${profile.type}")
        return profile
    }

    fun getHandCount(level: String): Int =
        prefs?.getInt(KEY_PREFIX + level + "_count", 0) ?: 0

    // ============ 云端同步 (线程版) ============

    /** 手动触发一次完整同步 */
    fun sync() {
        Thread({
            downloadAndMerge()
            uploadToCloud()
        }, "hud-sync-full").start()
    }

    private fun uploadToCloud() {
        val token = giteeToken ?: return
        try {
            val payload = buildCloudPayload()
            val json = payload.toString()
            val encoded = Base64.encodeToString(
                json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )

            val body = JSONObject()
            body.put("content", encoded)
            body.put("message", "HUD sync: ${getTotalHands()}手")
            giteeSha?.let { body.put("sha", it) }

            val request = Request.Builder()
                .url(GITEE_API_BASE)
                .put(body.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Authorization", "token $token")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    giteeSha = JSONObject(respBody).optJSONObject("content")?.optString("sha")
                    Log.i(TAG, "☁️ 上传成功: ${getTotalHands()}手")
                } else {
                    Log.w(TAG, "☁️ 上传失败: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "☁️ 上传异常: ${e.message}")
        }
    }

    private fun downloadAndMerge() {
        val token = giteeToken ?: return
        try {
            val request = Request.Builder()
                .url(GITEE_API_BASE)
                .header("Authorization", "token $token")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    Log.i(TAG, "☁️ 云端无数据，稍后首次上传")
                    return
                }
                val respBody = response.body?.string() ?: return
                if (!response.isSuccessful) return

                val root = JSONObject(respBody)
                giteeSha = root.optJSONObject("content")?.optString("sha")
                val contentEncoded = root.optJSONObject("content")?.optString("content") ?: return

                val decoded = Base64.decode(contentEncoded, Base64.NO_WRAP)
                val cloudJson = String(decoded, Charsets.UTF_8)
                val cloudData = JSONObject(cloudJson)
                val cloudLevels = cloudData.optJSONObject("levels") ?: return

                var merged = 0
                val keys = cloudLevels.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == "lastSync" || key == "deviceId") continue
                    mergeLevelFromCloud(key, cloudLevels.getJSONObject(key))
                    merged++
                }
                Log.i(TAG, "☁️ 下载合并: ${merged}个级别, 共${getTotalHands()}手")
            }
        } catch (e: Exception) {
            Log.e(TAG, "☁️ 下载异常: ${e.message}")
        }
    }

    private fun mergeLevelFromCloud(level: String, cloudObj: JSONObject) {
        val cloudRecords = cloudObj.optJSONArray("records") ?: return
        val localRecords = loadRecords(level)
        val localTs = localRecords.map { it.timestamp }.toSet()

        for (i in 0 until cloudRecords.length()) {
            val obj = cloudRecords.getJSONObject(i)
            val ts = obj.optLong("ts", 0)
            if (ts !in localTs) {
                appendRecord(level, parseRecord(obj, level))
            }
        }
    }

    private fun getTotalHands(): Int {
        var total = 0
        for (level in listOf("micro_nl2", "low_nl10", "mid_nl50")) {
            total += getHandCount(level)
        }
        return total
    }

    private fun buildCloudPayload(): JSONObject {
        val root = JSONObject()
        root.put("deviceId", getDeviceId())
        root.put("lastSync", System.currentTimeMillis() / 1000)

        val levels = JSONObject()
        for (level in listOf("micro_nl2", "low_nl10", "mid_nl50")) {
            val records = loadRecords(level)
            if (records.isEmpty()) continue

            val levelObj = JSONObject()
            val arr = JSONArray()
            for (r in records) {
                val obj = JSONObject()
                obj.put("ts", r.timestamp)
                obj.put("vpip", r.vpip.toDouble())
                obj.put("pfr", r.pfr.toDouble())
                obj.put("3b", r.threeBet.toDouble())
                obj.put("f3b", r.foldTo3Bet.toDouble())
                obj.put("cb", r.cbetFlop.toDouble())
                obj.put("cbt", r.cbetTurn.toDouble())
                obj.put("fcb", r.foldToCBetFlop.toDouble())
                obj.put("fcbt", r.foldToCBetTurn.toDouble())
                obj.put("crv", r.callRiver.toDouble())
                obj.put("crf", r.checkRaiseFlop.toDouble())
                arr.put(obj)
            }
            levelObj.put("records", arr)
            levelObj.put("count", records.size)
            levels.put(level, levelObj)
        }
        root.put("levels", levels)
        return root
    }

    private fun getDeviceId(): String {
        val ctx = appContext ?: return "unknown"
        return try {
            android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    // ============ 本地存储 ============

    private fun appendRecord(level: String, record: HandRecord) {
        val p = prefs ?: return
        val key = KEY_PREFIX + level + "_data"
        val existingJson = p.getString(key, "[]") ?: "[]"
        val arr = JSONArray(existingJson)

        val obj = JSONObject()
        obj.put("ts", record.timestamp)
        obj.put("vpip", record.vpip.toDouble())
        obj.put("pfr", record.pfr.toDouble())
        obj.put("3b", record.threeBet.toDouble())
        obj.put("f3b", record.foldTo3Bet.toDouble())
        obj.put("cb", record.cbetFlop.toDouble())
        obj.put("cbt", record.cbetTurn.toDouble())
        obj.put("fcb", record.foldToCBetFlop.toDouble())
        obj.put("fcbt", record.foldToCBetTurn.toDouble())
        obj.put("crv", record.callRiver.toDouble())
        obj.put("crf", record.checkRaiseFlop.toDouble())
        arr.put(obj)

        val trimmed: JSONArray = if (arr.length() > MAX_HANDS_STORED) {
            JSONArray().also { newArr ->
                for (i in arr.length() - MAX_HANDS_STORED until arr.length()) {
                    newArr.put(arr.get(i))
                }
            }
        } else arr

        p.edit().putString(key, trimmed.toString()).apply()
        val count = p.getInt(KEY_PREFIX + level + "_count", 0)
        p.edit().putInt(KEY_PREFIX + level + "_count", count + 1).apply()
    }

    private fun loadRecords(level: String): List<HandRecord> {
        val p = prefs ?: return emptyList()
        val json = p.getString(KEY_PREFIX + level + "_data", "[]") ?: "[]"
        val arr = try { JSONArray(json) } catch (e: Exception) { return emptyList() }
        val records = mutableListOf<HandRecord>()
        for (i in 0 until arr.length()) {
            try {
                records.add(parseRecord(arr.getJSONObject(i), level))
            } catch (_: Exception) {}
        }
        return records
    }

    private fun parseRecord(obj: JSONObject, level: String): HandRecord = HandRecord(
        timestamp = obj.optLong("ts", 0),
        level = level,
        vpip = obj.optDouble("vpip", -1.0).toFloat(),
        pfr = obj.optDouble("pfr", -1.0).toFloat(),
        threeBet = obj.optDouble("3b", -1.0).toFloat(),
        foldTo3Bet = obj.optDouble("f3b", -1.0).toFloat(),
        cbetFlop = obj.optDouble("cb", -1.0).toFloat(),
        cbetTurn = obj.optDouble("cbt", -1.0).toFloat(),
        foldToCBetFlop = obj.optDouble("fcb", -1.0).toFloat(),
        foldToCBetTurn = obj.optDouble("fcbt", -1.0).toFloat(),
        callRiver = obj.optDouble("crv", -1.0).toFloat(),
        checkRaiseFlop = obj.optDouble("crf", -1.0).toFloat(),
        handsObserved = 1
    )

    private fun computeProfile(records: List<HandRecord>, totalHands: Int): OpponentProfile {
        if (records.isEmpty()) return getBaselineProfile(currentLevel)
        val n = records.size
        var totalWeight = 0f
        var wVpip = 0f; var wPfr = 0f; var w3b = 0f; var wF3b = 0f
        var wCb = 0f; var wCbt = 0f; var wFcb = 0f; var wFcbt = 0f
        var wCrv = 0f; var wCrf = 0f

        for (i in records.indices) {
            val r = records[i]
            val weight = (i + 1).toFloat() / n
            if (r.vpip >= 0) { wVpip += r.vpip * weight; totalWeight += weight }
            if (r.pfr >= 0) wPfr += r.pfr * weight
            if (r.threeBet >= 0) w3b += r.threeBet * weight
            if (r.foldTo3Bet >= 0) wF3b += r.foldTo3Bet * weight
            if (r.cbetFlop >= 0) wCb += r.cbetFlop * weight
            if (r.cbetTurn >= 0) wCbt += r.cbetTurn * weight
            if (r.foldToCBetFlop >= 0) wFcb += r.foldToCBetFlop * weight
            if (r.foldToCBetTurn >= 0) wFcbt += r.foldToCBetTurn * weight
            if (r.callRiver >= 0) wCrv += r.callRiver * weight
            if (r.checkRaiseFlop >= 0) wCrf += r.checkRaiseFlop * weight
        }

        val norm = maxOf(totalWeight, 1f)
        val confidence = when {
            totalHands >= MIN_HANDS_FOR_OVERRIDE -> 0.90f
            totalHands >= MIN_HANDS_FOR_TRUST ->
                0.50f + (totalHands - MIN_HANDS_FOR_TRUST).toFloat() /
                    (MIN_HANDS_FOR_OVERRIDE - MIN_HANDS_FOR_TRUST) * 0.40f
            else -> 0.30f
        }

        return OpponentProfile(
            vpip = wVpip / norm, pfr = wPfr / norm, threeBet = w3b / norm,
            foldTo3Bet = wF3b / norm, cbetFlop = wCb / norm, cbetTurn = wCbt / norm,
            foldToCBetFlop = wFcb / norm, foldToCBetTurn = wFcbt / norm,
            callRiver = wCrv / norm, checkRaiseFlop = wCrf / norm,
            confidence = confidence, totalHandsObserved = totalHands, type = "self"
        )
    }

    private fun getBaselineProfile(level: String): OpponentProfile = when (level) {
        "micro_nl2" -> OpponentProfile(0.36f, 0.18f, 0.06f, 0.35f, 0.40f, 0.32f, 0.55f, 0.50f, 0.65f, 0.06f, 0.10f, 0, "baseline")
        "low_nl10" -> OpponentProfile(0.30f, 0.22f, 0.08f, 0.45f, 0.48f, 0.38f, 0.50f, 0.48f, 0.55f, 0.08f, 0.10f, 0, "baseline")
        else -> OpponentProfile(0.26f, 0.22f, 0.09f, 0.52f, 0.52f, 0.40f, 0.48f, 0.46f, 0.48f, 0.10f, 0.10f, 0, "baseline")
    }
}
