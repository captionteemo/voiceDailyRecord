# 团队约定：排查「用户无感知」的失败

> 来源：BUG-11 真机 Bug（「结束录音后进度卡 5%，无法翻译成文字」）
> 约定人：齐活林（主理人） · 2026-10-25

本项目已出现过**多次**「用户看不到任何提示、进度永久卡住」的缺陷，且多次是
**同一失败模式**的不同变体。固化为团队约定，避免重复踩坑。

---

## 铁律 1：确定性失败，绝不 `Result.retry()`

`WorkManager` 的 `Result.retry()` **没有次数上限**，配合 `EXPONENTIAL` 退避（30s 起）
意味着：**任何确定性失败都会被无限重试**。

判定表：

| 失败性质 | 例子 | 正确处理 |
|---|---|---|
| **确定性**（重试也不会变） | 采样率历史不一致、片段损坏、模型文件缺失、当天无语音内容 | 降级处理：跳过该阶段 → 继续后续 → 归档 DONE/100 → 写入**提示**（`SETTLE_NOTE_PREFIX`） |
| **瞬时性**（重试可能变好） | 网络抖动、IO 暂时失败、模型尚未下载完 | `Result.retry()` ✅ |

**本项目已发生的两次「永久卡住」**：
- 卡 **5%**：合并失败（采样率混用）→ `updateSettleProgress(5, msg)` + `Result.retry()`
- 卡 **95%**：当天无转写内容 → `SummarizeDayUseCase` 失败 → `Result.retry()`

两者都是「确定性失败 + 无限重试」。

**新增错误码时必须自问**：这个错误码对应的场景，重试有意义吗？
若没有 → **必须**新增一个「终态」错误码，并在调用方识别为「跳过 + 归档」。

---

## 铁律 2：区分「提示」与「错误」

用户既要知道发生了什么，又不该被引导去做无效操作。

- **错误**（`settleError` 不带前缀）→ UI 展示「重试结算」按钮
- **提示**（`settleError` 以 `Constants.SETTLE_NOTE_PREFIX = "提示："` 开头）→
  只展示说明，**不展示重试按钮**（重试对确定性失败无效）

判定靠**前缀**，不靠错误码：
```kotlin
val isSettleNote: Boolean
    get() = settleError?.startsWith(Constants.SETTLE_NOTE_PREFIX) == true
```
好处：新增提示文案**零成本**自动被 UI 正确归类。

---

## 铁律 3：优先用零迁移方案，不动 Room schema

`settle_note` 字段会引入 `Room VERSION 2→3` 迁移 → 真机升级迁移风险。
用**前缀 + 现有 `settleError` 字段**承载「提示」语义，零迁移。

**判据**：新增信息若能被编码进已有字段（加前缀/加后缀），就不要加列。

---

## 铁律 4：进度写入必须「单调 + 有序 + 不回退」

`settleProgress` 是 UI 的唯一真相源。规则：

1. **单调**：任何时刻 `settleProgress` 不得回退（100% → 95% 是 bug）；
2. **有序**：并发写必须 FIFO（单写入协程 + 无界通道）；
3. **不丢**：`cancel()` 不会等待在途写入完成 → 必须 `close()` + `join()`；
4. **不回写已归档**：总兜底写错误前必须判断 `current.settleProgress < PROGRESS_DONE`。

**反模式**：
```kotlin
// ❌ cancel 只发信号，不等在途写入
progressScope.cancel()
// ✅ 关闭通道 → 写入协程排空 → join 确保全部落库
progressChannel.close()
progressWriter.join()
```

---

## 铁律 5：撤通知 / 清理等「收尾动作」不得中断主链路

`notificationHelper.cancel()` 抛异常 → 冒泡到总兜底 → **已 DONE/100 的天被写回 5%**
——这正是用户报告的精确症状。

```kotlin
// ❌ 异常冒泡 → 总兜底把进度写回 5%
notificationHelper.cancel(NOTIFICATION_ID_PROGRESS)

// ✅
runCatching { notificationHelper.cancel(NOTIFICATION_ID_PROGRESS) }
    .onFailure { EchoLog.w(TAG, "撤下进度通知失败（不影响结算结果）", it) }
```

