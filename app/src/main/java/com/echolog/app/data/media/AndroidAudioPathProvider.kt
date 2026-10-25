package com.echolog.app.data.media

import android.content.Context
import com.echolog.app.core.util.FileUtils
import com.echolog.app.domain.usecase.AudioPathProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AudioPathProvider] 的 Android 实现（T04 接缝落地）：经 [FileUtils] 生成规范路径。
 */
@Singleton
class AndroidAudioPathProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : AudioPathProvider {

    override fun mergedDayPath(dayId: String): String = FileUtils.mergedDayPath(context, dayId)

    override fun segmentPath(dayId: String, ordinal: Int): String =
        FileUtils.segmentPath(context, dayId, ordinal)
}
