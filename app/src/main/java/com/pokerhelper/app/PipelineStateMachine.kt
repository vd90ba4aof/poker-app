package com.pokerhelper.app

import android.util.Log

/**
 * Pipeline 状态机 — 替代散乱的标志位（isVisionInProgress / handStartTime / _strategyReceived）
 *
 * 设计原则：
 * 1. 所有 pipeline 流转状态由状态机统一管理，消除竞态条件
 * 2. 每次状态转换都有明确日志，方便调试
 * 3. 非法转换被拒绝并记录警告，保持当前状态
 * 4. 线程安全：所有状态操作通过 synchronized 保护
 *
 * 状态流转图：
 * ┌──────┐     SCREENSHOT_OK     ┌──────────────────┐    LOCAL_RECOG_OK     ┌───────────────────┐
 * │ IDLE │ ───────────────────→ │   CAPTURING       │ ─────────────────→  │ RECOGNIZING_LOCAL │
 * └──┬───┘                      └────────┬──────────┘                     └─────────┬─────────┘
 *    ↑                                   │ SCREENSHOT_FAIL                          │ LOCAL_RECOG_FAIL
 *    │                                   ↓                                          ↓
 *    │                         ┌─────────────────┐                     ┌──────────────────┐
 *    │    (恢复完成)           │  ERROR_RECOVERY  │ ←────────────────  │  RECOGNIZING_API  │
 *    │ ←────────────────────── └─────────────────┘                     └─────────┬────────┘
 *    │                                    ↑                                      │ API_RECOG_OK
 *    │                                    │ API_RECOG_FAIL                       │
 *    │                                    └──────────────────────────────────────┘
 *    │                                                                           ↓
 *    │    (冷却结束)    ┌──────────┐   STRATEGY_READY    ┌───────────┐   ┌────────────────────┐
 *    └──────────────── │ COOLDOWN │ ←───────────────── │ EXECUTING │ ← │ STRATEGY_COMPUTING │
 *                      └──────────┘                     └─────┬─────┘   └────────────────────┘
 *                           ↑                                 │
 *                           │                          BLE_EXEC_OK
 *                           │                                 │
 *                           │                    ┌────────────┘
 *                           │                    │
 *                           │              ┌─────┴──────────┐
 *                           └──────────────│  ERROR_RECOVERY │ ←── BLE_EXEC_FAIL
 *                                          └────────────────┘
 *
 * 特殊转换（任意状态）：
 *   - SHOT_CLOCK_TIMEOUT → EXECUTING（强制fold）
 *   - NO_TABLE_DETECTED → IDLE（不在牌桌）
 *   - RESET → IDLE（手动重置）
 */
class PipelineStateMachine {

    companion object {
        private const val TAG = "PipelineFSM"
    }

    /**
     * Pipeline 状态枚举
     */
    enum class PipelineState {
        /** 空闲，等待下一次截屏触发 */
        IDLE,
        /** 正在截屏 */
        CAPTURING,
        /** 本地CV识别中 */
        RECOGNIZING_LOCAL,
        /** API视觉识别中（本地CV失败后的降级通道） */
        RECOGNIZING_API,
        /** 策略引擎计算中（WebView JS执行） */
        STRATEGY_COMPUTING,
        /** BLE执行中（ESP32点击按钮） */
        EXECUTING,
        /** 冷却期（执行完毕后等待下一手牌） */
        COOLDOWN,
        /** 错误恢复中 */
        ERROR_RECOVERY
    }

