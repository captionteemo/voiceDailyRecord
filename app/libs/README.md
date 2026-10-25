# app/libs 二进制制品说明

本目录存放无法从 Maven 仓库直接获取的二进制制品（已由 `.gitignore` 忽略，不入库）。
放置后由 `app/build.gradle.kts` 中的
`implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))`
自动引入，无需修改构建脚本。

## 1. sherpa-onnx Android AAR（端侧 ASR 引擎，T04 使用）

- 官方文档：<https://k2-fsa.github.io/sherpa/onnx/android/>
- 发布页：<https://github.com/k2-fsa/sherpa-onnx/releases>
- 版本要求：>= v1.10.x（与架构文档第 6 章一致）
- 下载 Release 中的 Android 预编译 AAR（资产名形如 `sherpa-onnx-<version>-android.aar`，以发布页实际资产名为准）
- 放置为：`app/libs/sherpa-onnx.aar`
- 说明：AAR 内置 arm64-v8a / armeabi-v7a 等 ABI 的 JNI 库，无需额外 NDK 编译；
  如需收窄包体，可只保留 arm64-v8a（架构决策 2 同 llama.cpp 策略）。

## 2. llama.cpp 自编译 arm64-v8a（端侧 LLM 引擎，T04 使用）

架构决策：仅支持 arm64-v8a（Qwen2.5-1.5B 对 32 位设备不现实）。

编译要点（需 NDK r26+，Linux/macOS 执行）：

```bash
git clone https://github.com/ggml-org/llama.cpp && cd llama.cpp

cmake -B build-android \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-29 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=ON \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF

cmake --build build-android -j8
```

- 产物：`build-android/src/libllama.so`（不同版本路径可能为 `build-android/libllama.so`）
- 放置为：`app/src/main/jniLibs/arm64-v8a/libllama.so`
- 注意：T04 还需一层薄 JNI 封装（`libllama_jni.so`，Kotlin `external fun` + `System.loadLibrary("llama_jni")`），
  该封装由 T04 以 CMake `externalNativeBuild` 或预编译方式补齐。
