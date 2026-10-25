package com.echolog.app.data.mapper

import com.echolog.app.data.local.entity.AudioSegmentEntity
import com.echolog.app.data.local.entity.DaySummaryEntity
import com.echolog.app.data.local.entity.MarkerEntity
import com.echolog.app.data.local.entity.RecordingDayEntity
import com.echolog.app.data.local.entity.TodoItemEntity
import com.echolog.app.data.local.entity.TranscriptSegmentEntity
import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.DaySummary
import com.echolog.app.domain.model.Marker
import com.echolog.app.domain.model.RecordingDay
import com.echolog.app.domain.model.TodoItem
import com.echolog.app.domain.model.TranscriptSegment

/**
 * 实体 ↔ 领域模型映射（data 层内部）。
 *
 * 集中为单一事实源，避免四个仓库实现各自重复映射导致漂移；
 * `internal` 限定在 data 层内使用，保证 domain 层不依赖 Room。
 */

// ── RecordingDay ──
internal fun RecordingDayEntity.toDomain(): RecordingDay = RecordingDay(
    dayId = dayId,
    state = state,
    monitorStartedAt = monitorStartedAt,
    monitorEndedAt = monitorEndedAt,
    totalRecordMs = totalRecordMs,
    mergedAudioPath = mergedAudioPath,
    summaryStatus = summaryStatus,
    settleProgress = settleProgress,
    settleError = settleError,
    mode = mode,
)

internal fun RecordingDay.toEntity(): RecordingDayEntity = RecordingDayEntity(
    dayId = dayId,
    state = state,
    monitorStartedAt = monitorStartedAt,
    monitorEndedAt = monitorEndedAt,
    totalRecordMs = totalRecordMs,
    mergedAudioPath = mergedAudioPath,
    summaryStatus = summaryStatus,
    settleProgress = settleProgress,
    settleError = settleError,
    mode = mode,
)

// ── AudioSegment ──
internal fun AudioSegmentEntity.toDomain(): AudioSegment = AudioSegment(
    segmentId = segmentId,
    dayId = dayId,
    filePath = filePath,
    startMs = startMs,
    endMs = endMs,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    transcriptStatus = transcriptStatus,
    transcriptError = transcriptError,
)

internal fun AudioSegment.toEntity(): AudioSegmentEntity = AudioSegmentEntity(
    segmentId = segmentId,
    dayId = dayId,
    filePath = filePath,
    startMs = startMs,
    endMs = endMs,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    transcriptStatus = transcriptStatus,
    transcriptError = transcriptError,
)

// ── Marker ──
internal fun MarkerEntity.toDomain(): Marker = Marker(
    markerId = markerId,
    dayId = dayId,
    segmentId = segmentId,
    atMs = atMs,
    note = note,
    createdAt = createdAt,
)

internal fun Marker.toEntity(): MarkerEntity = MarkerEntity(
    markerId = markerId,
    dayId = dayId,
    segmentId = segmentId,
    atMs = atMs,
    note = note,
    createdAt = createdAt,
)

// ── TranscriptSegment ──
internal fun TranscriptSegmentEntity.toDomain(): TranscriptSegment = TranscriptSegment(
    tsId = tsId,
    segmentId = segmentId,
    startMs = startMs,
    endMs = endMs,
    text = text,
)

/**
 * 新建转写分段的实体（[TranscriptSegment.tsId] 由自增主键生成，忽略入参值）。
 *
 * @param segmentId 所属片段 ID（外键）。
 */
internal fun TranscriptSegment.toEntity(segmentId: Long): TranscriptSegmentEntity =
    TranscriptSegmentEntity(
        segmentId = segmentId,
        startMs = startMs,
        endMs = endMs,
        text = text,
    )

// ── DaySummary ──
internal fun DaySummaryEntity.toDomain(): DaySummary = DaySummary(
    dayId = dayId,
    status = status,
    summaryText = summaryText,
    timelineJson = timelineJson,
    modelName = modelName,
    error = error,
)

internal fun DaySummary.toEntity(): DaySummaryEntity = DaySummaryEntity(
    dayId = dayId,
    status = status,
    summaryText = summaryText,
    timelineJson = timelineJson,
    modelName = modelName,
    error = error,
)

// ── TodoItem ──
internal fun TodoItemEntity.toDomain(): TodoItem = TodoItem(
    todoId = todoId,
    dayId = dayId,
    type = type,
    content = content,
    sourceMs = sourceMs,
    done = done,
)

internal fun TodoItem.toEntity(): TodoItemEntity = TodoItemEntity(
    todoId = todoId,
    dayId = dayId,
    type = type,
    content = content,
    sourceMs = sourceMs,
    done = done,
)