**判据**：主链路（进度、状态、数据）的成败**不能**被旁支动作（通知、导出、清理）决定。

---

## 铁律 6：`@HiltWorker` 必须移除 WorkManager 默认初始化器

漏掉这一条，`@HiltWorker` 会**静默失效**——没有编译错误、没有运行期告警，
Worker 直接 FAILED，`doWork()` 从未执行。

```xml
<provider android:name="androidx.startup.InitializationProvider"
          android:authorities="${applicationId}.androidx-startup"
          android:exported="false" tools:node="merge">
    <meta-data android:name="androidx.work.WorkManagerInitializer"
               android:value="androidx.startup" tools:node="remove" />
</provider>
```

**新增 `@HiltWorker` 时**：先确认 manifest 里这条 `tools:node="remove"` 还在。
（`docs/BUG11_ROOTCAUSE_FIX.md` 第二节有完整字节码证据链。）

---

## 铁律 7：跨产物跳转前，必须核对「时间基准」是否同一坐标系

本项目存在**两个都叫「时间」但坐标系不同**的量：

| 量 | 坐标系 | 产生位置 |
|---|---|---|
| `TranscriptSegment.startMs/endMs` | **全天绝对时间**（含片段之间的空闲间隔） | `TranscribeDayUseCase.kt:77`：`segment.startMs + line.startMs` |
| 合并 WAV 内的播放位置 | **片段时长累加**（首尾直拼，**无静音间隙**） | `AudioMerger.mergeSegments`（纯 PCM 直拼） |

例：片段 A 占 10:00–10:05，片段 B 占 14:00–14:03。
- B 的转写 `startMs` = 14:00 的绝对时间
- 但 B 的音频在合并文件里位于**第 5 分钟处**（A 的音频占了前 5 分钟）

→ 若直接拿绝对时间 `seekTo` 合并文件，会跳到第 14 分钟处（错误位置）。

**正确换算**：

```
mergedOffset = sumOf(该片段之前所有片段的 durationMs) + (transcript.startMs - segment.startMs)
```

其中 `withinSegmentOffset = transcript.startMs - segment.startMs` 是该句在片段内的偏移，
**回退到片段 WAV 播放时直接用它**（这时坐标系统一）。

**通则**：任何「点 X 跳到 Y」的交互，在写代码前必须回答——
**X 的时间戳与 Y 的播放位置是不是同一个坐标系？** 不一致就先写换算函数，
并把换算做成**可单测的纯函数**（覆盖「首个片段 / 中间有空闲间隔 / 找不到所属片段」
三种边界），而不是把偏移量计算塞在点击回调里。

---

## 铁律 8：跨文件/跨产物的功能，先确认「产物是否真的存在」

本项目有多个**可能缺失**的产物，任一缺失都必须有明确回退，不能静默失败：

| 产物 | 可能缺失的原因 | 回退策略 |
|---|---|---|
| 合并全天 WAV | 采样率混用 / 超 4GB 上限 / 合并失败 | 回退到**片段 WAV** 逐个播放 |
| ASR 模型 | 用户未下载 / 下载中断 | ASR not ready → 转写不可用，提示用户下载 |
| LLM 模型 | 同上 | 总结降级为规则总结（`RuleBasedSummarizer`） |

**判据**：路径非空 **不等于** 文件存在。判断可用产物必须用
`File(path).isFile`（或等价的存在性检查），不能只看 `path != null`。

---

## 设计决策记录：`force = true` 先删后建，失败不恢复旧总结（既定语义，勿当 bug 修）

**背景**：BUG-10 修复引入「已有非空总结则跳过」保护（防兜底扫描覆盖用户数据），
而转写全文页的「重新生成总结」是**用户显式重算**——语义是「旧结果作废，按当前
文字重算」。因此 `SummarizeDayUseCase(force = true)` 走「先 `deleteSummary`
清旧总结与旧待办，再生成」的路径。

**代价（有意取舍）**：若重算失败（如当天无转写内容 → E-4003），旧总结**已被删除、
不可恢复**。这是显式重算的既定语义，不是缺陷：

1. 旧待办可能已被用户勾选，若重算后悄悄恢复旧待办，勾选态与旧内容对不上，
   体感是「我勾的待办又回来了」——那才是 BUG-10 要修的病灶；