    /**
     * Pipeline 事件枚举
     */
    enum class PipelineEvent {
        /** 开始截屏（发起截屏请求） */
        START_CAPTURE,
        /** 截屏成功（截屏数据就绪） */
        SCREENSHOT_OK,
        /** 截屏失败 */
        SCREENSHOT_FAIL,
        /** 本地CV识别成功 */
        LOCAL_RECOG_OK,
        /** 本地CV识别失败（降级到API） */
        LOCAL_RECOG_FAIL,
        /** API识别成功 */
        API_RECOG_OK,
        /** API识别失败 */
        API_RECOG_FAIL,
        /** 策略引擎回调完成（JS autoDecision） */
        STRATEGY_READY,
        /** 策略引擎超时（8s无回调） */
        STRATEGY_TIMEOUT,
        /** BLE执行成功 */
        BLE_EXEC_OK,
        /** BLE执行失败 */
        BLE_EXEC_FAIL,
        /** Shot Clock硬超时（26s强制fold） */
        SHOT_CLOCK_TIMEOUT,
        /** 未检测到牌桌 */
        NO_TABLE_DETECTED,
        /** 冷却结束（可进入下一手牌） */
        COOLDOWN_END,
        /** 错误恢复完成 */
        RECOVERY_DONE,
        /** 手动重置（stopAutoCapture等） */
        RESET,
        /** 进入API识别通道（跳过本地CV） */
        ENTER_API
    }

    /**
     * 状态转换表：(当前状态, 事件) → 新状态
     * null 表示非法转换
     */
    private val transitionTable: Map<Pair<PipelineState, PipelineEvent>, PipelineState> = mapOf(
        // === IDLE 出发 ===
        (PipelineState.IDLE to PipelineEvent.START_CAPTURE) to PipelineState.CAPTURING,
        (PipelineState.IDLE to PipelineEvent.ENTER_API) to PipelineState.RECOGNIZING_API,

        // === CAPTURING 出发 ===
        (PipelineState.CAPTURING to PipelineEvent.SCREENSHOT_OK) to PipelineState.RECOGNIZING_LOCAL,
        (PipelineState.CAPTURING to PipelineEvent.SCREENSHOT_FAIL) to PipelineState.ERROR_RECOVERY,

        // === RECOGNIZING_LOCAL 出发 ===
        (PipelineState.RECOGNIZING_LOCAL to PipelineEvent.LOCAL_RECOG_OK) to PipelineState.STRATEGY_COMPUTING,
        (PipelineState.RECOGNIZING_LOCAL to PipelineEvent.LOCAL_RECOG_FAIL) to PipelineState.RECOGNIZING_API,
        (PipelineState.RECOGNIZING_LOCAL to PipelineEvent.NO_TABLE_DETECTED) to PipelineState.IDLE,

        // === RECOGNIZING_API 出发 ===
        (PipelineState.RECOGNIZING_API to PipelineEvent.API_RECOG_OK) to PipelineState.STRATEGY_COMPUTING,
        (PipelineState.RECOGNIZING_API to PipelineEvent.API_RECOG_FAIL) to PipelineState.ERROR_RECOVERY,
        (PipelineState.RECOGNIZING_API to PipelineEvent.NO_TABLE_DETECTED) to PipelineState.IDLE,

        // === STRATEGY_COMPUTING 出发 ===
        (PipelineState.STRATEGY_COMPUTING to PipelineEvent.STRATEGY_READY) to PipelineState.EXECUTING,
        (PipelineState.STRATEGY_COMPUTING to PipelineEvent.STRATEGY_TIMEOUT) to PipelineState.ERROR_RECOVERY,
        (PipelineState.STRATEGY_COMPUTING to PipelineEvent.NO_TABLE_DETECTED) to PipelineState.IDLE,

        // === EXECUTING 出发 ===
        (PipelineState.EXECUTING to PipelineEvent.BLE_EXEC_OK) to PipelineState.COOLDOWN,
        (PipelineState.EXECUTING to PipelineEvent.BLE_EXEC_FAIL) to PipelineState.ERROR_RECOVERY,

        // === COOLDOWN 出发 ===
        (PipelineState.COOLDOWN to PipelineEvent.COOLDOWN_END) to PipelineState.IDLE,
        (PipelineState.COOLDOWN to PipelineEvent.START_CAPTURE) to PipelineState.CAPTURING,  // 下一手牌开始

        // === ERROR_RECOVERY 出发 ===
        (PipelineState.ERROR_RECOVERY to PipelineEvent.RECOVERY_DONE) to PipelineState.IDLE,
        (PipelineState.ERROR_RECOVERY to PipelineEvent.RESET) to PipelineState.IDLE
    )

