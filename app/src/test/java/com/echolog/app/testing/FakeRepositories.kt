package com.echolog.app.testing

import com.echolog.app.domain.model.AudioSegment
import com.echolog.app.domain.model.DayState
import com.echolog.app.domain.model.DaySummary
import com.echolog.app.domain.model.Marker
import com.echolog.app.domain.model.PrivacyMode
import com.echolog.app.domain.model.RecordingDay
import com.echolog.app.domain.model.RecordingMode
import com.echolog.app.domain.model.RecordingSettings
import com.echolog.app.domain.model.SensitivityLevel
import com.echolog.app.domain.model.SummaryStatus
import com.echolog.app.domain.model.TodoItem
import com.echolog.app.domain.model.TranscriptSegment
import com.echolog.app.domain.model.TranscriptStatus
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.repository.SettingsRepository
import com.echolog.app.domain.repository.SummaryRepository
import com.echolog.app.domain.repository.TranscriptionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 测试替身：内存版 [RecordingRepository]。
 *
 * 行为对齐 Room 实现的关键语义：Flow 查询随写操作重新发射、
 * finishSegment 派生 duration、observeDays 倒序。
 */
class FakeRecordingRepository : RecordingRepository {

    private val dayFlows = mutableMapOf<String, MutableStateFlow<RecordingDay?>>()
    private val segmentFlows = mutableMapOf<String, MutableStateFlow<List<AudioSegment>>>()
    private val markerFlows = mutableMapOf<String, MutableStateFlow<List<Marker>>>()
    private val dayListFlow = MutableStateFlow<List<RecordingDay>>(emptyList())

    private var nextSegmentId = 1L
    private var nextMarkerId = 1L

    /** 状态变更流水（测试断言处理顺序用）。 */
    val stateChangeLog = mutableListOf<String>()

    // ── 录音日 ──

    override fun observeDays(): Flow<List<RecordingDay>> = dayListFlow

    override fun observeDay(dayId: String): Flow<RecordingDay?> =
        dayFlows.getOrPut(dayId) { MutableStateFlow(null) }

    override suspend fun getDay(dayId: String): RecordingDay? = dayFlows[dayId]?.value

    override suspend fun createOrResumeDay(dayId: String, mode: RecordingMode): RecordingDay {
        val existing = dayFlows[dayId]?.value
        val now = System.currentTimeMillis()
        val day = when {
            existing == null -> RecordingDay(
                dayId = dayId,
                state = DayState.MONITORING,
                monitorStartedAt = now,
                mode = mode,
            )
            existing.state == DayState.MONITORING -> existing
            existing.state == DayState.IDLE -> existing.copy(
                state = DayState.MONITORING,
                monitorStartedAt = if (existing.monitorStartedAt > 0L) existing.monitorStartedAt else now,
                mode = mode,
            )
            else -> existing.copy(state = DayState.MONITORING, monitorStartedAt = now, mode = mode)
        }
        putDay(day)
        return day
    }

    override suspend fun updateDayState(dayId: String, state: DayState) {
        stateChangeLog += "$dayId->$state"
        val current = dayFlows[dayId]?.value ?: return
        val updated = if (current.state == DayState.MONITORING && state != DayState.MONITORING) {
            current.copy(state = state, monitorEndedAt = System.currentTimeMillis())
        } else {
            current.copy(state = state)
        }
        putDay(updated)
    }

    override suspend fun updateSettleProgress(dayId: String, progressPercent: Int, error: String?) {
        val current = dayFlows[dayId]?.value ?: return
        putDay(current.copy(settleProgress = progressPercent.coerceIn(0, 100), settleError = error))
    }

    override suspend fun updateSummaryStatus(dayId: String, status: SummaryStatus) {
        val current = dayFlows[dayId]?.value ?: return
        putDay(current.copy(summaryStatus = status))
    }

    override suspend fun updateMergeResult(dayId: String, totalRecordMs: Long, mergedAudioPath: String?) {
        val current = dayFlows[dayId]?.value ?: return
        putDay(current.copy(totalRecordMs = totalRecordMs.coerceAtLeast(0L), mergedAudioPath = mergedAudioPath))
    }

    // ── 片段 ──

    override fun observeSegmentsForDay(dayId: String): Flow<List<AudioSegment>> =
        segmentFlows.getOrPut(dayId) { MutableStateFlow(emptyList()) }

