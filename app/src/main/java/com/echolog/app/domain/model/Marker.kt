package com.echolog.app.domain.model

/**
 * 领域模型：打点标记（架构 3.1，FR-005）。
 *
 * `segmentId` 为打点时刻正在录制片段的快照（可空：监听态打点时无片段）；
 * `note` 为用户文字备注（可空字符串）。
 */
data class Marker(
    val markerId: Long = 0L,
    val dayId: String,
    val segmentId: Long? = null,
    val atMs: Long,
    val note: String = "",
    val createdAt: Long = 0L,
)
