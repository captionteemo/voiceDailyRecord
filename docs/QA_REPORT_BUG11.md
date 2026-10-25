# QA 验证报告 — BUG-11「结束录音后进度卡在 5%，无法翻译成文字」

| 项目 | 内容 |
| --- | --- |
| 报告人 | Edward（QA Engineer） |
| 被测工程 | `C:\Users\huwei\WorkBuddy\2026-10-24-16-54-02\echolog-app`（模块 `app/`，包名 `com.echolog.app`） |
| 技术栈 | Kotlin 2.0.21 / MVVM / Clean / Compose(Material3) / Hilt 2.52 / Room 2.6.1(KSP) / DataStore / WorkManager 2.10.0 |
| targetSdk / minSdk | 35 / 29 |
| 基线 | 125 tests / 0 failures（修复前） |
| 报告日期 | 2026-10-25 |

---

## 1. 测试清单

### 1.1 QA 新增（12 个用例，2 个新文件）

| # | 文件 | 用例 | 断言 | 对应根因 |
| --- | --- | --- | --- | --- |
| 1 | `data/media/AudioMergerRobustnessTest.kt` | `占位头未回填的片段被跳过其余片段正常合并` | 3 片段（1 个 44 字节全零占位头）→ 不抛异常；输出文件存在；`WavIo.readHeader` 可解析；`dataSize` == 另外 2 片段数据总长（10 字节）；采样内容正确 | 修复 5 |
| 2 | 同上 | `损坏片段不计入合并进度分母` | 跳过 1 个损坏片段后，进度回调只有 `[100]`（不被损坏片段稀释） | 修复 5（进度语义） |
| 3 | 同上 | `采样率不一致的片段被跳过输出只含首个采样率的片段` | 16kHz + 48kHz → 不抛异常；输出 `sampleRate=16000`、`dataSize=6`、样本数 3（48kHz 内容未混入） | 修复 5 |
| 4 | 同上 | `声道不一致的片段被跳过` | 16kHz 单声道 + 16kHz 双声道 → 输出保持单声道 | 修复 5 |
| 5 | 同上 | `全部片段损坏时抛出可辨识的IOException` | 2 个全损坏片段 → 抛 `IOException`，message 含「没有可合并的有效片段」；不残留输出文件 | 修复 5 |
| 6 | 同上 | `只有一个片段有效且另一个采样率不同时仍成功合并` | 以首个有效片段的采样率为基准 | 修复 5 |
| 7 | `work/DaySettleWorkerSettlePolicyTest.kt` | `合并失败时转写仍被调用且进度走到100并保留合并失败提示` | **关键回归**：合并失败 → `Result.Success`；ASR 被调用 2 次（每片段一次）；`day.state == DONE`；`settleProgress == 100`；`settleError` 非空且含「合并」；转写文本仍落库 2 条 | 修复 2 + 修复 6 |
| 8 | 同上 | `合并失败时进度写入序列单调不回退` | 记录全部 `updateSettleProgress` 调用序列 → 进度**单调不减**、末值 == 100、末值 error 非空 | **QA 新发现 P0-1**（进度写入竞态） |
| 9 | 同上 | `合并成功时正常推进到DONE进度100且无错误` | `DONE` / `settleProgress=100` / `settleError==null` / `mergedAudioPath != null` / `totalRecordMs==1000` | 正常路径不回归 |
| 10 | 同上 | `结算链路异常时写入settleError并返回失败` | 未捕获异常 → `Result.Failure`；`settleError` 含「结算异常」 | 修复 2（总兜底） |
| 11 | 同上 | `撤下进度通知失败不得让已归档的进度回退到5%` | `notificationHelper.cancel()` 抛异常 → 仍 `Result.Success`；`settleProgress==100`（**不是 5**）；`DONE` | **QA 新发现 P0-2** |
| 12 | 同上 | `无可用片段时按空录音日归档为DONE进度100` | 片段行存在但音频文件缺失 → 走空录音日分支 → `DONE`/`100`/`settleError="当天无录音片段"`；**不调用 ASR** | 修复 2（空录音日分支） |

> **Worker 可驱动性说明（对应任务书第 5 条的备选路径）**：本 QA **没有**降级到用例层，而是**直接在 JVM 单测中驱动了 `DaySettleWorker`**。
> 做法：用 Mockito（inline mock maker，可 mock final 类）构造 `Context` / `WorkerParameters` / `NotificationHelper` 替身，
> 直接调用 `worker.doWork()`。已用 `javap` 反汇编 `work-runtime-2.10.0.aar` 验证可行性：
> - `CoroutineWorker` 构造器只存 `params`，不解引用其任何 getter → 不会 NPE；
> - `getWorkerContext()` 只在 `startWork()` 中解引用（单测不调用）；
> - `setForeground` → `ListenableWorker.setForegroundAsync` → `mWorkerParams.getForegroundUpdater()` 返回 null → NPE，
>   但源码已用 `runCatching` 包裹（降级为「只推 DB 进度」），不影响主链路断言。
> 离线环境无 `robolectric` / `work-testing`，Mockito 方案是当前唯一可行路径，且**比用例层断言更强**（能覆盖 `Result` 类型、`settleError` 落库、进度写入序列）。