    override suspend fun getSegmentsForDay(dayId: String): List<AudioSegment> =
        segmentFlows[dayId]?.value ?: emptyList()

    override suspend fun insertSegment(dayId: String, startMs: Long, filePath: String): Long {
        val id = nextSegmentId++
        val segment = AudioSegment(
            segmentId = id,
            dayId = dayId,
            filePath = filePath,
            startMs = startMs,
        )
        val flow = segmentFlows.getOrPut(dayId) { MutableStateFlow(emptyList()) }
        flow.value = flow.value + segment
        return id
    }

    override suspend fun finishSegment(segmentId: Long, endMs: Long, sizeBytes: Long) {
        updateSegment(segmentId) { segment ->
            // 与仓库实现一致：防御时钟回拨 + DB 派生 duration
            val safeEnd = if (endMs >= segment.startMs) endMs else segment.startMs
            segment.copy(
                endMs = safeEnd,
                durationMs = safeEnd - segment.startMs,
                sizeBytes = sizeBytes.coerceAtLeast(0L),
            )
        }
    }

    // ── 标记 ──

    override fun observeMarkersForDay(dayId: String): Flow<List<Marker>> =
        markerFlows.getOrPut(dayId) { MutableStateFlow(emptyList()) }

    override suspend fun getMarkersForDay(dayId: String): List<Marker> =
        markerFlows[dayId]?.value ?: emptyList()

    override suspend fun insertMarker(dayId: String, atMs: Long, note: String): Long {
        val id = nextMarkerId++
        val marker = Marker(markerId = id, dayId = dayId, atMs = atMs, note = note, createdAt = atMs)
        val flow = markerFlows.getOrPut(dayId) { MutableStateFlow(emptyList()) }
        flow.value = flow.value + marker
        return id
    }

    override suspend fun deleteMarker(markerId: Long) {
        markerFlows.values.forEach { flow ->
            flow.value = flow.value.filterNot { it.markerId == markerId }
        }
    }

    override suspend fun deleteDay(dayId: String, deleteAudio: Boolean, deleteText: Boolean) {
        if (!deleteAudio && !deleteText) return
        if (deleteAudio) segmentFlows.remove(dayId)
        if (deleteText) {
            markerFlows.remove(dayId)
            dayFlows[dayId]?.value = null
            dayListFlow.value = dayListFlow.value.filterNot { it.dayId == dayId }
        }
    }

    // ── 清理缓存（用户需求 2） ──

    override suspend fun deleteAllOriginalAudio(): Int {
        // 内存替身无真实文件，按「片段数」等价计一次删除量
        val count = segmentFlows.values.sumOf { it.value.size }
        segmentFlows.values.forEach { flow -> flow.value = emptyList() }
        return count
    }

    override suspend fun clearDayConvertedArtifacts(dayId: String): Int {
        val flow = segmentFlows[dayId] ?: return 0
        val count = flow.value.size
        // 清除转写产物：片段转写态重置为 PENDING（可重新转写）
        flow.value = flow.value.map { it.copy(transcriptStatus = TranscriptStatus.PENDING) }
        return count
    }

    // ── 测试辅助 ──

    fun seedDay(day: RecordingDay) = putDay(day)

    fun addSegment(segment: AudioSegment): AudioSegment {
        val withId = if (segment.segmentId != 0L) {
            segment
        } else {
            segment.copy(segmentId = nextSegmentId++)
        }
        val flow = segmentFlows.getOrPut(withId.dayId) { MutableStateFlow(emptyList()) }
        flow.value = flow.value + withId
        return withId
    }

    fun updateSegment(segmentId: Long, transform: (AudioSegment) -> AudioSegment) {
        segmentFlows.values.forEach { flow ->
            flow.value = flow.value.map { if (it.segmentId == segmentId) transform(it) else it }
        }
    }

    fun segmentsOf(dayId: String): List<AudioSegment> = segmentFlows[dayId]?.value ?: emptyList()

    fun allSegments(): List<AudioSegment> = segmentFlows.values.flatMap { it.value }

    fun markersOf(dayId: String): List<Marker> = markerFlows[dayId]?.value ?: emptyList()

    private fun putDay(day: RecordingDay) {
        dayFlows.getOrPut(day.dayId) { MutableStateFlow(null) }.value = day
        dayListFlow.value = (dayListFlow.value.filterNot { it.dayId == day.dayId } + day)
            .sortedByDescending { it.dayId }
    }
}

