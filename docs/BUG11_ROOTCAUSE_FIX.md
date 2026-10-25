# BUG-11 根因修复报告：结束录音后进度卡 5%、无法翻译成文字

> 状态：**已修复（代码已落地，等待真机回归）**
> 报告人：齐活林（team-lead）
> 日期：2026-10-25
> 相关：`docs/QA_REPORT_BUG11.md`（QA 首轮报告）

---

## 一、用户报告的现象

> 「实际安装后，尝试进行录音，结束录音后进度会卡在 5%，无法翻译成文字」

三个症状同时出现，且**没有任何错误提示**。这个"沉默"是本次排查的关键线索。

---

## 二、根因：WorkManager 初始化链断裂（本轮新发现，最高优先级）

### 2.1 结论

真机卡 5% 的**真正根因不是** `FOREGROUND_SERVICE_DATA_SYNC` 权限缺失，而是
**WorkManager 在 `Application.onCreate()` 之前被 androidx.startup 用默认配置初始化**，
导致 `Configuration.Provider` 永远不被查询、`HiltWorkerFactory` 形同虚设，
所有 `@HiltWorker` 实例化失败、`doWork()` **从未执行**。

该根因同时解释了三个症状，是本轮**唯一必须修复**的项。

### 2.2 字节码级证据链

全部基于 `work-runtime-2.10.0.aar` 反汇编验证（`javap -p -c`）。

#### 证据 1：默认初始化器用的是默认配置，不读 `Configuration.Provider`

`androidx/work/WorkManagerInitializer.class`：

```
public androidx.work.WorkManager create(android.content.Context);
   6: ldc  #19   // String Initializing WorkManager with default configuration.
  11: aload_1
  12: new   #25   // class androidx/work/Configuration$Builder
  16: invokespecial #27  // Configuration$Builder."<init>":()V
  19: invokevirtual #28  // Configuration$Builder.build:()Landroidx/work/Configuration;
  22: invokestatic  #32   // WorkManager.initialize:(Landroid/content/Context;Landroidx/work/Configuration;)V
  26: invokestatic  #38   // WorkManager.getInstance:(Landroid/content/Context;)Landroidx/work/WorkManager;
```

→ **默认 `Configuration.Builder`，未调用 `setWorkerFactory`**。

#### 证据 2：`Configuration.Provider` 分支永远不执行

`androidx/work/impl/WorkManagerImpl.class` → `getInstance(Context)`：

```
   6: invokestatic  #16   // getInstance:()Landroidx/work/impl/WorkManagerImpl;   ← 读 sDelegatedInstance
   9: astore_2
  10: aload_2
  11: ifnonnull     57     ← ★ 非 null 就直接返回，Configuration.Provider 分支被跳过
  14: aload_0
  15: invokevirtual #20   // Context.getApplicationContext()
  19: aload_3
  20: instanceof    #26   // class androidx/work/Configuration$Provider
  23: ifeq          47
  26: aload_3
  28: checkcast     #26
  31: invokeinterface #28 // Configuration$Provider.getWorkManagerConfiguration()
  36: invokestatic  #32   // initialize(Context, Configuration)
```

→ 只有 `sDelegatedInstance == null` 时才会走 Provider 分支。
而证据 1 的 `WorkManager.initialize` 已在 ContentProvider 阶段把它置为非 null。

#### 证据 3：反射兜底必然失败

`androidx/work/WorkerFactory.class` → `createWorkerWithDefaultFallback$fallbackToReflection`：

```
   6: aload_3
   7: iconst_2
   8: anewarray     #57   // class java/lang/Class
  13: aastore              // ← Class[0] = android/content/Context
  22: aastore              // ← Class[1] = androidx/work/WorkerParameters
  27: invokevirtual #125   // Class.getDeclaredConstructor([Ljava/lang/Class;)
  ...
  52: invokevirtual       // Constructor.newInstance
  84: ldc  "Could not instantiate"
```

→ 只尝试 `(Context, WorkerParameters)` 两参构造。
而 `DaySettleWorker` 是 `@AssistedInject` 的 **7 参构造**：

```kotlin
@HiltWorker
class DaySettleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val mergeDayAudioUseCase: MergeDayAudioUseCase,
    private val transcribeDayUseCase: TranscribeDayUseCase,
    private val summarizeDayUseCase: SummarizeDayUseCase,
    private val recordingRepository: RecordingRepository,
    private val notificationHelper: NotificationHelper,
) : CoroutineWorker(appContext, workerParams)
```

