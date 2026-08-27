package com.pokerhelper.app

import android.util.Log
import org.json.JSONObject

/**
 * 筹码追踪器（V2.9.541精简）
 *
 * 历史：V2.9.210曾用ML Kit OCR做全屏/定点筹码识别，但V2.9.519起
 * 筹码识别已迁移到 LocalActionRecognizer 纯像素模板匹配。
 * 本类仅保留状态查询接口，供 HttpServerService / FloatingService 调用。
 */
object ChipTracker {

    private const val TAG = "ChipTracker"

    fun getStatusJson(): String = JSONObject().apply {
        put("available", false)
        put("message", "筹码识别已由本地CV接管")
    }.toString()

    fun reset() {
        Log.d(TAG, "reset (no-op)")
    }
}
