package com.echolog.app.domain.usecase

/**
 * 结算入队接缝（T03 引入）。
 *
 * 手动结束 → 当天进入 SETTLING → 由本接口把「结算流水线」入队
 * （架构 4.2：DaySettleWorker，合并→转写→总结）。
 *
 * T05 起由 `work/WorkScheduler` 实现（WorkManager 唯一工作名
 * `settle-{dayId}`，架构 7.5），`di/DataModule` 完成绑定。
 */
interface SettleScheduler {

    /** 入队某天的结算任务（唯一工作名 `settle-{dayId}`，架构 7.5）。 */
    fun scheduleDaySettle(dayId: String)
}
