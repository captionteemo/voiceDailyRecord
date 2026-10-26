package com.echolog.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.echolog.app.core.common.Constants
import com.echolog.app.data.local.prefs.SettingsStore
import com.echolog.app.domain.model.RecordingMode
import com.echolog.app.domain.model.SensitivityLevel
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * [SettingsStore] 快捷录音开关字段的存储契约（QA 第一轮，**仪器测试**）。
 *
 * ## 为什么走 instrumentation
 *
 * [SettingsStore] 的持久化经由 `Context.settingsDataStore`（DataStore Preferences），
 * JVM 单测里 `Context` 只能是替身、且 `preferencesDataStore` 的委托会把首个
 * `Context` 创建的 DataStore **缓存成进程级单例**——用 mock 构造第二个实例根本不会
 * 真的重建存储，断言「持久化」会变成自欺欺人。因此用真实应用 Context 落真实文件。
 *
 * ## 为什么固定方法执行顺序
 *
 * DataStore 单例没有对外暴露「删除某个 key」的能力（[SettingsStore] 只提供写 true/false），
 * 所以「默认值 true」只能在**从未写入过**的前提下断言一次。为保证这一点：
 * 1. `@BeforeClass` 先**备份**再删掉落盘文件——必须早于任何一次 [SettingsStore] 实例化，
 *    否则单例已带着旧状态建好，删文件不再生效；
 * 2. 用例名以 `t01_/t02_...` 前缀 + `@FixMethodOrder(NAME_ASCENDING)` 固定顺序，
 *    让「默认值」用例永远先于任何写入用例执行。
 *
 * **真机副作用已收敛**：这个文件删的是设备上 App 的**真实设置文件**，
 * 因此 `@BeforeClass` 先备份到 cacheDir、`@AfterClass` 还原，
 * 跑完不会把设备上的用户设置洗成空白。
 *
 * 若将来 [SettingsStore] 补了清除接口，本文件可以去掉顺序依赖。
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class QuickRecordSettingsStoreTest {

    private companion object {

        @JvmStatic
        @BeforeClass
        fun resetBeforeClass() {
            // 真机上这是**用户真实设置**：先备份再删，用完还原，避免把设备上的 App 设置洗掉。
            backupStoreFiles()
            deleteStoreFiles()
        }

        @JvmStatic
        @AfterClass
        fun resetAfterClass() {
            // 还原用户真实设置（而不是给设备留一份空白设置）。
            restoreStoreFiles()
        }

        private fun appContext(): Context =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

        /** DataStore 落盘目录：`{filesDir}/datastore/`。 */
        private fun storeDir(): File = File(appContext().filesDir, "datastore")

        /** 本应用设置相关的落盘文件（按前缀匹配，避免硬编码文件名）。 */
        private fun storeFiles(): List<File> =
            storeDir().listFiles()
                ?.filter { it.name.startsWith(Constants.SETTINGS_DATASTORE_NAME) }
                .orEmpty()

        private fun storeFileNames(): List<String> = storeFiles().map { it.name }

        private fun backupDir(): File = File(appContext().cacheDir, "settings_store_backup")

        private fun backupStoreFiles() {
            val dir = backupDir()
            dir.mkdirs()
            storeFiles().forEach { source ->
                runCatching { source.copyTo(File(dir, source.name), overwrite = true) }
            }
        }

        private fun restoreStoreFiles() {
            val dir = backupDir()
            if (!dir.isDirectory) return
            storeDir().mkdirs()
            dir.listFiles()?.forEach { backup ->
                runCatching { backup.copyTo(File(storeDir(), backup.name), overwrite = true) }
            }
            dir.deleteRecursively()
        }

        private fun deleteStoreFiles() {
            storeFiles().forEach { it.delete() }
        }
    }

    @Test
    fun `t01_默认开启_从未写入时读到true`() = runBlocking {
        val store = SettingsStore(appContext())

        assertTrue(
            "快捷录音入口默认必须开启：默认关闭等于需求不存在（用户永远不会发现这个开关）",
            store.settings.first().quickRecordNotificationEnabled,
        )
    }

    @Test
    fun `t02_写入false后本实例与新实例都读到false_且已落盘`() = runBlocking {
        val store = SettingsStore(appContext())

        store.setQuickRecordNotificationEnabled(false)

        assertFalse(store.settings.first().quickRecordNotificationEnabled)
        assertFalse(
            "新实例也必须读到 false——证明值来自磁盘而非某个实例的内存缓存",
            SettingsStore(appContext()).settings.first().quickRecordNotificationEnabled,
        )
        assertTrue(
            "必须真的落盘（datastore 目录应出现 ${Constants.SETTINGS_DATASTORE_NAME} 文件），" +
                "实际：${storeFileNames()}",
            storeFileNames().isNotEmpty(),
        )
    }

    @Test
    fun `t03_改回true后本实例与新实例都读到true`() = runBlocking {
        val store = SettingsStore(appContext())
        store.setQuickRecordNotificationEnabled(false)

        store.setQuickRecordNotificationEnabled(true)

        assertTrue(store.settings.first().quickRecordNotificationEnabled)
        assertTrue(
            "改回 true 后新实例也必须读到 true（跨实例重读一致）",
            SettingsStore(appContext()).settings.first().quickRecordNotificationEnabled,
        )
    }

    @Test
    fun `t04_写快捷开关不污染其它设置项`() = runBlocking {
        val settings = SettingsStore(appContext()).settings.first()

        assertEquals(
            "采样率应仍是默认 ${Constants.DEFAULT_SAMPLE_RATE}",
            Constants.DEFAULT_SAMPLE_RATE,
            settings.sampleRate,
        )
        assertEquals(
            "静音断段时长应仍是默认 ${Constants.DEFAULT_SILENCE_MS}",
            Constants.DEFAULT_SILENCE_MS,
            settings.silenceDurationMs,
        )
        assertEquals("灵敏度应仍是默认 MEDIUM", SensitivityLevel.MEDIUM, settings.sensitivity)
        assertEquals("录音模式应仍是默认 AUTO_VAD", RecordingMode.AUTO_VAD, settings.recordingMode)
        assertNull("未选自选目录时应为 null", settings.storageTreeUri)
    }
}
