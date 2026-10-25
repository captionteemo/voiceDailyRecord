# EchoLog（回声日志）

> Android 全天候声音记录 + AI 智能总结应用——音频版 Looki L1。
> 手动开启后台监听，安静时不录音，声音突变自动开录；晚上一键结束，全天录音自动合并、端侧转写、AI 总结（摘要 + 时间线 + 待办提取）。

**仓库**：[`captionteemo/voiceDailyRecord`](https://github.com/captionteemo/voiceDailyRecord)

| 项 | 值 |
|---|---|
| 平台 | Android 10+（minSdk 29 / targetSdk 35），仅 arm64-v8a 推理 |
| 技术栈 | Kotlin 2.0.21 · MVVM + Clean · Compose (Material3) · Hilt · Room · WorkManager |
| 单元测试 | 166 个用例（21 个测试类），0 失败 |
| APK 体积 | 约 317MB（**不含模型权重**——权重由用户首次使用自行下载） |
| 模型 | SenseVoiceSmall int8（ASR）+ Qwen2.5-1.5B-Instruct Q4_K_M（LLM），均跑在端侧 |

## 功能概览（MVP）

| 模块 | 说明 |
|------|------|
| 后台监听 | 前台服务（microphone 类型）+ 能量自适应 VAD，静音 5 秒自动断段，单段不设上限 |
| 手动标记 | 通知栏 RemoteInput / App 内一键打点 + 文字备注 |
| 当天结算 | 手动「结束」→ 当天片段合并为一整段 WAV（保留分段时间线）→ 端侧转写 → AI 总结 |
| 跨天兜底 | 忘记结束时，DayRolloverWorker 按自然日自动拆分归档 |
| 充电兜底 | 结束时的转写未完成，充电 + 电量 ≥50% 时 TranscribeWorker 后台续跑 |
| AI 能力 | 混合模式：端侧默认（SenseVoiceSmall ASR + Qwen2.5-1.5B-Instruct 总结），云端增强为 P1 预留 |
| 存储 | 纯本地（Room + 文件系统），无账号、无云同步 |
| 隐私合规 | 首次启动隐私告知、常驻「正在录音」通知、隐私模式开关 |
| 转写全文 | 独立页展示当天全部转写文字；点文字跳到对应音频位置播放、就地修改识别错误、一键复制全部或选段、页面内重新生成总结 |

## 功能新增（Task 6：录音治理与可控性）

> 在 MVP「能用」基础上补齐日常可用性：可辨识的文件命名、可一键直达的目录、可随时暂停的后台监听、以及可在 App 内调节的录音参数。

| 功能 | 说明 | 关键实现 |
|------|------|---------|
| ① 文件命名与同名翻译 | 每次录音按「生成日期 + 第几次」命名：`yyyy-MM-dd_第N次.wav`；转写结果生成同名 `.md`（如 `2026-10-25_第3次.md`），音频与译文一一对应；App 内「打开所在目录」一键跳转到该录音文件目录 | `RecordingNamer`（基名/序号解析）、`TranscriptExporter`/`SegmentTranscriptExporter`（写同名 md）、`FolderLauncher`（SAF `DocumentFile` 跳转） |
| ② 通知栏暂停 / 继续 | 后台监听常驻通知上提供暂停 / 继续按钮，运行中可随时点按；暂停时服务保持前台、音频采集循环停止、心跳维持，恢复后从下一段重新开录（不丢已录片段） | `NotificationActionReceiver`（PAUSE/RESUME 广播）、`RecordingService`（处理暂停态）、`RecordingServiceState.PAUSED` 枚举 |
| ③ App 内录音设置 | 设置页可调三项：**灵敏度**（低 / 中 / 高，低灵敏度需更响声音才开录 → 更高 dB 门限）、**采样率**（非 16k 经重采样再送 ASR）、**存储位置**（系统文件选择器自选目录，SAF 复制落盘） | `SettingsScreen`/`SettingsViewModel`、`RecordingSettings`（`SensitivityLevel.LOW(14)/MEDIUM(10)/HIGH(6)`）、`AudioResampler`、`StorageLocationManager` |

> 灵敏度语义（用户需求 3）：`门限 = 噪声底 + 余量`，余量 低14 / 中10 / 高6 dB。即「低」档门限最高、最不容易误触发，需更大的声音才开录；「高」档门限最低、最灵敏。
> 采样率语义：录音可按设备支持任意采样率采集，非 16k 的 PCM 由 `AudioResampler` 重采样到 16k 再喂给 SenseVoice ASR，保证识别质量。
> 存储位置语义：默认仍落 App 私有目录；用户可在设置里用系统 SAF 目录选择器指定任意可读写目录，后续录音与译文经 `StorageLocationManager` 复制到该目录。

## 模型来源：用户自行下载（权重不入库、不进 APK）

> **权重不随代码分发。** 早期版本把 1.35GB 权重打进 APK（APK ≈1.6GB），导致无法发布到代码托管平台（GitHub 单文件硬限制 100MB），也拖慢安装与更新。现改为**用户首次使用从 ModelScope 自行下载**：APK 降到 ≈317MB，安装包只含代码与原生库，权重落到应用私有目录。

| 项 | 说明 |
|----|------|
| 下载入口 | 首启自动进入「模型下载」引导页；也可在设置/首页提示卡手动触发。Wi-Fi 下直接前台下载（带进度条），计量网络下入队 `ModelDownloadWorker`（`NetworkType.UNMETERED`）后台续传 |
| 下载源 | ASR：ModelScope `poloniumrock/SenseVoiceSmallOnnx`（`model.int8.onnx` + 伴随文件 `tokens.txt`）；LLM：ModelScope `qwen/Qwen2.5-1.5B-Instruct-GGUF`（`qwen2.5-1.5b-instruct-q4_k_m.gguf`） |
| 落盘位置 | 应用私有目录 `{filesDir}/models/{asr,llm}/`，不写外部存储、无需权限 |
| 完整性保障 | ① `ModelDownloader` 先写 `.part` 临时文件，**校验体积达标后原子 rename** —— 正式路径上要么完整要么不存在；② `ModelEntry.minSizeBytes` = 真实字节数 × 90% 作为就绪门槛，任何截断的半成品都无法通过判定 |
| 断点续传 | `.part` 文件保留已下载字节，进程被杀/网络中断后重新触发会从断点继续，不从头再来 |
| 体积 | ASR ~229MB + tokens ~309KB；LLM ~1.04GB。首次下载需预留约 1.4GB 空闲空间 |

> **为什么保留 90% 门槛**：若门槛过低（如 1MB），下载中断留下的半成品文件也能骗过「模型已就绪」判定，导致推理引擎加载失败后永不重试——用户表现为「永久无法翻译成文字」（BUG-11）。90% 门槛可拦住任何截断，同时容忍极小的元数据差异。此为 BUG-11 修复成果，**不可回退**。
>
> **下载期间**：录音、转写等核心功能不受影响，未就绪时总结能力降级提示；模型就绪后自动恢复。

## 功能新增（Task 8：模式选择 / 清理缓存 / 内容导出）

> 三项需求一并落地：录音方式从「单一自动断段」扩展为 4 种可选模式；补齐清理缓存的三键操作；支持把指定日期的全天录音转写导出为一份带日期的 Markdown 文档。

### ① 四种录音模式

| 模式 | 说明 | 关键实现 |
|------|------|---------|
| 全天录音 | 开启后持续录一整天，不依赖 VAD 断段；跨零点自动换天并给旧天出结果 | `RecordingMode.ALL_DAY`、`RecordingService.rolloverToNewDayIfNeeded()`（10s 节流，旧天入队结算 + 新一天建档续录） |
| 自动录音 | 按声音突变自动开录、静音断段；静音判定可配置（灵敏度余量 + 断段时长 3/5/10s） | `RecordingMode.AUTO_VAD`、`EnergyVadEngine`、`SensitivityLevel.LOW(14)/MEDIUM(10)/HIGH(6)` |
| 模板录音 | 导入课表式时间模板（从几点几分到几点几分录、停、再录），按区间自动启停 | `RecordingMode.SCHEDULE_TEMPLATE`、`ScheduleTemplate` + `ScheduleTemplateCodec` + `ScheduleTemplateStore`（独立 DataStore） |
| 手动录音 | 通过状态栏或 App 的录音按键手动控制，中间点击的部分才录 | `RecordingMode.MANUAL`、`Constants.ACTION_MANUAL_TOGGLE`、通知栏与 App 按钮共享 `manualRecordingOn` 标志 |

模式在设置页选择，落库到 `recording_day.mode`（Room v1→v2 迁移），当天以建档时的模式为准。

### ② 清理缓存（三键）

| 操作 | 删除 | 保留 |
|------|------|------|
| 删除所有录音原文件 | 全部片段 `.wav` + 全天合并 `.wav` | 转写文字与总结内容 |
| 清除某天转化后产物 | 该天合并 `.wav`、片段同名转写 `.md`、DB 转写分段（片段转写态重置为 PENDING 可重转写） | 录音原文件与总结内容 |
| 清除所有总结内容 | 全部天的总结 / 待办 / 关键时间线 | 录音与转写文字 |

实现：`CleanupCacheUseCase` + `RecordingRepository.deleteAllOriginalAudio()` / `clearDayConvertedArtifacts()` / `SummaryRepository.deleteAllSummaries()`；UI 见设置页 `CleanupSection`。

### ③ 按日期导出转写文档

一键把指定日期「录音转文字」聚合成带日期的 Markdown：`{dayId}_全天转写.md`，内含当天总结、打点标记、以及按时间轴排列的全部分片转写。

实现：`DayTranscriptMarkdown`（纯函数构建）+ `DayTranscriptFileExporter`（落盘）+ `ExportDayTranscriptUseCase`。

### 全天模式的工程约束（重要）

「一整天都在录音」有三条硬约束，本轮已全部处理：

1. **WAV 4GB 格式上限**：RIFF 长度字段是无符号 32 位，原实现按 Java `Int`（有符号 2GB）钳制会让大文件头溢出成负数。已改为按无符号语义写入（`WavIo.putUint32LE`），并在 `AudioMerger` 合并前预检总字节，超限抛 `MergeOversizeException` → 结算链路降级为「跳过合并、继续转写与总结」（E-5002，非重试软失败）。
2. **ASR 内存护栏**：`WavIo.readFloatSamples` 整段读入内存，全天单文件必 OOM。已加样本数上限（8M ≈ 5 分钟 @16kHz），录音侧同步按 `Constants.maxSegmentDurationMs(sampleRate)` 折算片段上限——高采样率下 5 分钟会超出该护栏，必须按采样率缩短。
3. **跨零点归属**：全天录音跨天后，旧天收尾 → 置 `SETTLING` → 入队结算；新一天建档续录。刻意不复用 `StopMonitoringAndSettleUseCase`（它会清 `monitorActive` 与心跳，打断新一天保活）。

> 磁盘提示：全天模式的原始 PCM 体积为 16kHz ≈ 2.76GB/天、48kHz ≈ 8.3GB/天。建议全天模式使用 16kHz；事后可用「清理缓存 → 删除所有录音原文件」释放。

## 环境要求

- **JDK 17**（Temurin/Adoptium 均可）
- **Android SDK**：compileSdk 35 / build-tools 35.0.0 / platform-tools，minSdk 29（Android 10）
- **Gradle 8.9**（工程已带 wrapper；wrapper jar 如缺失，本地 Gradle 执行一次 `gradle wrapper --gradle-version 8.9` 即可再生）

## 克隆与构建

```bash
git clone https://github.com/captionteemo/voiceDailyRecord.git
cd voiceDailyRecord/echolog-app

# 1) 放置二进制制品（不入库，见 app/libs/README.md）
#    - app/libs/sherpa-onnx.aar          ← k2-fsa/sherpa-onnx Release v2.0.0
#    - app/src/main/jniLibs/arm64-v8a/*.so ← llama.cpp 自编译（NDK r26+，见 app/libs/README.md）

# 2) 配置 SDK
echo "sdk.dir=/path/to/your/android-sdk" > local.properties   # 或设置 ANDROID_HOME

# 3) 构建
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk  （≈317MB，不含模型权重）
```

> **模型权重无需下载即可构建**——权重不进 APK，由用户首次使用从 ModelScope 自行获取（见「模型来源：用户自行下载」章节）。
>
> 环境要求：**JDK 17** / **Android SDK 35**（compileSdk 35、build-tools 35.0.0、platform-tools）/ **minSdk 29**（Android 10）/ Gradle 8.9。

## 构建（本地工程）

```bash
# 方式一：Android Studio 直接打开 echolog-app/ 目录（推荐，自动处理 SDK 许可）
# 方式二：命令行
cd echolog-app
echo "sdk.dir=/path/to/your/android-sdk" > local.properties   # 或设置 ANDROID_HOME
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 本仓库 `tools/` 目录内置了一套已验证的工具链（JDK 17.0.20.1 + Gradle 8.9 + Android SDK 35），无本地环境时可直接复用：
> ```bash
> cd echolog-app
> export JAVA_HOME=../tools/jdk-17.0.20.1+1
> export ANDROID_HOME=../tools/android-sdk
> ../tools/gradle-8.9/bin/gradle assembleDebug
> ```

## 端侧模型（用户自行下载）

> 权重**不进 APK、不进版本库**，首次使用从 ModelScope 下载（详见上文「模型来源：用户自行下载」章节）。APK ≈317MB（不含权重），下载后的权重落到应用私有目录。

| 模型 | 用途 | 体积 |
|------|------|------|
| SenseVoiceSmall int8 + tokens.txt | 中文语音识别（sherpa-onnx 承载） | ~229MB + 309KB |
| Qwen2.5-1.5B-Instruct Q4_K_M GGUF | 端侧总结（llama.cpp JNI 承载） | ~1.04GB |

- `ModelManager.ensureModels()` 幂等：已就绪（体积门槛达标）直接跳过，不重复下载 1GB+ 权重；缺失/损坏走 `ModelDownloader`（`.part` 断点续传 + 原子 rename + 体积校验）
- 已知遗留项：`libllama_jni.so` 未随包提供（见「二进制制品说明」），QwenSummarizer 在未集成该 JNI 时会自动降级为 RuleBasedSummarizer（规则总结）；ASR（sherpa-onnx AAR）不受影响。该降级与「模型是否下载」无关，属端侧推理链另一环节，留给后续版本补齐。

## 二进制制品说明（app/libs/ 与 jniLibs/）

| 制品 | 来源 | 状态 |
|------|------|------|
| `app/libs/sherpa-onnx-2.0.0.aar` | k2-fsa/sherpa-onnx GitHub Release v2.0.0 | ✅ 已内置（含 onnxruntime + jni .so，arm64 + armeabi-v7a） |
| `app/src/main/jniLibs/arm64-v8a/libllama.so` 等 4 库 | ggml-org/llama.cpp Release b11852 | ✅ 已内置（libllama / libggml-base / libggml-cpu-armv8.0 / libllama-common） |
| `libllama_jni.so` | 需 NDK 自行编译（llama.cpp jni 目录） | ⚠️ 未提供，自动走规则总结降级 |

> **这些二进制制品不入库**（`.gitignore` 已排除 `app/libs/*.aar` 与 `app/src/main/jniLibs/**/*.so`）——单文件超 GitHub 100MB 硬限制。克隆后按 `app/libs/README.md` 的说明本地放置即可构建。

## 目录结构

```
echolog-app/
├── app/
│   ├── libs/                        # sherpa-onnx AAR
│   ├── src/main/
│   │   ├── java/com/echolog/app/
│   │   │   ├── ai/                  # ASR/LLM 引擎、模型管理（T04）
│   │   │   ├── core/                # 常量/错误码/工具（T01）
│   │   │   ├── data/                # Room/DataStore/仓库（T02）
│   │   │   ├── di/                  # Hilt 模块
│   │   │   ├── domain/              # 模型/用例（纯净 Kotlin）
│   │   │   ├── service/             # 录音服务/VAD/通知/广播（T03）
│   │   │   ├── ui/                  # Compose UI + ViewModel（T05）
│   │   │   └── work/                # 5 个 Worker + 调度器（T05）
│   │   ├── jniLibs/arm64-v8a/       # llama.cpp .so
│   │   └── res/                     # 字符串/主题/图标
│   └── schemas/                     # Room schema 导出
├── docs/                            # PRD / 架构 / QA 报告（仓库根目录）
└── tools/                           # 本地工具链（JDK/Gradle/SDK）
```

## 真机验证清单（建议）

- [ ] 中端机（6GB 内存）：全天监听 8 小时耗电 < 8%（NFR-001）
- [ ] 低端机（4GB 内存）：SenseVoice 转写速度、Qwen 总结耗时 < 10 分钟（NFR-004）
- [ ] 小米 / 华为 / OPPO 各一台：后台保活自恢复（NFR-003）
- [ ] 首启流：隐私告知 → 模型下载引导页（可跳过，权重需联网下载约 1.4GB）→ 三 Tab
- [ ] 完整一天：开启 → 说话触发 → 打点 → 结束 → 合并 → 转写 → 总结

## 相关文档

- 产品需求：`../docs/echolog-prd.md`
- 系统架构：`../docs/echolog-architecture.md`
- QA 报告（MVP 缺陷回归）：`../docs/echolog-qa-report.md`
- QA 报告（Round2 缺陷复跑）：`../docs/echolog-qa-report-round2.md`
- QA 报告（Task 6 三功能落地）：`../docs/echolog-qa-report-round3.md`
- QA 报告（Task 7 模型内置离线 APK）：`../docs/echolog-qa-report-round4.md`
- 需求新增说明（Task 8 模式选择 / 清理缓存 / 内容导出）：`../docs/echolog-feature-update-task8.md`
- **BUG-11 根因修复报告（真机「进度卡 5% / 无法翻译成文字」）：`docs/BUG11_ROOTCAUSE_FIX.md`**
- **BUG-11 修复说明（结算卡 5% / 无法翻译文字 + 权重改用户下载制）：`../docs/echolog-bug11-settle-stuck-fix.md`**
- **团队约定：排查「用户无感知」的失败（6 条铁律 + 排查方法论）：`docs/TEAM_CONVENTIONS_SILENT_FAILURES.md`**
- QA 报告（BUG-11 首轮）：`docs/QA_REPORT_BUG11.md`