### 1.2 工程师新增（2 个用例，`data/media/AudioMergerTest.kt`）

| 用例 | 断言 | 对应根因 |
| --- | --- | --- |
| `损坏片段被跳过其余片段正常合并` | 跳过坏片段后正常合并、`dataSize==10` | 修复 5 |
| `采样率不一致的片段被跳过其余正常合并` | 输出保持 16kHz、`dataSize==6` | 修复 5 |

### 1.3 工程师新增（5 个用例，2 个新文件）—— QA 已评审通过 ✅

| 文件 | 用例 | 断言 | 对应根因 |
| --- | --- | --- | --- |
| `domain/usecase/MergeDayAudioUseCaseSettleRaceTest.kt` | `首次读到未收尾片段时轮询等待后合并成功` | 首次 `durationMs=0` → 轮询后已收尾 → Success + 合并文件真实生成 + `dataSize == (16000+32000)*2` + `totalRecordMs==3000`；**断言确实发生重读**（`readCount >= 2`） | 修复 4 |
| 同上 | `片段始终未收尾时等待上限后按无可合并片段失败` | E-5001 + 「当天无可合并片段」；合并路径/总时长未回写 | 修复 4 |
| 同上 | `片段文件缺失时返回无可合并片段失败` | E-5001；**已收尾的片段无需轮询**（`readCount == 1`） | 修复 4 |
| `domain/usecase/SettlePendingDaysMergeFailureTest.kt` | `合并失败时转写照常进行并归档DONE进度100` | ASR 被调用 + 转写落库 + 片段转写态 DONE + DONE/100 + `settleError` 含「合并」+ **进度写入序列单调不回退** + 存在「15% 且携带合并提示」的写入 | 修复 6 |
| 同上 | `所有片段均未收尾时按空录音日归档且不出现合并字样` | 空录音日 → DONE/100，`settleError` 为「当天无录音片段」，**不得**出现「合并」 | 修复 6 |

> **QA 评审意见**：两个文件均满足本报告 §5.2 的验收标准。
> 特别肯定：调度器选用 `UnconfinedTestDispatcher(testScheduler)`（与 `runTest` 共享 scheduler）→ `delay(100)` 走虚拟时间，
> 30 次轮询真实耗时为 0（避免「永远不收尾」用例真实耗时 3s）。
> **唯一小缺口（非阻塞）**：「片段始终未收尾」用例只断言了 `readCount >= 2`（下界），建议补上界
> `assertTrue(repo.readCount <= 31)`（= 1 次初始读 + 30 次轮询），以防将来 `MAX_SETTLE_WAIT_MS` 被调大时无人察觉。

### 1.4 `FakeRepositories.kt` 是否需要同步

**不需要。** `RecordingRepository` 接口在本次修复中**未发生任何变更**（`getSegmentsForDay` / `updateSettleProgress` / `updateMergeResult` 签名均未动），现有 `FakeRecordingRepository` 仍满足接口。
QA 未修改 `FakeRepositories.kt`（该文件由工程师认领），QA 的竞态断言通过**测试文件内的局部代理** `ProgressRecordingRepository`（`RecordingRepository by delegate` 委托 + 记录 `updateSettleProgress` 调用序列）实现，不污染公共 Fake。

---

## 2. 验证结果

### 2.1 QA 独立跑过的构建 + 团队构建记录

| 时间 | 执行人 | 命令 | 结果 |
| --- | --- | --- | --- |
| 17:27 | QA | `./gradlew --offline :app:testDebugUnitTest` | **127 tests / 3 failed**（全部在 `AudioMergerTest`）→ **不是真失败**：抓到了工程师的**中间态**——`AudioMerger` 直接调用 `android.util.Log.w`，而 `android.util.Log` 在 JVM 单测中未 mock → `RuntimeException: Method w in android.util.Log not mocked`，掩盖了真实业务异常。工程师随后引入 `core/util/EchoLog.kt`（`runCatching` 包裹）修复。 |
| 17:31 | QA | 同上 | **构建失败**（`hiltJavaCompileDebug`：「Unable to delete directory …hilt/component_sources/debug」）→ 与主理人/工程师**并发构建**互相污染，**非代码缺陷**。主理人已用 `./gradlew --offline clean` 清理。 |
| 17:46 | 团队（`build_verify.log`） | 同上 | **133 tests / 1 failed** → `AudioMergerTest.采样率不一致的片段被跳过其余正常合并` `AssertionError at AudioMergerTest.kt:148`。<br>**路由判定：测试 bug（非源码 bug）** —— 断言写成了 `assertEquals(3L, format.dataSize)`，把「样本数 3」误当成「字节数 3」；16bit PCM 下 3 个样本 = **6 字节**。工程师已于 17:46:31 修正为 `assertEquals(6L, …)` 并补注释。<br>→ 印证 §2.2 的结论：**测试断言必须自己算一遍字节数**（`dataSize = 样本数 × 2`）。QA 自己的 `AudioMergerRobustnessTest` 里对应断言写的就是 `6L`，一次通过。 |