→ `NoSuchMethodException` → 抛 `"Could not instantiate"` → Worker 进入 FAILED。

#### 证据 4：Hilt 侧装配本身是好的（排除项）

`DaggerEchoLogApplication_HiltComponents_SingletonC.java`（KSP 生成）：

```java
private Map<String, Provider<WorkerAssistedFactory<? extends ListenableWorker>>> mapOfStringAndProviderOfWorkerAssistedFactoryOf() {
  return MapBuilder.<String, Provider<WorkerAssistedFactory<? extends ListenableWorker>>>newMapBuilder(5)
      .put("com.echolog.app.work.DayRolloverWorker", ...)
      .put("com.echolog.app.work.DaySettleWorker", ...)
      .put("com.echolog.app.work.HealthCheckWorker", ...)
      .put("com.echolog.app.work.ModelDownloadWorker", ...)
      .put("com.echolog.app.work.TranscribeWorker", ...)
      .build();
}
private HiltWorkerFactory hiltWorkerFactory() {
  return WorkerFactoryModule_ProvideFactoryFactory.provideFactory(mapOfStringAndProviderOfWorkerAssistedFactoryOf());
}
```

→ 5 个 Worker **全部注册**、`HiltWorkerFactory` 绑定正确。
**说明 Hilt 侧无问题，问题 100% 在「WorkerFactory 有没有被交给 WorkManager」这一步。**

### 2.3 三个症状的精确对应

| 症状 | 机制 |
|---|---|
| **进度卡 5%** | 5% 是**入队方** `StopMonitoringAndSettleUseCase:42` 写的（`updateSettleProgress(dayId, SETTLE_START_PROGRESS=5, null)`），**不是** Worker 写的。Worker 从未启动 → 无人推进 → 永远 5%。 |
| **无任何错误提示** | `settleError` 恒为 `null` —— 写错误信息的代码全在 `DaySettleWorker.doWork()` 内部，而 `doWork()` 从未执行。 |
| **无法翻译成文字** | `ModelDownloadWorker` 同样是 `@HiltWorker` → 1.6GB 内置模型从未落地到 `{filesDir}/models/` → `SenseVoiceAsrEngine.locateModelFiles()` 校验不过 → `isReady()` 恒 false → 转写必然 E-3001。 |

**失效范围**：5 个 `@HiltWorker` 全部失效 —— `DaySettleWorker` / `ModelDownloadWorker` /
`TranscribeWorker` / `DayRolloverWorker` / `HealthCheckWorker`。
即整个后台任务体系（含跨天归档、充电兜底转写、健康检查）都处于瘫痪状态。

### 2.4 修复

`app/src/main/AndroidManifest.xml`（官方 Hilt + WorkManager 标准做法）：

```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="androidx.work.WorkManagerInitializer"
        android:value="androidx.startup"
        tools:node="remove" />
</provider>
```

移除默认初始化器后，WorkManager 变为**惰性初始化**（首次 `getInstance()` 时才 `initialize`），
此时 `getApplicationContext()` 返回的 Application 实现 `Configuration.Provider`
→ `getWorkManagerConfiguration()` 被调用 → `HiltWorkerFactory` 正确装配。

**时序安全性**（已字节码验证 `Hilt_EchoLogApplication.onCreate()`）：

```java
public void onCreate() {
  hiltInternalInject();   // ← 注入早于 super.onCreate()
  super.onCreate();
}
```

`hiltInternalInject()` → `injectEchoLogApplication(...)` 注入 `workerFactory` / `workScheduler`，
**早于** `EchoLogApplication.onCreate()` 函数体。而首次 `WorkManager.getInstance()`
由 `EchoLogApplication.onCreate()` 内的 `schedulePeriodicWorkers()` 触发 →
`workerFactory` 必然已就绪 → 不会抛 `UninitializedPropertyAccessException`。

**合并后 manifest 已验证**：`androidx.work.WorkManagerInitializer` 的 meta-data
已从 `app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml`
中消失（`InitializationProvider` 保留并继续承载 lifecycle/emoji2/profileinstaller 三项）。

---

## 三、本轮修复清单（按优先级）

