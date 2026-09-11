package win.opt.view

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * V2.9.516: SelfLearner — 青云"越打越聪明"自我学习模块
 *
 * 只记自己的决策和结果，不记对手AD。
 * - P1: SQLite持久化决策+盈亏关联
 * - P2: Leak检测（样本≥30、平均BB<-1.5标记LEAK）
 * - P3: V2.9.615策略自适应闭环上线——leak维度转翻前频率收紧因子,
 *       经getLeakAdjustments()暴露给JS PreflopLeakGuard;总手数≥200且维度样本≥30才激活,
 *       只收紧(0.7~0.9)不放宽;缓存预计算,桥接读取零DB不阻塞决策链路
 *
 * 铁律：所有IO走后台线程，绝不阻塞pipeline主链路。
 */
object SelfLearner {
    private const val TAG = "SelfLearner"
    private const val DB_NAME = "self_learner.db"
    private const val DB_VERSION = 1

    // Leak检测阈值
    private const val MIN_SAMPLE_FOR_LEAK = 30
    private const val LEAK_BB_THRESHOLD = -1.5f

    // V2.9.615 P3: 闭环激活门槛与收紧分档
    private const val MIN_TOTAL_HANDS_FOR_ADJ = 200   // 总手数≥200才允许任何自适应
    private const val LEAK_ADJ_LIGHT = -1.5f          // avg_bb<=此值 → freq*0.9
    private const val LEAK_ADJ_HEAVY = -3.0f          // avg_bb<=此值 → freq*0.7
    private const val LEAK_FACTOR_LIGHT = 0.9f
    private const val LEAK_FACTOR_HEAVY = 0.7f

    // V2.9.615 P3: leak收紧因子缓存(io线程预计算,@JavascriptInterface只读内存)
    @Volatile private var leakAdjustCache: JSONObject = JSONObject()

