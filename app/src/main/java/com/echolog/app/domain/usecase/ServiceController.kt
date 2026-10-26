package com.echolog.app.domain.usecase

import com.echolog.app.core.common.EchoResult

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

    /**
     * 进入待命态并挂出快捷录音入口（用户需求：锁屏/状态栏一键开始录音）。
     *
     * 用于「入口尚未建立」的场景（首启、开机自恢复、用户在设置里打开开关）。
     * 服务已在监听/暂停/待命时无操作——不得打断进行中的录音。
     *
     * **返回 [EchoResult] 而非 Unit 的原因**：Android 8+ 后台启动限制或厂商 ROM
     * 收紧会让 `startForegroundService` 抛异常，此时**入口其实没建起来**，
     * 而用户侧看到的是「设置里开关是开的、锁屏却没有入口」——纯静默失败。
     *
     * 调用方**必须处理返回值**，不得丢弃：最低要求是给出可见反馈（失败时实现方
     * 已代发 alert 渠道通知，调用方至少补一条日志），否则即违反项目红线
     * 「静默失败禁止」。
     */
    fun enterStandby(): EchoResult<Unit>
}