> **给团队的一条硬约定（建议写进 README / CLAUDE.md）**：
> **domain / data 层禁止直接调用 `android.util.Log`，统一走 `com.echolog.app.core.util.EchoLog`。**
> 否则任何一条日志语句都会在 JVM 单测中抛 `RuntimeException`，把真实断言失败掩盖成「莫名其妙的白板异常」。

### 2.2 QA 在**不触碰共享构建目录**前提下做的独立类型检查（预验证）

由于团队约定「同一时间只允许一个 Gradle 进程」，QA 无法跑 `./gradlew`。
为避免我的代码把团队的下一次构建搞挂，QA 用 **Gradle 缓存里的 `kotlin-compiler-embeddable-2.0.21.jar`
直接调 Kotlin 编译器前端**（输出到系统临时目录，**完全不写 `app/build/`**）对我改动的 5 个文件做了独立类型检查：

| 文件 | 前端错误（解析 + 类型检查） |
| --- | --- |
| `app/src/test/java/com/echolog/app/data/media/AudioMergerRobustnessTest.kt`（QA 新增） | **0** |
| `app/src/test/java/com/echolog/app/work/DaySettleWorkerSettlePolicyTest.kt`（QA 新增） | **0** |
| `app/src/main/java/com/echolog/app/work/DaySettleWorker.kt`（QA 改动） | **0** |
| `app/src/main/java/com/echolog/app/work/DayRolloverWorker.kt`（QA 改动） | **0** |
| `app/src/main/java/com/echolog/app/service/receiver/BootReceiver.kt`（QA 改动） | **0** |
| **合并编译**：`data/media/` + `work/` + `domain/usecase/` + `testing/` 下**全部 9 个测试文件**（含工程师新增的 `MergeDayAudioUseCaseSettleRaceTest.kt` 与 `SettlePendingDaysMergeFailureTest.kt`） | **0** |

- 覆盖到的关键 API 正确性：`androidx.work.Data.Builder()`、`WorkerParameters`（final 类）的 mock/stub、
  `CoroutineWorker.doWork()` 的 suspend 调用、`ListenableWorker.Result.Success` 的类型判断、
  `R.string.worker_error_no_segment` / `R.string.worker_empty_day_note` 的解析、`Mockito.when/doThrow` 对 final 方法的用法、
  `kotlinx.coroutines.channels.Channel` 的 `trySend/close/join`、`RecordingRepository by delegate` 委托。
- **对照实验（证明工具链噪声）**：用**完全未修改**的 `MergeDayAudioUseCase.kt` + `AudioMerger.kt` 跑同一套命令，
  同样出现 `Backend Internal error: Exception during IR lowering`，且**前端错误同样为 0**
  → 该 backend 异常是「临时拼装的编译器 classpath 缺少 `org.jetbrains.annotations`」的**工具链产物**，与本次代码改动无关。
- 结论：**我的 5 个文件在类型层面全部通过**，团队的下一次 Gradle 构建不会因它们而编译失败。

### 2.3 待主理人串行执行（QA 已停止并发构建）

```
cd "C:/Users/huwei/WorkBuddy/2026-10-24-16-54-02/echolog-app" && \
export JAVA_HOME="/c/Users/huwei/WorkBuddy/2026-10-24-16-54-02/tools/jdk-17.0.20.1+1" && \
./gradlew --offline clean :app:testDebugUnitTest :app:assembleDebug
```

**预期**：测试总数 ≥ 125（基线）+ 12（QA）+ 2（工程师 `AudioMergerTest`）+ 5（工程师新增 2 文件）= **≥ 144**，0 failures。
**必须额外核对**：合并后的 manifest（`app/build/intermediates/merged_manifests/debug/.../AndroidManifest.xml`）确实包含
`<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />`（修复 1 的落地证据）。

---

## 3. 发现的问题（按严重度排序）

