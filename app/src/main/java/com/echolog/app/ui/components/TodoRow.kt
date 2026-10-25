package com.echolog.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.echolog.app.R
import com.echolog.app.core.util.TimeUtils
import com.echolog.app.domain.model.TodoItem
import com.echolog.app.domain.model.TodoType

/**
 * 待办条目（架构 2.5，PRD 4.3，FR-105 简化版）：勾选 + 复制。
 *
 * @param item 待办/决策/约定条目（同一组件复用，勾选仅对 TODO 有意义）。
 * @param onToggleDone 勾选变更（仅 TODO 类型回调）。
 * @param onCopy 复制本条内容。
 */
@Composable
fun TodoRow(
    item: TodoItem,
    onToggleDone: (Boolean) -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.isTodo) {
            Checkbox(
                checked = item.done,
                onCheckedChange = onToggleDone,
            )
        }
        Text(
            text = item.content,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (item.done) TextDecoration.LineThrough else null,
            color = if (item.done) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = TimeUtils.formatTime(item.sourceMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.summary_copy),
            )
        }
    }
}

/** 是否待办类型（勾选框仅对待办展示，决策/约定不可勾选）。 */
private val TodoItem.isTodo: Boolean
    get() = type == TodoType.TODO