/**
 * 测试替身：内存版 [TranscriptionRepository]。
 *
 * 片段状态与查询委托给 [FakeRecordingRepository]（对齐真实实现中
 * `AudioSegmentDao` 与 `TranscriptSegmentDao` 同库的事实）；
 * [FakeRecordingRepository.updateSegment] 使 PENDING→DONE/FAILED 状态推进可见，
 * 保证 `TranscribeDayUseCase` 的 while 循环能正常收敛。
 */
class FakeTranscriptionRepository(
    private val recordingRepository: FakeRecordingRepository,
) : TranscriptionRepository {

    private val flows = mutableMapOf<String, MutableStateFlow<List<TranscriptSegment>>>()

    /** 自增主键模拟（对齐 Room 的 `ts_id` 自增，插入时分配）。 */
    private var nextTsId = 1L

    /** getPendingSegments 调用计数（循环护栏用）。 */
    var queryCount = 0
        private set

    /** 超过此次数后 getPendingSegments 抛异常，防止测试被死循环挂死。 */
    var guardAfterQueries: Int = Int.MAX_VALUE

    override fun observeTranscriptsForDay(dayId: String): Flow<List<TranscriptSegment>> =
        flows.getOrPut(dayId) { MutableStateFlow(emptyList()) }

    override suspend fun getTranscriptsForDay(dayId: String): List<TranscriptSegment> =
        flows[dayId]?.value ?: emptyList()

    override suspend fun upsertTranscripts(segmentId: Long, items: List<TranscriptSegment>) {
        val dayId = recordingRepository.allSegments()
            .firstOrNull { it.segmentId == segmentId }?.dayId ?: return
        val flow = flows.getOrPut(dayId) { MutableStateFlow(emptyList()) }
        val kept = flow.value.filterNot { it.segmentId == segmentId }
        // 对齐 Room 行为：`ts_id` 由数据库自增分配，入参 tsId 一律忽略
        val stored = items.map { it.copy(tsId = nextTsId++) }
        flow.value = (kept + stored).sortedBy { it.startMs }
    }

    override suspend fun updateTranscriptText(tsId: Long, text: String): Int {
        var affected = 0
        flows.values.forEach { flow ->
            val current = flow.value
            if (current.any { it.tsId == tsId }) {
                flow.value = current.map { if (it.tsId == tsId) it.copy(text = text) else it }
                affected++
            }
        }
        return affected
    }

    override suspend fun updateSegmentTranscriptStatus(
        segmentId: Long,
        status: TranscriptStatus,
        error: String?,
    ) {
        recordingRepository.updateSegment(segmentId) {
            it.copy(transcriptStatus = status, transcriptError = error)
        }
    }

    override suspend fun getPendingSegments(dayId: String, limit: Int): List<AudioSegment> {
        queryCount++
        if (queryCount > guardAfterQueries) {
            throw IllegalStateException("循环护栏：getPendingSegments 已调用 $queryCount 次，疑似无限重试")
        }
        return pendingSegments(dayId, limit)
    }

    override suspend fun getAllPendingSegments(limit: Int): List<AudioSegment> =
        recordingRepository.allSegments()
            .filter { it.transcriptStatus in PENDING_OR_FAILED }
            .sortedBy { it.startMs }
            .take(limit)

    override suspend fun searchTranscripts(keyword: String, limit: Int): List<TranscriptSegment> =
        flows.values.flatMap { it.value }
            .filter { it.text.contains(keyword) }
            .take(limit)

    /** 测试辅助：某天全部转写分段（按时间升序）。 */
    fun transcriptsOf(dayId: String): List<TranscriptSegment> = flows[dayId]?.value ?: emptyList()

    /** 与 [getPendingSegments] 相同的过滤逻辑（不含护栏计数），供测试直接使用。 */
    fun pendingSegments(dayId: String, limit: Int): List<AudioSegment> =
        recordingRepository.allSegments()
            .filter { it.dayId == dayId && it.transcriptStatus in PENDING_OR_FAILED }
            .sortedBy { it.startMs }
            .take(limit)

    private companion object {
        val PENDING_OR_FAILED = setOf(TranscriptStatus.PENDING, TranscriptStatus.FAILED)
    }
}