### P0-1 ✅ WorkManager 初始化链断裂（本轮新发现，根因）
- `app/src/main/AndroidManifest.xml`：移除 `androidx.work.WorkManagerInitializer`
- `app/src/main/java/com/echolog/app/EchoLogApplication.kt`：补充时序说明注释

### P0-2 ✅ 模型体积阈值漏洞 → 模型永久不可用
- `ModelCatalog.kt`：`sizeBytes` 改为 assets **真实字节数**（ASR 239,233,841 / LLM 1,117,320,736），
  `minSizeBytes` 改为 **90% 门槛**（原为 `minOf(sizeBytes/100, 1MB)` = 仅 1MB 门槛）
- `ModelManager.kt`：`installBundledIfNeeded()` 改为**原子安装**（`.tmp` 临时文件 + 长度等值校验 + rename）；
  `assetLength()` 用 `assets.openFd()` 取真实长度
- `ModelDownloadWorker.kt`：`installBundledModels()` 改 `suspend` 并**真正 await**，
  未就绪返回 `Result.retry()`（原为 fire-and-forget → 进程被杀则永不再装）

> 原逻辑：拷贝 1.35GB 途中进程被杀 → 留下 >1MB 的半成品 → 骗过就绪判定 →
> sherpa-onnx 加载失败 → `SenseVoiceAsrEngine.failedModelStamp` 记住指纹 → **永不重试**。

### P0-3 ✅ Android 14+ 前台服务类型专属权限
- `AndroidManifest.xml`：补 `FOREGROUND_SERVICE_DATA_SYNC`
  （`DaySettleWorker` 以 `FOREGROUND_SERVICE_TYPE_DATA_SYNC` 运行，缺失则 `setForeground` 抛异常）
  - 注：此修复**必要但不充分** —— Worker 当时根本没起来

### P0-4 ✅ 进度被回退 5%（精确复现用户症状）
- `DaySettleWorker.kt`：两处 `notificationHelper.cancel(...)` 包 `runCatching`
  （此前异常冒泡到总兜底 → 已 DONE/100 的天被写回 5%）
- 总兜底加守卫：`current.settleProgress < PROGRESS_DONE` 才回写

### P0-5 ✅ 进度写入竞态（QA 发现）
- `DaySettleWorker.kt`：`progressScope.cancel()` → 改为「单写入协程 + `Channel.UNLIMITED` FIFO + `close()` + `join()`」
  - 原问题：乱序写入 / 未调度写被丢弃 / 在途写晚于 100% 落库导致**进度回退 + `settleError` 被 null 覆盖**

### P1-1 ✅ 合并失败无限重试 → 永久卡 5%
- `DaySettleWorker.kt` / `SettlePendingDaysUseCase.kt`：
  `ERROR_MERGE_FAILED` 不再 `updateSettleProgress(5, msg)` + `Result.retry()`，
  改为「跳过合并 → 继续转写与总结」，失败原因写入 `settleError`
  - WorkManager 的 `Result.retry()` **无次数上限** + EXPONENTIAL 退避 → 确定性失败会无限重试

### P1-2 ✅ 合并失败提示在 UI 不可见
- `Constants.kt`：新增 `SETTLE_NOTE_PREFIX = "提示："`
- `TodayViewModel.kt`：新增 `isSettleFailed` / `isSettleNote` / `showSettleNoteWhenDone`
- `TodayScreen.kt`：新增 `SettleNoteCard`（`isDayDone` 分支展示，**无重试按钮**）
- **零 Room schema 迁移**（避免真机升级迁移风险）

### P1-3 ✅ 首启模型从未落地
- `EchoLogApplication.kt`：`workScheduler.scheduleModelInstall()`（无网络约束，纯本地拷贝）
- `WorkScheduler.kt`：新增 `scheduleModelInstall()`，复用 `ModelDownloadWorker` + `EXTRA_ONLY_INSTALL`
  - 刻意不新建 `@HiltWorker`：新增会触发 Hilt 在 `debugUnitTest` 变体重新生成
    `Hilt_EchoLogApplication` 基类，与 `debug` 变体字节码转换产物同名冲突 → 单元测试编译失败

### P2-1 ✅ 「结束录音」时序竞态
- `MergeDayAudioUseCase.kt`：`awaitSettledSegments()` 轮询等待片段收尾落库
  （`stopSettle` 只投递 Intent 就返回，收尾发生在音频循环退出后 ≤1 帧）
  轮询间隔 100ms → **50ms**，上限 3s 保留

