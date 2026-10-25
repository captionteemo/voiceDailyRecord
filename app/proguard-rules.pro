# ── llama.cpp JNI（T04）：保留 native 方法与被 JNI 调用的封装类 ──
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.echolog.app.ai.llm.** { *; }

# ── sherpa-onnx（AAR 自带 consumer rules，此处兜底） ──
-keep class com.k2fsa.sherpa.onnx.** { *; }

# ── Room 实体：保留字段名便于 schema 导出与排障 ──
-keep class com.echolog.app.data.local.entity.** { *; }

# ── Hilt / WorkManager ──
-keep class dagger.hilt.** { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# ── Kotlin 协程（内部类被反射引用） ──
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