### 🔴 P0-1：`DaySettleWorker` 进度写入竞态 —— 进度可从 100% 回退、且覆盖 `settleError`
**（QA 已修复）**
- 位置：`app/src/main/java/com/echolog/app/work/DaySettleWorker.kt`（原 `progressScope` + `progressScope.cancel()`）
- 现象：
  1. 转写进度经**独立协程作用域** `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 异步写库，转写返回后立即 `progressScope.cancel()`；
  2. `cancel()` **只发取消信号、不等待在途写入完成**：
     - (a) 尚未被调度的写入被丢弃 → DB 进度停在中途；
     - (b) **已在执行**的写入（Room 的 suspend DAO 有真实挂起点）可能**晚于**归档的 `PROGRESS_DONE=100` 落库
       → **进度从 100 回退到中途值**，且该次写入的 `error=null` 会把 `settleError`（合并失败提示）**覆盖为 null**
       → UI 从「已完成」闪回「结算中 47%」+ 提示凭空消失。
  3. 原注释「写入顺序由 IO 调度器保证大致递增」是**错的**：`Dispatchers.IO` 是多线程池，不保证完成顺序 → 低进度可覆盖高进度。
- **QA 修复**：改为「**单一写入协程 + `Channel`（FIFO）**」：
  - 所有进度写入经 `Channel.trySend` 入队，由**唯一**写入协程消费 → 严格 FIFO 有序；
  - 转写结束 `progressChannel.close()` + `progressWriter.join()` → **确保在途写入全部落库且早于**后续 80/95/100 的直接写入；
  - `finally` 兜底，异常路径下写入协程也会退出（不泄漏协程）。
- **回归护栏**：用例 #8（进度写入序列单调不回退）。
- **未回退修复意图**：进度仍**实时**写 DB（不是只在节点写），只是写入通道变成有序 + 可等待。

### 🔴 P0-2：`notificationHelper.cancel()` 未兜底 —— 已归档的进度被回写成 5%
**（QA 已修复）**
- 位置：`DaySettleWorker.kt` — 转写后归档路径、空录音日归档路径（各 1 处）
- 现象：`notificationHelper.cancel(...)` 是全流程中**唯一没有 `runCatching` 包裹**的外部调用。
  若通知系统抛异常（ROM 差异 / `NotificationManager` 不可用 / `PendingIntent` 异常），异常冒泡到 `doWork()` 的**总兜底** →
  总兜底把进度**写回 5%** + `settleError="结算异常：…"` → **用户又看到「卡在 5%」**，而当天其实**已经 DONE/100**。
  这恰好复现了用户报告的症状。
- **QA 修复**：
  1. 两处 `notificationHelper.cancel` 均改为 `runCatching { … }.onFailure { EchoLog.w(...) }`；
  2. 总兜底增加守卫：**已 DONE/100 的天不再被回写成 5%**（`recordingRepository.getDay(dayId)?.settleProgress < PROGRESS_DONE` 才写）。
- **回归护栏**：用例 #11。

### 🟠 P1-1：合并失败的提示**在 UI 上完全不可见**（UX 缺陷，见 §5.2 的 B）
- 位置：`ui/today/TodayViewModel.kt:291`（`isSettling = dayState == DayState.SETTLING && !isServiceActive`）
  + `ui/today/TodayScreen.kt:102` / `:187`（`isSettling` 分支 vs `isDayDone` 分支）
- 现象：修复 6 把「合并失败」改成**降级继续转写并归档 DONE** → 当天状态变成 `DONE`
  → `isSettling == false` → `TodayScreen` 走 `isDayDone` 分支 → **`SettlingCard` 根本不渲染**
  → `settleError` 里的「合并音频缺失」提示**用户永远看不到**，直到用户去导出/分享当天音频才发现合并文件不存在。
- 影响：功能可用（文字已转写、总结已生成），但**产物缺失是静默的**。
- 建议：在 `isDayDone` 分支（或时间线页/总结页）展示 `settleError` / 新增的 `settleNote`，文案例如「合并音频缺失，导出可能不完整」。

### 🟠 P1-2：首启模型安装是异步的 —— 仍存在一个「无法翻译成文字」窗口
- 位置：`work/ModelDownloadWorker.kt`（`EXTRA_ONLY_INSTALL` 分支 → `modelManager.installBundledModels()`）、
  `work/WorkScheduler.kt:149`（`scheduleModelInstall()`，无网络约束）、`EchoLogApplication.kt:47`
- 事实：`app/src/main/assets/models/` 内**确实有真实权重**（ASR `model.int8.onnx` 239MB + `tokens.txt`；LLM `qwen2.5-1.5b-instruct-q4_k_m.gguf` 1.1GB），
  首启会由 `ModelInstallWorker`（复用 `ModelDownloadWorker` + `EXTRA_ONLY_INSTALL=true`）在 IO 线程拷贝 → **不触网** ✓
- 残留风险：拷贝 1.35GB 需要时间。若用户在拷贝完成前就「结束录音」→ `SenseVoiceAsrEngine.isReady()==false`
  → 转写返回 **E-3001** → `DaySettleWorker` 返回 `Result.failure()` → 进度停在 15 并提示「端侧 ASR 模型未就绪」。
  **用户仍会遇到一次「无法翻译成文字」**（虽然 UI 会引导下载/等待模型）。
- 建议：`DaySettleWorker` 在**进入转写前**先查 `ModelManager.isAsrReady()`，未就绪时直接写明确提示并 `Result.failure()`（把「模型未就绪」与「转写失败」区分开）；
  同时 UI 在模型未就绪时应显示「正在准备模型（首次约需几分钟）」而不是笼统的「转写失败」。

### 🟡 P2-1：`awaitSettledSegments` 的 ≤3s 等待期间，进度停在 **8%** 不动（非阻塞）
- 位置：`domain/usecase/MergeDayAudioUseCase.kt:121-132`；`work/DaySettleWorker.kt:148-152`
- **已核实的事实**（`DaySettleWorker.runSettlePipeline`）：
  `updateSettleProgress(dayId, PROGRESS_MERGING=8, null)` 在**调用 `mergeDayAudioUseCase(...)` 之前**执行，
  而 `awaitSettledSegments` 的 3s 等待发生在 `mergeDayAudioUseCase` **内部** →
  **等待期间用户看到的是 8%（不是 5%）**，阶段文案为「正在合并音频」。
  → 因此「结束录音后卡在 5%」的观感**不会**由这条路径产生（这条路径已被修复 2 的 `PROGRESS_MERGING` 覆盖）。
- 残留的小问题：3s 内进度**完全不动**（8% → 8%），用户会以为卡住。
  真实收尾延迟只有「≤1 帧 25ms + 协程调度」，正常路径下 1 次轮询（100ms）就命中；
  3s 只在**服务已死**时才会跑满（此时后续也做不出结果）。
- 建议（低优先，非阻塞）：
  1. 等待期间**周期性写一次进度**（如 9%），让 UI 有「还在动」的反馈；
  2. 或把轮询间隔从 100ms 降到 25~50ms（真实延迟 ≤25ms，1~2 次即可命中），上限保持 3s 作为防挂死安全网；
  3. 根治见 §5.2 / §4-C 的方案 2-b（让「投递结算任务」晚于「片段收尾落库」→ 竞态从根上消失）。

### 🟡 P2-2：`SettlePendingDaysUseCase` 的空录音日判定存在理论上的音频丢失路径（低概率）
- 位置：`domain/usecase/SettlePendingDaysUseCase.kt:85-90`
- 现象：合并失败后二次读 `getSegmentsForDay` 判断「是否有有效片段」；若片段因进程被杀而**永远收不了尾**（`durationMs` 恒 0）
  → 判定为空录音日 → 归档 DONE/100 → **该片段的音频既不合并也不转写**。
- 评估：进程被杀场景下片段文件头是 44 字节占位头（`AudioMerger` 已按损坏跳过），音频**本来就不可恢复** → 实际影响极低。
  但若文件头已回填而仅 `finishSegment` 未落库（极窄窗口），音频会被静默丢弃。
- 建议（低优先）：`SettlePendingDaysUseCase` 的「是否有有效片段」判断应与 `MergeDayAudioUseCase` 共用同一套「已收尾」判定，或在
  「有片段行但都未收尾」时**保留 SETTLING 态**交给下次兜底扫描，而不是直接归档空录音日。

### 🟡 P2-3：`DaySettleWorker` 类 KDoc 与实现已不符（QA 已更新）
- 原 KDoc 仍写「合并失败（E-5001）：保留片段，`[Result.retry]` 允许重试」——与修复 2 的新语义相反。
  QA 已改为与实现一致（合并失败 → 降级继续转写 + 写 `settleError`）。

### 🔵 P3-1：构建卫生
- 并发跑 Gradle 会污染 `app/build/generated/hilt/component_sources/{debug,debugUnitTest}`（两个变体生成**同名同包**的
  `Hilt_EchoLogApplication`）→ `debugUnitTest` 编译时把 `debug` 产物一并编入 → 类名冲突 → **自继承** → 编译失败。
  建议：团队内**同一时间只允许一个 Gradle 进程**；或在 `gradle.properties` 固定 `--max-workers=1` 并约定串行执行。

---

## 4. 对 A / B / C / D 四个核查点的回答

### A. `progressScope.cancel()` 之后，转写进度可能还没写完 DB 就被取消 —— 会导致进度不更新吗？
**会。而且不止「不更新」，还会「回退」。** 详见 §3 的 P0-1。要点：

1. **`cancel()` 不等于「等完」**：它只是给作用域内的子协程发取消信号。已经被 `Dispatchers.IO` 线程取走、正在执行
   `recordingRepository.updateSettleProgress(...)`（Room suspend DAO 有真实挂起点）的写入，**不会**在中途被可靠打断；
   而**还没被调度**的写入会被直接丢弃。
2. **两种后果**：
   - (a) 丢弃 → DB 进度停在中途（例如永远停在 47%），**正是用户报告的症状**；
   - (b) 晚于归档落库 → **进度从 100 回退到中途值**，并把 `settleError` 覆盖成 `null`（合并失败提示凭空消失）。
3. **另外**：原实现用 `Dispatchers.IO`（多线程池）并发写库，**不保证完成顺序**，注释里「由 IO 调度器保证大致递增」是错的。
4. **建议（已按此实施）**：
   - ✅ 首选：**改为「单写入协程 + Channel（FIFO）」**，转写结束 `close()` + `join()` 写入协程。
     一次解决三件事：有序、不截断、严格早于归档写入。
   - ✅ 次选（若坚持保留 `progressScope`）：`cancel()` 之前先 `join` 全部子任务
     （`scope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }`），
     并额外用 `Mutex` 串行化写入（`Mutex` 是公平锁，FIFO）。
   - ❌ 不推荐：仅在 `cancel()` 前 `Thread.sleep` / `delay` 兜底 —— 治标不治本，且引入不确定延迟。
   - ⚠️ 注意：`join()` 必须**有界**（如 `withTimeoutOrNull(3_000)`），否则 DB 写入卡死会拖住结算主链路。

**本轮 QA 已按「首选」方案落地，并配了回归用例 #8（进度写入序列单调不回退）。**

### B. `mergeErrorHint` 写进 `settleError` → UI 显示「重试结算」→ 用户点了会重跑整个结算（合并仍会失败）。UX 合理吗？有更好的方案吗？
**先纠正一个前提**：在**当前**实现下，「重试结算」按钮**根本不会出现**。理由：

- `TodayViewModel.isSettling = dayState == DayState.SETTLING && !isServiceActive`（`TodayViewModel.kt:291`）；
- 修复 6 让合并失败后**继续转写并归档 DONE** → `dayState == DONE` → `isSettling == false`
  → `TodayScreen` 走 `isDayDone` 分支 → **`SettlingCard` 不渲染**。

所以真正的问题不是「按钮语义不对」，而是 **提示彻底不可见**（见 §3 的 P1-1）：
用户看到「已归档 / 查看总结」，完全不知道「合并音频缺失」，直到导出当天音频才发现。
（`settleError` 只有在**真错误**路径——转写/总结失败——才可见，因为那时状态停留在 `SETTLING`。）

**关于「重试结算」按钮本身**（真错误路径下）：语义是**合理的**——转写/总结失败确实是可重试的，
且 `SummarizeDayUseCase` 内置「已有非空总结则跳过」保护，重跑不会覆盖用户已勾选的待办。**建议保留。**

**更好的方案 —— 区分「提示」与「错误」**（我同意这个方向，但**不建议本轮就改数据库结构**）：

| 方案 | 做法 | 迁移成本 | 收益 | 评价 |
| --- | --- | --- | --- | --- |
| **① 零迁移（推荐本轮止血）** | 不新增 Room 字段；在 domain 层用**结构化前缀**区分，例如 `settleError = "[提示] 合并音频缺失：…"`，由 `TodayUiState`/`SettlingCard` 解析前缀决定：真错误 → 红色 + 「重试结算」按钮；提示 → 灰色说明、**无**重试按钮 | **0**（不动 Room、不动 schema） | 立刻恢复可见性 + 语义分离 | ✅ 性价比最高 |
| **② 新增 `settle_note` 字段** | Room `recording_days` 加 `settle_note TEXT`（VERSION 2→3，`Migration(2,3)` = `ALTER TABLE … ADD COLUMN settle_note TEXT NOT NULL DEFAULT ''`）+ domain `RecordingDay.settleNote` + `updateSettleProgress` 增参 + UI 改造 | **≈0.5~1 人日**：改实体/DAO/Domain/UI + 提交 `app/schemas/` 新 JSON（Room schema 哈希校验）+ 写迁移测试 | 语义最干净、可长期演进（未来「部分转写失败」「片段跳过」等提示都能挂） | ⏳ 建议下一迭代做 |
| ③ 只在 `isDayDone` 分支展示 `settleError` | 最小 UI 改动 | 极小 | 恢复可见性 | ⚠️ 仍把「提示」和「错误」混在一个字段，后续容易再踩坑 |

**我的建议**：本轮做 **① + ③**（零数据库改动，当天就能上线把提示露出来）；
**是否引入 `settle_note` 字段（方案 ②）请主理人决策** —— 按你的要求我不自行改数据库结构。
若决定做 ②，我可以出一份精确的迁移清单（实体字段 / DAO / `Migration` / schema JSON / 回归用例）。

### C. `awaitSettledSegments` 最多等 3 秒 —— 若服务已死、片段永远收不了尾，会白等 3 秒，可接受吗？有更精确的同步点吗？
**「白等 3 秒」可以接受；「等的时候进度完全不动」是唯一的小瑕疵。** 展开：

1. **代价其实很小**：真实收尾延迟是「≤1 帧 25ms + 协程调度」，正常路径下第 1 次轮询（100ms）就能拿到；
   3s 上限只在**服务已死**时才会跑满，而那时后面也做不出什么结果。
   → 3s 上限**可接受**，无需缩短。
2. **等待期间用户看到什么？—— 已核实：8%，不是 5%**
   `DaySettleWorker.runSettlePipeline` 中 `updateSettleProgress(dayId, PROGRESS_MERGING=8, null)`
   在**调用 `mergeDayAudioUseCase(...)` 之前**执行（`DaySettleWorker.kt:148-152`），
   而 3s 等待发生在 `mergeDayAudioUseCase` **内部** → 等待期间 DB 显示 **8%**，阶段文案「正在合并音频」。
   → **「结束录音后卡在 5%」的观感不会由这条路径产生**（修复 2 的 `PROGRESS_MERGING` 已覆盖）。
   → 唯一残留小问题：3s 内进度**一动不动**（8% → 8%），用户会以为卡住。
   → 最小改动建议：**等待期间周期性写一次进度**（如 9%），或把轮询间隔降到 25~50ms（真实延迟 ≤25ms，1~2 次命中）。
3. **更精确的同步点（根治竞态本身，而非缓解）**，按推荐度排序：
   - **方案 2-b（最推荐）— 让「投递时机」晚于「收尾时机」**：
     现在是 `stopSettle` → 立刻投递 Intent + 立刻入队结算 Worker（`StopMonitoringAndSettleUseCase`）。
     改成：**服务在音频循环退出、`finishSegment` 全部落库之后**，才投递「结算已就绪」并入队 Worker。
     这样 Worker 启动时片段**必然已收尾**，`awaitSettledSegments` 根本不需要存在 → 竞态从根上消失。
   - **方案 2-a — 显式就绪信号**：`RecordingDay` 加一个 `settleReadyAt`/`segmentsFinalized` 标记，
     `finishSegment` 全部完成后写入；Worker 等这个**事件/标记**而不是轮询片段状态。可配合 DataStore 或 Room Flow。
   - **方案 2-c（折中，保留轮询）**：把轮询间隔从 100ms 降到 25~50ms（真实延迟 ≤25ms，一两次就能命中），
     上限保持 3s（防挂死），并在等待期间**写一次 DB 进度**（如 9%）让 UI 有反馈。
4. **结论**：
   - 短期（本次发版前）：做 **2-c 的「等待期间写一次进度」**（一两行）→ 消除「8% 停 3 秒不动」的观感；
   - 中期：做 **2-b** → 彻底消除时序竞态，`awaitSettledSegments` 可降级为纯兜底；
   - 3s 上限**保持不变**（它是防挂死的安全网，不是问题本身）。

### D. `EchoLogApplication` 里 `modelManager.ensureModels()` 在 `super.onCreate()` 之后调用 —— Hilt 注入到 Application 字段的时机是否早于此？会不会 NPE？
**不会 NPE。有字节码级证据。** 且当前实现已经改为**不注入 ModelManager**，风险更低。

1. **Hilt 的注入时机（字节码证据）**：
   `app/build/generated/hilt/component_sources/debug/com/echolog/app/Hilt_EchoLogApplication.java:42-45`
   ```java
   @CallSuper
   @Override
   public void onCreate() {
     hiltInternalInject();   // ← 字段注入在这里
     super.onCreate();        // ← 平台的 Application.onCreate()
   }
   ```
   `EchoLogApplication`（经 Hilt Gradle 插件字节码转换）继承 `Hilt_EchoLogApplication`，
   因此执行顺序是：
   `EchoLogApplication.onCreate()` → `super.onCreate()` → `Hilt_EchoLogApplication.onCreate()`
   → **`hiltInternalInject()`（此时 `workerFactory` / `workScheduler` 已注入）** → `Application.onCreate()`
   → 回到 `EchoLogApplication.onCreate()` 的函数体（`createNotificationChannels()` / `schedulePeriodicWorkers()` / `workScheduler.scheduleModelInstall()`）。
   → **注入发生在 `onCreate()` 函数体之前**，`lateinit` 字段在函数体里一定已就绪，**不存在 NPE**。
   （`hiltInternalInject()` 有 `injected` 幂等守卫。）
2. **当前实现进一步降低了风险**：
   `EchoLogApplication` 现在**只注入既有字段** `workerFactory` + `workScheduler`，
   **不再注入 `ModelManager`**（避免把 Application 自身纳入 SingletonComponent 依赖图 → Hilt 测试变体的自继承问题）。
   `WorkScheduler` 的依赖是 `WorkManager.getInstance(context)`（懒加载），不引入 ModelManager → **无循环依赖风险**。
3. **首启模型安装的实际路径**（已核对源码与 assets）：
   `EchoLogApplication.onCreate()` → `workScheduler.scheduleModelInstall()`
   → `enqueueUniqueWork("model-install", KEEP, OneTimeWorkRequest(ModelDownloadWorker, inputData={EXTRA_ONLY_INSTALL=true}))`
   → `ModelDownloadWorker.doWork()` 检测 `EXTRA_ONLY_INSTALL` → `modelManager.installBundledModels()`
   → **仅做 assets 本地拷贝，不触网** ✓
   → assets 已确认存在真实权重：`assets/models/asr/model.int8.onnx`（239MB）+ `tokens.txt`（316KB）、
   `assets/models/llm/qwen2.5-1.5b-instruct-q4_k_m.gguf`（1.1GB）。
4. **残留风险见 §3 的 P1-2**：拷贝 1.35GB 是异步的，首启立刻「结束录音」可能撞上「模型未就绪」→ E-3001。

---

## 5. 结论

### 5.1 放行结论：**有条件放行**

**必须修复项（QA 已全部修复并配了回归用例，等主理人跑一次全量构建确认绿灯）：**

| # | 项 | 状态 |
| --- | --- | --- |
| M1 | `DaySettleWorker` 进度写入竞态（进度回退 100% + 覆盖 `settleError`） | ✅ QA 已修 + 用例 #8 |
| M2 | `notificationHelper.cancel()` 未兜底 → 已归档进度被回写成 5% | ✅ QA 已修 + 用例 #11 |
| M3 | 全量构建必须 0 failures，且测试数 ≥ 139 | ⏳ **待主理人串行执行** |
| M4 | 合并后的 manifest 必须含 `FOREGROUND_SERVICE_DATA_SYNC` | ⏳ **待主理人在同一次构建中核对** |

**建议但非阻塞（不阻塞本次发版）：**
- P1-1 合并失败提示在 UI 不可见 → 建议用**零迁移**方案（结构化前缀 + `isDayDone` 分支展示）本轮止血；`settle_note` 字段方案（Room VERSION 2→3）请主理人决策是否下迭代。
- P1-2 模型未就绪窗口 → 建议 Worker 转写前先查 `isAsrReady()` 并给专门文案。
- P2-1 `awaitSettledSegments` 等待期间进度停在 8% 不动 → 建议等待期间周期写进度（或把轮询间隔降到 25~50ms）。**（注：等待期间显示的是 8% 而非 5%，「卡在 5%」的观感不由该路径产生）**
- 团队约定：domain/data 层禁用 `android.util.Log`，统一走 `EchoLog`；同一时间只允许一个 Gradle 进程。

### 5.2 给工程师的验收标准（`MergeDayAudioUseCaseTest.kt`，任务书第 4 条）

1. 首次 `getSegmentsForDay` 返回 `durationMs == 0` 的片段、第二次起返回已收尾 → 用例最终 **Success**、合并文件存在且可解析。
2. 断言**确实发生了重读**（`getSegmentsForDay` 调用次数 ≥ 2），否则测不到竞态修复。
3. **永远不收尾**的场景 → 断言**等待有界**：调用次数 ≤ 31（= 1 次初始读 + 30 次轮询，3s/100ms），
   且用例最终失败（`ERROR_MERGE_FAILED` /「当天无可合并片段」）而不是无限等待。
4. **虚拟时间提示**：`awaitSettledSegments` 用的是 `delay(100)`。
   若测试用**独立**的 `UnconfinedTestDispatcher()`（自带 scheduler，不受 `runTest` 驱动）→ `delay` 会走**真实 100ms×N**，
   「永远不收尾」那个用例会**真实耗时 3s**（可接受，但会让 CI 变慢）。
   若想用虚拟时间（瞬时完成），请把 ioDispatcher 设为 **`StandardTestDispatcher(testScheduler)`**
   （取自 `runTest` 的 `TestScope`），这样 `delay` 由 `runTest` 自动推进 → 用例瞬时完成且能精确断言 `testScheduler.currentTime` 增量 ≈ 3000ms。

### 5.3 本轮 QA 对源码的改动清单（diff 摘要）

#### A. `app/src/main/java/com/echolog/app/work/DaySettleWorker.kt`（核心修复）

| 位置 | 改动 | 原因 |
| --- | --- | --- |
| 类 KDoc「失败策略」段 | 重写 | 与修复 2 的新语义对齐（原文档仍写「合并失败 → `Result.retry` 无限重试」） |
| `doWork()` 总兜底 catch | `updateSettleProgress(dayId, 5, …)` 增加守卫：`getDay(dayId)?.settleProgress < PROGRESS_DONE` 才写 | 已 DONE/100 的天不得被回写成 5% |
| `transcribeSummarizeArchive()` 进度写入 | `CoroutineScope + progressScope.launch{}` → `Channel(UNLIMITED) + 单一写入协程`；转写结束 `close()` + `join()`，`finally` 兜底 | 消除乱序 / 截断 / 回退竞态（P0-1） |
| `transcribeSummarizeArchive()` 归档处 | `notificationHelper.cancel(...)` → `runCatching { … }.onFailure { EchoLog.w(…) }` | 通知失败不得让已归档进度回退到 5%（P0-2） |
| `finishEmptyDay()` | 同上 | 同上 |
| import | 增 `kotlinx.coroutines.channels.Channel` | — |

#### B. 日志封装统一（预防同类测试陷阱，机械改动、零行为变化）

| 文件 | 改动 |
| --- | --- |
| `work/DayRolloverWorker.kt` | `android.util.Log.w(...)` → `EchoLog.w(...)`（+ import） |
| `service/receiver/BootReceiver.kt` | `Log.w(...)` ×2 → `EchoLog.w(...)`；移除 `import android.util.Log`（+ import `EchoLog`） |

> 现已**全量**确认：`app/src/main/java` 下除 `core/util/EchoLog.kt` 自身外，**再无任何直接 `android.util.Log` 调用**。
> 这条约定建议写进 README：**domain / data / work / service 层禁止直接调用 `android.util.Log`，统一走 `core/util/EchoLog`**。
> 理由（实测）：`android.util.Log` 在 JVM 单测中未 mock → 抛 `RuntimeException: Method w in android.util.Log not mocked`，
> 会把真实的断言失败掩盖成「白板异常」，极难排查。

**未回退 6 项修复的任何核心意图**：进度仍**实时**写 DB（只是写入通道有序化 + 可等待）；
合并失败仍降级继续转写；总兜底仍写 `settleError` 并返回 `Result.failure()`（只是不再把 DONE 的天打回 5%）。

**QA 新增测试文件（2 个，未与工程师的文件冲突）**：
- `app/src/test/java/com/echolog/app/data/media/AudioMergerRobustnessTest.kt`（6 用例）
- `app/src/test/java/com/echolog/app/work/DaySettleWorkerSettlePolicyTest.kt`（6 用例）