### P2-2 ✅ 采样率混用 / 损坏片段拖垮全天合并
- `AudioMerger.kt`：逐个校验并跳过坏片段，全部无效才抛 `IOException`
- （可达路径：设置页可运行时切换采样率，但 `RecordingService.handleStart` 只在服务启动时读一次）

### P2-3 ✅ 确定性「无转写内容」终态（本轮新发现）
- `Constants.kt`：新增 `ERROR_SUMMARY_NO_CONTENT = "E-4003"`
- `SummarizeDayUseCase.kt`：无转写内容时返回该错误码（原误用 `ERROR_ASR_FAILED`）
- `DaySettleWorker.kt` / `SettlePendingDaysUseCase.kt`：识别为**终态** →
  跳过总结、归档 DONE/100、写提示，**不再 `Result.retry()`**
  - 片段已全部 DONE，重试不可能产出文字 → 无限重试会让该天永久停在 **95%**

---

## 四、语义冲突测试的处理

`SettlePendingDaysUseCaseTest.合并失败的天不得标记为DONE` 的旧契约
（合并失败 → 不归档）与新契约（合并失败 → 降级归档 DONE/100）直接矛盾。
已改写为 `合并失败的天降级归档为DONE并保留合并失败提示`，
断言 `state == DONE` / `settleProgress == 100` / `settleError` 以「提示：」开头。

> 保留「不归档」语义的是 **ASR 模型缺失**（E-3001）用例 —— 该场景重试有意义
> （用户装好模型后可重跑），仍必须保持 SETTLING 态。

---

## 五、真机回归验证清单

### 5.0 已完成的真机安装验证（2026-10-25）

设备：**小米 2203121C（序列号 `512e6a3e`）/ Android 15 / SDK 35** —— 正是
`FOREGROUND_SERVICE_DATA_SYNC` 强制生效的版本。

| 验证项 | 结果 |
|---|---|
| 签名一致性（新 APK vs 设备端已装版） | SHA-256 均为 `0df3bb2c…6e8fc`（Android Debug）→ 可覆盖安装 |
| 安装 | `adb install -r -d` → **Success**（1.69GB / 55s） |
| 用户数据 | 完整保留（`files/models/`、`datastore/`、`audio/`） |
| **已安装包内 `WorkManagerInitializer`** | **不在包内** ✅（直接 pull 设备上的 base.apk 解析） |
| 已安装包内 `InitializationProvider` | 在包内 ✅（仍承载 lifecycle/emoji2/profileinstaller） |
| 已安装包内两个 FGS 权限 | 均在包内 ✅ |
| **`Could not instantiate` 出现次数** | **0**（修复前必现）✅ |
| `ModelDownloadWorker` | `Starting work` → **`Worker result SUCCESS`** ✅（此前从未成功执行） |
| `HealthCheckWorker` / `DayRolloverWorker` | `Worker result SUCCESS` ✅ |
| `TranscribeWorker` | `Worker result RETRY` —— **预期行为**（充电 + 电量≥50% 约束，未充电时主动退避，非故障） |
| 模型文件完整性 | asr 239,233,841 / tokens 315,894 / llm 1,117,320,736 字节（与 assets 精确一致） |

一键复跑：`bash docs/verify_bug11.sh`

### 5.1 关键观测点（新增，验证根因修复）

```bash
# ① 确认不再有反射兜底失败
adb logcat | grep -E "Could not instantiate|WorkerFactory"

# ② 确认 Worker 正常调度
adb shell dumpsys jobscheduler | grep -A3 com.echolog.app

# ③ 确认模型真正落地（不是 0 / 1MB 半成品）
adb shell run-as com.echolog.app ls -l files/models/asr/    # model.int8.onnx ≈ 239MB
adb shell run-as com.echolog.app ls -l files/models/llm/    # *.gguf ≈ 1.1GB
```

### 5.2 四条关键路径