    /**
     * 全局事件转换表：任意状态 + 事件 → 新状态（优先级高于普通转换表）
     */
    private val globalTransitions: Map<PipelineEvent, PipelineState> = mapOf(
        PipelineEvent.SHOT_CLOCK_TIMEOUT to PipelineState.EXECUTING,  // 任意状态强制fold
        PipelineEvent.NO_TABLE_DETECTED to PipelineState.IDLE,         // 任意状态回到空闲
        PipelineEvent.RESET to PipelineState.IDLE                      // 任意状态重置
    )

    @Volatile
    private var currentState: PipelineState = PipelineState.IDLE

    /** 状态转换监听器（可选） */
    var onStateChanged: ((oldState: PipelineState, event: PipelineEvent, newState: PipelineState) -> Unit)? = null

    /**
     * 获取当前状态
     */
    fun getCurrentState(): PipelineState = currentState

    /**
     * 判断 pipeline 是否正在执行（非空闲且非错误恢复）
     * 替代原来的 isVisionInProgress
     */
    fun isPipelineActive(): Boolean {
        return currentState != PipelineState.IDLE
    }

    /**
     * 判断是否处于识别阶段（本地 or API）
     */
    fun isRecognizing(): Boolean {
        return currentState == PipelineState.RECOGNIZING_LOCAL ||
               currentState == PipelineState.RECOGNIZING_API
    }

    /**
     * 判断是否可以开始新的截屏
     * 替代原来 !isVisionInProgress 的检查
     */
    fun canCapture(): Boolean {
        return currentState == PipelineState.IDLE ||
               currentState == PipelineState.COOLDOWN ||
               currentState == PipelineState.ERROR_RECOVERY
    }

    /**
     * 执行状态转换
     * @return 转换后的新状态，如果转换非法则返回当前状态
     */
    @Synchronized
    fun transition(event: PipelineEvent): PipelineState {
        val oldState = currentState

        // 优先检查全局事件（SHOT_CLOCK_TIMEOUT / NO_TABLE_DETECTED / RESET）
        val globalTarget = globalTransitions[event]
        if (globalTarget != null) {
            if (oldState == globalTarget) {
                // 已经在目标状态，无需转换
                Log.d(TAG, "状态保持: $oldState + $event → $globalTarget (已是目标状态)")
                return globalTarget
            }
            currentState = globalTarget
            Log.d(TAG, "★ 全局转换: $oldState + $event → $globalTarget")
            onStateChanged?.invoke(oldState, event, globalTarget)
            return globalTarget
        }

        // 普通转换表查找
        val newState = transitionTable[oldState to event]
        if (newState != null) {
            currentState = newState
            Log.d(TAG, "状态转换: $oldState + $event → $newState")
            onStateChanged?.invoke(oldState, event, newState)
            return newState
        }

        // 非法转换
        Log.w(TAG, "⚠️ 非法转换: $oldState + $event → 保持 $oldState")
        return oldState
    }

    /**
     * 强制重置到 IDLE（用于 stopAutoCapture / onDestroy 等紧急场景）
     */
    @Synchronized
    fun reset() {
        val oldState = currentState
        if (oldState != PipelineState.IDLE) {
            currentState = PipelineState.IDLE
            Log.d(TAG, "★ 强制重置: $oldState → IDLE")
            onStateChanged?.invoke(oldState, PipelineEvent.RESET, PipelineState.IDLE)
        }
    }

    /**
     * 获取当前状态的中文描述（用于UI显示）
     */
    fun getStateDescription(): String {
        return when (currentState) {
            PipelineState.IDLE -> "空闲"
            PipelineState.CAPTURING -> "截屏中"
            PipelineState.RECOGNIZING_LOCAL -> "本地识别中"
            PipelineState.RECOGNIZING_API -> "API识别中"
            PipelineState.STRATEGY_COMPUTING -> "策略计算中"
            PipelineState.EXECUTING -> "执行中"
            PipelineState.COOLDOWN -> "冷却中"
            PipelineState.ERROR_RECOVERY -> "错误恢复中"
        }
    }
}
