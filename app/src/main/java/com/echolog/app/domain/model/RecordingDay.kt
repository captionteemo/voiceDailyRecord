package com.echolog.app.domain.model

/**
 * 领域模型：录音日（架构 3.1）。
 *
 * 录音状态机与结算进度的唯一真相源；
 * 内部时间一律 epoch millis，dayId 为本地日期 `yyyy-MM-dd`。
 */
data class RecordingDay(
    val dayId: String,
    val state: DayState = DayState.IDLE,
    val monitorStartedAt: Long = 0L,
    val monitorEndedAt: Long = 0L,
    val totalRecordMs: Long = 0L,
    val mergedAudioPath: String? = null,
    val summaryStatus: SummaryStatus = SummaryStatus.NONE,
    val settleProgress: Int = 0,
    val settleError: String? = null,
    val mode: RecordingMode = RecordingMode.AUTO_VAD,
)
