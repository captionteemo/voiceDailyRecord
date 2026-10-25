package com.echolog.app.di

import android.content.Context
import androidx.room.Room
import com.echolog.app.ai.asr.AsrEngine
import com.echolog.app.ai.asr.SenseVoiceAsrEngine
import com.echolog.app.data.local.EchoDatabase
import com.echolog.app.data.local.dao.AudioSegmentDao
import com.echolog.app.data.local.dao.DaySummaryDao
import com.echolog.app.data.local.dao.MarkerDao
import com.echolog.app.data.local.dao.RecordingDayDao
import com.echolog.app.data.local.dao.TodoItemDao
import com.echolog.app.data.local.dao.TranscriptSegmentDao
import com.echolog.app.data.local.prefs.ScheduleTemplateStore
import com.echolog.app.data.local.prefs.SettingsStore
import com.echolog.app.data.media.AndroidAudioPathProvider
import com.echolog.app.data.media.DayTranscriptFileExporter
import com.echolog.app.data.media.TranscriptExporter
import com.echolog.app.data.repository.RecordingRepositoryImpl
import com.echolog.app.data.repository.ScheduleTemplateRepositoryImpl
import com.echolog.app.data.repository.SettingsRepositoryImpl
import com.echolog.app.data.repository.SummaryRepositoryImpl
import com.echolog.app.data.repository.TranscriptionRepositoryImpl
import com.echolog.app.domain.repository.RecordingRepository
import com.echolog.app.domain.repository.ScheduleTemplateRepository
import com.echolog.app.domain.repository.SettingsRepository
import com.echolog.app.domain.repository.SummaryRepository
import com.echolog.app.domain.repository.TranscriptionRepository
import com.echolog.app.domain.usecase.AudioPathProvider
import com.echolog.app.domain.usecase.SegmentTranscriptExporter
import com.echolog.app.domain.usecase.ServiceController
import com.echolog.app.domain.usecase.SettleScheduler
import com.echolog.app.service.RecordingServiceController
import com.echolog.app.work.WorkScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据层依赖模块（架构 2.2 配套）。
 *
 * 职责：
 * 1. 提供 [EchoDatabase] 与 6 个 DAO（T03/T04/T05 直接注入）；
 * 2. 提供 [SettingsStore]；
 * 3. 以 `@Binds` 将 4 个仓库实现、2 个接缝实现与 ASR 引擎绑定到 domain 层接口
 *    （UI/UseCase 只见接口）。
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): EchoDatabase =
        Room.databaseBuilder(context, EchoDatabase::class.java, EchoDatabase.DATABASE_NAME)
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides
    fun provideRecordingDayDao(database: EchoDatabase): RecordingDayDao =
        database.recordingDayDao()

    @Provides
    fun provideAudioSegmentDao(database: EchoDatabase): AudioSegmentDao =
        database.audioSegmentDao()

    @Provides
    fun provideMarkerDao(database: EchoDatabase): MarkerDao = database.markerDao()

    @Provides
    fun provideTranscriptSegmentDao(database: EchoDatabase): TranscriptSegmentDao =
        database.transcriptSegmentDao()

    @Provides
    fun provideDaySummaryDao(database: EchoDatabase): DaySummaryDao = database.daySummaryDao()

    @Provides
    fun provideTodoItemDao(database: EchoDatabase): TodoItemDao = database.todoItemDao()

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context): SettingsStore =
        SettingsStore(context)

    /** 录音时间模板存储（用户需求 4：模板录音）。 */
    @Provides
    @Singleton
    fun provideScheduleTemplateStore(@ApplicationContext context: Context): ScheduleTemplateStore =
        ScheduleTemplateStore(context)

    /**
     * v1 → v2 迁移（用户需求 4）：`recording_day` 新增 `mode` 列。
     *
     * 存量数据统一回落 AUTO_VAD（等价于升级前的 VAD 自动监听行为）。
     * NOT NULL 列需带 DEFAULT，保证旧行迁移成功。
     */
    private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE recording_day ADD COLUMN mode TEXT NOT NULL DEFAULT 'AUTO_VAD'",
            )
        }
    }
}

/**
 * 仓库接口绑定（架构 3.2：领域层只依赖接口，实现可替换/可测）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindRecordingRepository(impl: RecordingRepositoryImpl): RecordingRepository

    @Binds
    @Singleton
    abstract fun bindTranscriptionRepository(impl: TranscriptionRepositoryImpl): TranscriptionRepository

    @Binds
    @Singleton
    abstract fun bindSummaryRepository(impl: SummaryRepositoryImpl): SummaryRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    /** 用户需求 4：模板录音的时间模板仓库绑定。 */
    @Binds
    @Singleton
    abstract fun bindScheduleTemplateRepository(
        impl: ScheduleTemplateRepositoryImpl,
    ): ScheduleTemplateRepository

    /** T03 接缝：用例 → 前台服务（实现见 service/RecordingServiceController）。 */
    @Binds
    @Singleton
    abstract fun bindServiceController(impl: RecordingServiceController): ServiceController

    /**
     * 结算入队绑定（T05 正式实现）：[WorkScheduler] 经 WorkManager
     * 以唯一工作名 `settle-{dayId}` 驱动 DaySettleWorker（架构 4.2/7.5）。
     */
    @Binds
    @Singleton
    abstract fun bindSettleScheduler(impl: WorkScheduler): SettleScheduler

    /**
     * T04：ASR 引擎绑定（sherpa-onnx SenseVoice）。
     * sherpa 依赖隔离在 SenseVoiceAsrEngine 单文件，AAR 未就位时其余链路不受影响。
     */
    @Binds
    @Singleton
    abstract fun bindAsrEngine(impl: SenseVoiceAsrEngine): AsrEngine

    /** T04：音频路径生成接缝实现（domain 不依赖 Context，实现落在 data 层）。 */
    @Binds
    @Singleton
    abstract fun bindAudioPathProvider(impl: AndroidAudioPathProvider): AudioPathProvider

    /**
     * 用户需求 1：片段转写 .md 导出（与录音同名）。
     * 写盘失败不影响转写主链路（内部文件仍是数据源）。
     */
    @Binds
    @Singleton
    abstract fun bindSegmentTranscriptExporter(impl: TranscriptExporter): SegmentTranscriptExporter

    /**
     * 用户需求 3：按日期导出全天转写文档（带日期的 .md）。
     * 写盘失败以 [kotlin.Result] 呈现，不影响其他链路。
     */
    @Binds
    @Singleton
    abstract fun bindDayTranscriptExporter(
        impl: DayTranscriptFileExporter,
    ): com.echolog.app.domain.usecase.DayTranscriptExporter
}
