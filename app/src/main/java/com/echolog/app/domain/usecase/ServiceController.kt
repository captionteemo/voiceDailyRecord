package com.echolog.app.domain.usecase

/**
 * 服务控制接缝（T03 引入，Clean 分层）。
 *
 * domain 层不依赖 Android SDK，因此用例通过本接口间接触发
 * [com.echolog.app.service.RecordingService]；实现位于 service 层
 * （`RecordingServiceController`），可在单测中替换为 fake。
 */
interface ServiceController {

    /** 以「开启监听」意图启动前台服务（用户主动触发，合法拉起）。 */
    fun startMonitoring(dayId: String)

    /** 以「恢复监听」意图启动前台服务（健康检查/开机恢复/暂停后继续路径）。 */
    fun resumeMonitoring(dayId: String)

    /** 以「暂停」意图投递前台服务（用户需求 2：通知栏暂停；服务保持前台）。 */
    fun pauseMonitoring(dayId: String)

    /** 以「结束结算」意图停止服务音频循环（服务自行收尾后退出）。 */
    fun stopSettle(dayId: String)

    /**
     * 手动录音开停（用户需求 4 第四种：通知栏/App 录音按键）。
     *
     * @param recordingOn 目标态（true=开始录 / false=停止录）；null 表示按当前态取反。
     */
    fun toggleManualRecording(dayId: String, recordingOn: Boolean?)
}