/**
 * 测试替身：内存版 [SummaryRepository]。
 */
class FakeSummaryRepository : SummaryRepository {

    private val summaryFlows = mutableMapOf<String, MutableStateFlow<DaySummary?>>()
    private val todoFlows = mutableMapOf<String, MutableStateFlow<List<TodoItem>>>()

    var saveCount = 0
        private set

    override fun observeSummary(dayId: String): Flow<DaySummary?> =
        summaryFlows.getOrPut(dayId) { MutableStateFlow(null) }

    override suspend fun getSummary(dayId: String): DaySummary? = summaryFlows[dayId]?.value

    override fun observeTodosForDay(dayId: String): Flow<List<TodoItem>> =
        todoFlows.getOrPut(dayId) { MutableStateFlow(emptyList()) }

    override suspend fun saveSummary(summary: DaySummary, todos: List<TodoItem>) {
        saveCount++
        summaryFlows.getOrPut(summary.dayId) { MutableStateFlow(null) }.value = summary
        todoFlows.getOrPut(summary.dayId) { MutableStateFlow(emptyList()) }.value = todos
    }

    override suspend fun setTodoDone(todoId: Long, done: Boolean) {
        todoFlows.values.forEach { flow ->
            flow.value = flow.value.map { if (it.todoId == todoId) it.copy(done = done) else it }
        }
    }

    override suspend fun deleteSummary(dayId: String) {
        summaryFlows[dayId]?.value = null
        todoFlows[dayId]?.value = emptyList()
    }

    override suspend fun deleteAllSummaries(): Int {
        val count = summaryFlows.values.count { it.value != null }
        summaryFlows.values.forEach { it.value = null }
        todoFlows.values.forEach { it.value = emptyList() }
        return count
    }

    fun todosOf(dayId: String): List<TodoItem> = todoFlows[dayId]?.value ?: emptyList()
}

/**
 * 测试替身：内存版 [SettingsRepository]（默认 [RecordingSettings]，自选存储目录为 null）。
 *
 * 用于验证合并/转写链路尊重存储位置时返回 null → 不触发 SAF 复制（复制失败被主链路忽略）。
 */
class FakeSettingsRepository : SettingsRepository {

    private val settingsFlow = MutableStateFlow(RecordingSettings())

    override fun observeSettings(): Flow<RecordingSettings> = settingsFlow

    override suspend fun getSettings(): RecordingSettings = settingsFlow.value

    override suspend fun setSensitivity(level: SensitivityLevel) {
        settingsFlow.value = settingsFlow.value.copy(sensitivity = level)
    }

    override suspend fun setSilenceDurationMs(durationMs: Long) {
        settingsFlow.value = settingsFlow.value.copy(silenceDurationMs = durationMs)
    }

    override suspend fun setPrivacyMode(mode: PrivacyMode) {
        settingsFlow.value = settingsFlow.value.copy(privacyMode = mode)
    }

    override suspend fun setCloudEnhance(enabled: Boolean) {
        settingsFlow.value = settingsFlow.value.copy(cloudEnhance = enabled)
    }

    override suspend fun setEncryption(enabled: Boolean) {
        settingsFlow.value = settingsFlow.value.copy(encryption = enabled)
    }

    override suspend fun setModelsReady(ready: Boolean) {
        settingsFlow.value = settingsFlow.value.copy(modelsReady = ready)
    }

    override suspend fun setMonitorActive(active: Boolean) {
        settingsFlow.value = settingsFlow.value.copy(monitorActive = active)
    }

    override suspend fun writeHeartbeat(epochMs: Long) {
        settingsFlow.value = settingsFlow.value.copy(serviceHeartbeat = epochMs)
    }

    override suspend fun setPrivacyAck(acknowledged: Boolean) {
        settingsFlow.value = settingsFlow.value.copy(privacyAck = acknowledged)
    }

    override suspend fun setSampleRate(sampleRate: Int) {
        settingsFlow.value = settingsFlow.value.copy(sampleRate = sampleRate)
    }

    override suspend fun setStorageTreeUri(treeUri: String?) {
        settingsFlow.value = settingsFlow.value.copy(storageTreeUri = treeUri)
    }

    override suspend fun setRecordingMode(mode: RecordingMode) {
        settingsFlow.value = settingsFlow.value.copy(recordingMode = mode)
    }
}