| 路径 | 步骤 | 预期结果 |
|---|---|---|
| **P1 录音→结束→转写** | 开始全天录音 → 结束录音 | 进度 5 → 8 → 15 → 80 → 95 → 100；**出现文字稿**；`settleError` 为空或以「提示：」开头 |
| **P2 模型首启落地** | 全新安装 → 首次启动 → 等待 | `files/models/asr/model.int8.onnx` ≈ 239MB；`files/models/llm/*.gguf` ≈ 1.1GB；模型页显示「已就绪」 |
| **P3 合并失败降级** | 制造损坏片段 → 结束录音 | 转写**照常完成**、进度到 100、UI 显示「合并音频失败…」提示（**无重试按钮**） |
| **P4 纯静音录音** | 全程静音 → 结束录音 | 归档 DONE/100，提示「当天未识别到语音内容…」（**不卡 95%**） |

### 5.3 通过标准

- ✅ 三个症状**全部消失**：不卡 5%、有文字稿、有错误提示
- ✅ 日志中**无** `Could not instantiate`
- ✅ `settleProgress` 单调递增，**从不回退**
- ✅ 任意失败路径下 `settleError` **非 null**（无静默失败）

---

## 六、涉及文件清单

### 生产代码
```
app/src/main/AndroidManifest.xml                                   ← P0-1 根因修复
app/src/main/java/com/echolog/app/EchoLogApplication.kt            ← P0-1 / P1-3
app/src/main/java/com/echolog/app/core/common/Constants.kt         ← P1-2 / P2-3
app/src/main/java/com/echolog/app/core/util/EchoLog.kt             ← 新增（日志封装）
app/src/main/java/com/echolog/app/ai/model/ModelCatalog.kt         ← P0-2
app/src/main/java/com/echolog/app/ai/model/ModelManager.kt         ← P0-2
app/src/main/java/com/echolog/app/work/DaySettleWorker.kt          ← P0-4 / P0-5 / P1-1 / P2-3
app/src/main/java/com/echolog/app/work/ModelDownloadWorker.kt      ← P0-2
app/src/main/java/com/echolog/app/work/WorkScheduler.kt            ← P1-3
app/src/main/java/com/echolog/app/data/media/AudioMerger.kt        ← P2-2
app/src/main/java/com/echolog/app/domain/usecase/MergeDayAudioUseCase.kt    ← P2-1
app/src/main/java/com/echolog/app/domain/usecase/SettlePendingDaysUseCase.kt ← P1-1 / P2-3
app/src/main/java/com/echolog/app/domain/usecase/SummarizeDayUseCase.kt     ← P2-3
app/src/main/java/com/echolog/app/domain/usecase/StopMonitoringAndSettleUseCase.kt ← （5% 写入点，定位锚点）
app/src/main/java/com/echolog/app/ui/today/TodayViewModel.kt        ← P1-2
app/src/main/java/com/echolog/app/ui/today/TodayScreen.kt          ← P1-2
```

### 测试代码
```
app/src/test/java/com/echolog/app/data/media/AudioMergerRobustnessTest.kt      ← QA
app/src/test/java/com/echolog/app/work/DaySettleWorkerSettlePolicyTest.kt      ← QA
app/src/test/java/com/echolog/app/domain/usecase/MergeDayAudioUseCaseSettleRaceTest.kt ← 工程师
app/src/test/java/com/echolog/app/domain/usecase/SettlePendingDaysMergeFailureTest.kt  ← 工程师
app/src/test/java/com/echolog/app/domain/usecase/SettlePendingDaysUseCaseTest.kt      ← 语义冲突改写
```

---

## 七、经验沉淀

1. **「沉默」是最强的线索**：用户说"没有任何提示"时，优先怀疑
   **代码根本没执行**，而不是"执行了但没写日志"。
2. **「进度卡在 X%」要立刻定位 X% 是谁写的**：本例 5% 由**入队方**写，
   而非 Worker 写 —— 这一步直接排除了"Worker 内部逻辑 bug"整个方向。
3. **Hilt + WorkManager 必须移除默认初始化器**，这是官方文档的硬要求；
   漏掉后 `@HiltWorker` 会静默失效，且**没有任何编译期或运行期告警**。
4. **确定性失败绝不能 `Result.retry()`**：WorkManager 的 retry 无次数上限，
   确定性失败 + 无限重试 = 真机上永久卡住。本项目因此把
   「合并失败」「无转写内容」都改为降级归档。
5. **字节码是唯一可信的依赖行为证据**：本轮对 `work-runtime-2.10.0` 的
   三段反汇编（Initializer / getInstance / fallbackToReflection）
   一次性锁定根因，比读文档快且不会漏掉版本差异。