2. 失败原因经 [EchoResult] 返回值交 UI 呈现（`RegenerateSummaryUseCase` 同步把
   日级状态置 `FAILED`，状态与数据一致），用户可再次点击重试；
3. 兜底扫描（`SettlePendingDaysUseCase` / `DaySettleWorker`）走 `force = false`，
   **不受此语义影响**，仍受 BUG-10 保护。

**测试锁定点**：`SummarizeDayUseCaseTest.force=true 时当天无转写内容返回 E-4003
且旧总结已被清空`——若未来有人把该用例改成「失败后恢复旧总结」，先读本条。


---

## 排查方法论：遇到「无任何提示」怎么定位

### 步骤 1：立刻定位「那个数字是谁写的」

用户说「卡在 5%」→ 第一件事不是去看 5% 相关的代码，而是 **grep 谁写了 5%**。

> 本例：`5%` 由**入队方** `StopMonitoringAndSettleUseCase:42` 写，
> 而非 Worker 写 → 立刻排除「Worker 内部逻辑 bug」整个方向，
> 转向「Worker 根本没启动」。

### 步骤 2：怀疑「代码根本没执行」

用户说「**没有任何提示**」→ 优先假设**执行入口就没进去**，
而不是「进去了但没写日志」。

验证手段：日志里找 Worker 的第一条 `EchoLog.i`。本例 `DaySettleWorker` 开头就有
`EchoLog.i(TAG, "结算开始: dayId=$dayId")` —— 真机日志里**根本没有这一行**，
直接证明 `doWork()` 从未执行。

### 步骤 3：字节码验证依赖行为，不靠读文档

`javap -p -c` 反汇编 AAR 是唯一可信的「这个库到底怎么做的」的证据。
本例三段反汇编（`WorkManagerInitializer` / `WorkManagerImpl.getInstance` /
`WorkerFactory.fallbackToReflection`）一次性锁定根因，比读文档快且不会漏掉版本差异。

```bash
unzip -q work-runtime-2.10.0.aar -d /tmp/wr && cd /tmp/wr && unzip -q classes.jar -d cls
javap -p -c cls/androidx/work/WorkerFactory.class
```

### 步骤 4：排除「依赖装配」问题时要双向验证

本例先怀疑 Hilt 侧（读了生成的 `DaggerEchoLogApplication_HiltComponents_SingletonC.java`，
确认 5 个 Worker 全部注册、`HiltWorkerFactory` 绑定正确）→ **Hilt 侧无问题** →
问题必然在「WorkerFactory 有没有被交给 WorkManager」→ 于是去读 WorkManager 源码。

**双向排除**比单向猜测快得多。

### 步骤 5：所有修复都要在最终产物里验证

不只看源文件，要看**合并后的 manifest**、**最终 APK 内的字节**：

```bash
# 合并 manifest
grep -A12 "InitializationProvider" app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml

# APK 内二进制 manifest（字符串在 UTF-16 字符串池里）
python -c "
import zipfile,re
d=zipfile.ZipFile('app-debug.apk').read('AndroidManifest.xml')
print([t.decode('utf-16-le','ignore') for t in
      re.findall(rb'(?:[ -~]\x00){6,}', d) if 'WorkManagerInitializer' in t.decode('utf-16-le','ignore')])"
```

---

## 提交前自检清单

- [ ] 新增的错误码：**重试有意义吗？** 没意义 → 必须走「终态降级」分支
- [ ] 任何 `Result.retry()`：失败是确定性的吗？是 → 改掉
- [ ] 任何 `runCatching` 包裹的旁支动作：失败会冒泡到总兜底吗？不会 → 确认
- [ ] 任何写 `settleProgress` 的地方：会回退吗？会 → 加守卫
- [ ] 新增 `@HiltWorker`：manifest 的 `tools:node="remove"` 还在吗？
- [ ] 新增「提示」类文案：加 `SETTLE_NOTE_PREFIX` 前缀了吗？UI 会正确归类吗？
- [ ] 需要 Room 新列吗？**能编码进现有字段吗？**（优先零迁移）
- [ ] 跑过 `testDebugUnitTest` + `assembleDebug` 吗？APK 里的东西核验过吗？
