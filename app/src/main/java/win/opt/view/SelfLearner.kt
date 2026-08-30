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
 * - P3: 策略自适应（暂缓，等200手数据）
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
                    // 没有对应决策记录，只写结果行（手数统计用）
                    writeHandRow(handId, "", "", resultBb, netChips, resultType, JSONArray())
                }
                Log.i(TAG, "手牌结果: hand=$handId result=${resultBb}BB type=$resultType")
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