    private var dbHelper: DbHelper? = null
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "self-learner-io").apply { isDaemon = true }
    }

    // 当前正在进行的手牌（内存缓存，一手结束时写库）
    private data class PendingHand(
        var handId: String,
        var holeCards: String,
        var position: String,
        var startTime: Long,
        var bb: Int = 200,
        val decisions: MutableList<JSONObject> = mutableListOf()
    )
    private var pending: PendingHand? = null

    fun init(context: Context) {
        if (dbHelper != null) return
        dbHelper = DbHelper(context.applicationContext)
        Log.i(TAG, "SelfLearner初始化完成")
        // V2.9.615 P3: 启动即预计算一次leak调整缓存(io线程,不阻塞)
        refreshLeakAdjustments()
    }

    // ============ P1: 决策记录 + 盈亏关联 ============

    /**
     * 记录一次决策（每条街道一条）。
     * 由JS AndroidBridge.logSelfDecision调用。
     */
    fun recordDecision(
        handId: String,
        holeCards: String,
        position: String,
        street: String,
        communityCards: String,
        pot: Int,
        toCall: Int,
        action: String,
        sizing: Int,
        eq: Int,
        hClass: String,
        confidence: String,
        reason: String,
        bb: Int
    ) {
        io.execute {
            try {
                // 更新内存中当前手牌
                val p = pending
                if (p == null || p.handId != handId) {
                    // 新的一手牌开始
                    pending?.let { flushHandLocked(it, null) }
                    pending = PendingHand(handId, holeCards, position, System.currentTimeMillis(), bb)
                }
                val cur = pending!!
                if (bb > 0) cur.bb = bb

                val dec = JSONObject().apply {
                    put("street", street)
                    put("community_cards", communityCards)
                    put("pot", pot)
                    put("to_call", toCall)
                    put("action", action)
                    put("sizing", sizing)
                    put("eq", eq)
                    put("h_class", hClass)
                    put("confidence", confidence)
                    put("reason", reason)
                }
                cur.decisions.add(dec)
                Log.d(TAG, "决策记录: hand=$handId street=$street action=$action eq=$eq%")
            } catch (e: Exception) {
                Log.e(TAG, "recordDecision失败: ${e.message}")
            }
        }
    }

    /**
     * 收到一手牌的最终结果（赢/输）。
     * 由JS筹码差检测逻辑触发：新一手preflop时对比上一手筹码变化。
     */
    fun handResult(handId: String, resultBb: Float, netChips: Long, resultType: String) {
        io.execute {
            try {
                val p = pending
                if (p != null && p.handId == handId) {
                    flushHandLocked(p, Triple(resultBb, netChips, resultType))
                    pending = null
                } else {
                    // V2.9.615 FIX(P0数据覆盖): 没有对应pending,分两种情况——
                    //  (a)该行已存在(摊牌帧L12491报当前手+新手牌帧L11834又报旧手,同手双上报):
                    //      只UPDATE结果字段,严禁CONFLICT_REPLACE写入空position/空decisions,
                    //      否则打到亮牌的手(大赢大亏手)位置与决策明细被清空,位置leak统计偏倚;
                    //  (b)行不存在(无决策记录的手):才INSERT占位行
                    upsertResultOnly(handId, resultBb, netChips, resultType)
                }
                Log.i(TAG, "手牌结果: hand=$handId result=${resultBb}BB type=$resultType")
                // V2.9.615 P3: 新结果落库→重算leak调整缓存(io队列排队,不阻塞当前上报)
                refreshLeakAdjustments()
            } catch (e: Exception) {
                Log.e(TAG, "handResult失败: ${e.message}")
            }
        }
    }

    /** 一手牌结束（弃牌/摊牌/离开），没有明确结果也写库（result_bb=0） */
    fun endHand(handId: String) {
        io.execute {
            try {
                val p = pending
                if (p != null && p.handId == handId) {
                    flushHandLocked(p, null)
                    pending = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "endHand失败: ${e.message}")
            }
        }
    }

    private fun flushHandLocked(p: PendingHand, result: Triple<Float, Long, String>?) {
        val decisionsArr = JSONArray()
        for (d in p.decisions) decisionsArr.put(d)
        val resultBb = result?.first ?: 0f
        val netChips = result?.second ?: 0L
        val resultType = result?.third ?: if (p.decisions.any { it.optString("action") == "fold" }) "fold" else "unknown"
        writeHandRow(p.handId, p.holeCards, p.position, resultBb, netChips, resultType, decisionsArr)
    }

    private fun writeHandRow(
        handId: String, holeCards: String, position: String,
        resultBb: Float, netChips: Long, resultType: String,
        decisions: JSONArray
    ) {
        val db = dbHelper?.writableDatabase ?: return
        try {
            val cv = ContentValues().apply {
                put("hand_id", handId)
                put("ts", System.currentTimeMillis())
                put("hole_cards", holeCards)
                put("position", position)
                put("result_bb", resultBb)
                put("net_chips", netChips)
                put("result_type", resultType)
                put("decisions_json", decisions.toString())
            }
            db.insertWithOnConflict("hands", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            Log.e(TAG, "写hands表失败: ${e.message}")
        }
    }

    /**
     * V2.9.615 P0: 结果字段幂等更新——双上报时只写result_bb/net_chips/result_type,
     * 已存在的行(position/hole_cards/decisions_json)一律不覆盖。
     * 行不存在才INSERT占位(空position,手数统计仍可用,位置维度WHERE position!=''自动排除)。
     */
    private fun upsertResultOnly(handId: String, resultBb: Float, netChips: Long, resultType: String) {
        val db = dbHelper?.writableDatabase ?: return
        try {
            // 先尝试只更新结果字段(已存在的行:position/hole_cards/decisions_json原样保留)
            db.execSQL(
                "UPDATE hands SET result_bb=?, net_chips=?, result_type=? WHERE hand_id=?",
                arrayOf<Any>(resultBb, netChips, resultType, handId)
            )
            // 行不存在才INSERT占位行(空position,位置维度WHERE position!=''自动排除)
            db.rawQuery("SELECT COUNT(*) FROM hands WHERE hand_id=?", arrayOf(handId)).use { c ->
                if (c.moveToFirst() && c.getInt(0) == 0) {
                    writeHandRow(handId, "", "", resultBb, netChips, resultType, JSONArray())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "upsertResultOnly失败: ${e.message}")
        }
    }

    // ============ P2: Leak检测 + 复盘数据 ============

    /**
     * 获取最近N手牌（供复盘面板使用）。
     * 同步返回，调用方应在后台线程。
     */
    fun getRecentHands(limit: Int = 50): JSONArray {
        val arr = JSONArray()
        val db = dbHelper?.readableDatabase ?: return arr
        try {
            db.rawQuery(
                "SELECT hand_id, ts, hole_cards, position, result_bb, net_chips, result_type, decisions_json " +
                "FROM hands ORDER BY ts DESC LIMIT ?", arrayOf(limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val o = JSONObject().apply {
                        put("hand_id", c.getString(0))
                        put("ts", c.getLong(1))
                        put("time", formatTime(c.getLong(1)))
                        put("hole_cards", c.getString(2) ?: "")
                        put("position", c.getString(3) ?: "")
                        put("result_bb", c.getFloat(4))
                        put("net_chips", c.getLong(5))
                        put("result_type", c.getString(6) ?: "")
                        put("decisions", JSONArray(c.getString(7) ?: "[]"))
                    }
                    arr.put(o)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getRecentHands失败: ${e.message}")
        }
        return arr
    }

    /**
     * Leak检测：按维度统计平均BB结果。
     * 样本≥30且平均BB<-1.5标记为LEAK。
     */
    fun detectLeaks(): JSONArray {
        val arr = JSONArray()
        val db = dbHelper?.readableDatabase ?: return arr
        try {
            // 1. 按位置统计
            addLeakStat(arr, db, "position", "位置")
            // 2. 按手牌分类统计（从decisions_json提取preflop的h_class）
            addHandClassLeak(arr, db)
            // 3. 按action类型统计
            addActionLeak(arr, db)
        } catch (e: Exception) {
            Log.e(TAG, "detectLeaks失败: ${e.message}")
        }
        return arr
    }

    private fun addLeakStat(arr: JSONArray, db: SQLiteDatabase, col: String, label: String) {
        db.rawQuery(
            "SELECT $col, COUNT(*), AVG(result_bb), SUM(CASE WHEN result_bb>0 THEN 1 ELSE 0 END) " +
            "FROM hands WHERE $col != '' AND result_type != 'unknown' " +
            "GROUP BY $col HAVING COUNT(*) >= ?",
            arrayOf(MIN_SAMPLE_FOR_LEAK.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0)
                val count = c.getInt(1)
                val avgBb = c.getFloat(2)
                val wins = c.getInt(3)
                arr.put(JSONObject().apply {
                    put("dimension", label)
                    put("key", key)
                    put("count", count)
                    put("avg_bb", Math.round(avgBb * 10f) / 10f)
                    put("win_rate", Math.round(wins.toFloat() / count * 1000f) / 10f)
                    put("is_leak", avgBb < LEAK_BB_THRESHOLD)
                })
            }
        }
    }

    private fun addHandClassLeak(arr: JSONArray, db: SQLiteDatabase) {
        // 从decisions_json提取preflop h_class
        db.rawQuery(
            "SELECT decisions_json, result_bb FROM hands WHERE result_type != 'unknown' LIMIT 500",
            null
        ).use { c ->
            val stats = mutableMapOf<String, Pair<Int, Float>>() // hClass -> (count, sumBb)
            while (c.moveToNext()) {
                try {
                    val decisions = JSONArray(c.getString(0) ?: "[]")
                    val resultBb = c.getFloat(1)
                    var hClass = ""
                    for (i in 0 until decisions.length()) {
                        val d = decisions.getJSONObject(i)
                        if (d.optString("street") == "preflop") {
                            hClass = d.optString("h_class", "")
                            break
                        }
                    }
                    if (hClass.isNotEmpty()) {
                        val (cnt, sum) = stats[hClass] ?: (0 to 0f)
                        stats[hClass] = (cnt + 1) to (sum + resultBb)
                    }
                } catch (_: Exception) {}
            }
            for ((hClass, pair) in stats) {
                val (count, sumBb) = pair
                if (count >= MIN_SAMPLE_FOR_LEAK) {
                    val avgBb = sumBb / count
                    arr.put(JSONObject().apply {
                        put("dimension", "手牌类型")
                        put("key", hClass)
                        put("count", count)
                        put("avg_bb", Math.round(avgBb * 10f) / 10f)
                        put("win_rate", 0f)
                        put("is_leak", avgBb < LEAK_BB_THRESHOLD)
                    })
                }
            }
        }
    }

    private fun addActionLeak(arr: JSONArray, db: SQLiteDatabase) {
        db.rawQuery(
            "SELECT decisions_json, result_bb FROM hands WHERE result_type != 'unknown' LIMIT 500",
            null
        ).use { c ->
            val stats = mutableMapOf<String, Pair<Int, Float>>()
            while (c.moveToNext()) {
                try {
                    val decisions = JSONArray(c.getString(0) ?: "[]")
                    val resultBb = c.getFloat(1)
                    // 取preflop action作为这一手的line
                    var action = ""
                    for (i in 0 until decisions.length()) {
                        val d = decisions.getJSONObject(i)
                        if (d.optString("street") == "preflop") {
                            action = d.optString("action", "")
                            break
                        }
                    }
                    if (action.isNotEmpty()) {
                        val (cnt, sum) = stats[action] ?: (0 to 0f)
                        stats[action] = (cnt + 1) to (sum + resultBb)
                    }
                } catch (_: Exception) {}
            }
            for ((action, pair) in stats) {
                val (count, sumBb) = pair
                if (count >= MIN_SAMPLE_FOR_LEAK) {
                    val avgBb = sumBb / count
                    arr.put(JSONObject().apply {
                        put("dimension", "翻前动作")
                        put("key", action)
                        put("count", count)
                        put("avg_bb", Math.round(avgBb * 10f) / 10f)
                        put("is_leak", avgBb < LEAK_BB_THRESHOLD)
                    })
                }
            }
        }
    }

    // ============ P3: 策略自适应闭环(V2.9.615) ============

    /**
     * 供JS桥直接调用:返回当前生效的leak收紧因子(JSON字符串,纯内存读,零DB)。
     * 结构: {"active":bool,"total_hands":int,"position":{"bb":{"factor":0.9,...}},
     *        "hand_class":{"SUITED":{"factor":0.7,...}}}
     * 任何异常返回"{}"——JS侧PreflopLeakGuard按无调整处理(失败静默回退基线)。
     */
    fun getLeakAdjustments(): String {
        return try { leakAdjustCache.toString() } catch (e: Exception) { "{}" }
    }

    /**
     * io线程预计算leak收紧因子并刷新缓存。
     * 规则:
     *  - 总手数<200 → active=false,无任何调整(样本不足宁可不动)
     *  - 维度样本≥30 且 avg_bb<=-3.0 → factor=0.7(重度收紧)
     *  - 维度样本≥30 且 -3.0<avg_bb<=-1.5 → factor=0.9(轻度收紧)
     *  - 其余维度不出现(只收紧亏损维度,盈利维度绝不反向放宽)
     * 维度:位置(直接喂翻前RFI/3B/4B频率)、手牌类型(翻前粗分类PAIR/BROADWAY/SUITED/OTHER)。
     * 翻前动作维度只用于复盘展示,不参与闭环(避免多轴叠加过度收紧)。
     */
    fun refreshLeakAdjustments() {
        io.execute {
            try {
                val out = JSONObject()
                val total = getTotalHands()
                out.put("total_hands", total)
                out.put("generated_at", System.currentTimeMillis())
                if (total < MIN_TOTAL_HANDS_FOR_ADJ) {
                    out.put("active", false)
                    leakAdjustCache = out
                    Log.d(TAG, "P3调整未激活: 总手数$total < $MIN_TOTAL_HANDS_FOR_ADJ")
                    return@execute
                }
                val posAdj = JSONObject()
                val hcAdj = JSONObject()
                val db = dbHelper?.readableDatabase
                if (db != null) {
                    // 位置维度
                    db.rawQuery(
                        "SELECT position, COUNT(*), AVG(result_bb) FROM hands " +
                        "WHERE position != '' AND result_type != 'unknown' " +
                        "GROUP BY position HAVING COUNT(*) >= ?",
                        arrayOf(MIN_SAMPLE_FOR_LEAK.toString())
                    ).use { c ->
                        while (c.moveToNext()) {
                            val key = c.getString(0)
                            val cnt = c.getInt(1)
                            val avg = c.getFloat(2)
                            val f = leakFactorFor(avg)
                            if (f < 1f) {
                                posAdj.put(key, JSONObject().apply {
                                    put("factor", f)
                                    put("count", cnt)
                                    put("avg_bb", Math.round(avg * 10f) / 10f)
                                })
                            }
                        }
                    }
                    // 手牌类型维度(翻前粗分类,从decisions_json取preflop h_class)
                    db.rawQuery(
                        "SELECT decisions_json, result_bb FROM hands WHERE result_type != 'unknown' LIMIT 500",
                        null
                    ).use { c ->
                        val stats = mutableMapOf<String, Pair<Int, Float>>()
                        while (c.moveToNext()) {
                            try {
                                val decisions = JSONArray(c.getString(0) ?: "[]")
                                val resultBb = c.getFloat(1)
                                var hc = ""
                                for (i in 0 until decisions.length()) {
                                    val d = decisions.getJSONObject(i)
                                    if (d.optString("street") == "preflop") {
                                        hc = d.optString("h_class", "")
                                        break
                                    }
                                }
                                // 只统计P3翻前粗分类4类,旧数据UNKNOWN/PRE自动排除
                                if (hc == "PAIR" || hc == "BROADWAY" || hc == "SUITED" || hc == "OTHER") {
                                    val (cnt0, sum0) = stats[hc] ?: (0 to 0f)
                                    stats[hc] = (cnt0 + 1) to (sum0 + resultBb)
                                }
                            } catch (_: Exception) {}
                        }
                        for ((hc, pair) in stats) {
                            val (cnt, sumBb) = pair
                            if (cnt >= MIN_SAMPLE_FOR_LEAK) {
                                val avg = sumBb / cnt
                                val f = leakFactorFor(avg)
                                if (f < 1f) {
                                    hcAdj.put(hc, JSONObject().apply {
                                        put("factor", f)
                                        put("count", cnt)
                                        put("avg_bb", Math.round(avg * 10f) / 10f)
                                    })
                                }
                            }
                        }
                    }
                }
                out.put("position", posAdj)
                out.put("hand_class", hcAdj)
                out.put("active", posAdj.length() > 0 || hcAdj.length() > 0)
                leakAdjustCache = out
                Log.i(TAG, "P3调整缓存已刷新: active=${out.getBoolean("active")} " +
                        "位置leak=${posAdj.length()}个 手牌leak=${hcAdj.length()}个 (总手数$total)")
            } catch (e: Exception) {
                Log.e(TAG, "refreshLeakAdjustments失败: ${e.message}")
            }
        }
    }

    /** avg_bb → 收紧因子;未达leak阈值返回1f(不调整) */
    private fun leakFactorFor(avgBb: Float): Float {
        return when {
            avgBb <= LEAK_ADJ_HEAVY -> LEAK_FACTOR_HEAVY
            avgBb <= LEAK_ADJ_LIGHT -> LEAK_FACTOR_LIGHT
            else -> 1f
        }
    }

    /** 总手数 */
    fun getTotalHands(): Int {
        val db = dbHelper?.readableDatabase ?: return 0
        return try {
            db.rawQuery("SELECT COUNT(*) FROM hands", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        } catch (e: Exception) { 0 }
    }

    /** 总盈亏BB */
    fun getTotalBb(): Float {
        val db = dbHelper?.readableDatabase ?: return 0f
        return try {
            db.rawQuery("SELECT SUM(result_bb) FROM hands WHERE result_type != 'unknown'", null).use { c ->
                if (c.moveToFirst()) c.getFloat(0) else 0f
            }
        } catch (e: Exception) { 0f }
    }

    /** 清空所有学习数据 */
    fun reset() {
        io.execute {
            try {
                dbHelper?.writableDatabase?.execSQL("DELETE FROM hands")
                pending = null
                Log.i(TAG, "学习数据已清空")
                // V2.9.615 P3: 数据清空→leak调整缓存同步归零
                refreshLeakAdjustments()
            } catch (e: Exception) {
                Log.e(TAG, "reset失败: ${e.message}")
            }
        }
    }

    private fun formatTime(ts: Long): String {
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }

    // ============ SQLite ============

    private class DbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE hands (
                    hand_id TEXT PRIMARY KEY,
                    ts INTEGER NOT NULL,
                    hole_cards TEXT,
                    position TEXT,
                    result_bb REAL DEFAULT 0,
                    net_chips INTEGER DEFAULT 0,
                    result_type TEXT,
                    decisions_json TEXT
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX idx_hands_ts ON hands(ts DESC)")
            db.execSQL("CREATE INDEX idx_hands_position ON hands(position)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
            // v1: 初始版本
        }
    }
}
