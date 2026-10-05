package com.gouge.xbot.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gouge.xbot.domain.DirectionState
import com.gouge.xbot.widget.SignalIconMapping
import com.gouge.xbot.widget.SignalIconType
import com.gouge.xbot.widget.drawableResource
import com.gouge.xbot.widget.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalIconMappingSheet(
    mappings: List<SignalIconMapping>,
    onMappingsChange: (List<SignalIconMapping>) -> Unit,
    onDismiss: () -> Unit,
) {
    var editingMapping by remember { mutableStateOf<SignalIconMapping?>(null) }
    var isAdding by remember { mutableStateOf(false) }
    var deletingMapping by remember { mutableStateOf<SignalIconMapping?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("信号图标映射", style = MaterialTheme.typography.headlineSmall)
            Text(
                "信号名称精确匹配成功后，小组件会用对应图标族的多、空形态代替原名称。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { isAdding = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("添加映射")
            }
            if (mappings.isEmpty()) {
                Text(
                    "暂无映射，未匹配的信号将继续显示原名称。",
                    modifier = Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(mappings, key = { it.id }) { mapping ->
                        SignalIconMappingCard(
                            mapping = mapping,
                            onEnabledChange = { enabled ->
                                onMappingsChange(
                                    mappings.map {
                                        if (it.id == mapping.id) it.copy(enabled = enabled) else it
                                    },
                                )
                            },
                            onEdit = { editingMapping = mapping },
                            onDelete = { deletingMapping = mapping },
                        )
                    }
                }
            }
        }
    }

    if (isAdding || editingMapping != null) {
        SignalIconMappingEditorDialog(
            mapping = editingMapping,
            existingMappings = mappings,
            onDismiss = {
                isAdding = false
                editingMapping = null
            },
            onSave = { savedMapping ->
                val updatedMappings = if (editingMapping == null) {
                    mappings + savedMapping
                } else {
                    mappings.map {
                        if (it.id == savedMapping.id) savedMapping else it
                    }
                }
                onMappingsChange(updatedMappings)
                isAdding = false
                editingMapping = null
            },
        )
    }

    deletingMapping?.let { mapping ->
        AlertDialog(
            onDismissRequest = { deletingMapping = null },
            title = { Text("删除映射？") },
            text = { Text("删除 ${mapping.signalName} 后，未匹配时将恢复显示原信号名称。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onMappingsChange(mappings.filterNot { it.id == mapping.id })
                        deletingMapping = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingMapping = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun SignalIconMappingCard(
    mapping: SignalIconMapping,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DirectionIconPair(
                iconType = mapping.iconType,
                iconSize = 36.dp,
                showLabels = true,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(mapping.signalName, style = MaterialTheme.typography.titleMedium)
                Text(
                    mapping.iconType.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onEdit) { Text("编辑") }
                    TextButton(onClick = onDelete) { Text("删除") }
                }
            }
            Switch(
                checked = mapping.enabled,
                onCheckedChange = onEnabledChange,
            )
        }
    }
}

@Composable
private fun SignalIconMappingEditorDialog(
    mapping: SignalIconMapping?,
    existingMappings: List<SignalIconMapping>,
    onDismiss: () -> Unit,
    onSave: (SignalIconMapping) -> Unit,
) {
    var signalName by remember(mapping?.id) { mutableStateOf(mapping?.signalName.orEmpty()) }
    var iconType by remember(mapping?.id) {
        mutableStateOf(mapping?.iconType ?: SignalIconType.MaTrend)
    }
    val trimmedName = signalName.trim()
    val isDuplicate = existingMappings.any {
        it.id != mapping?.id && it.signalName.trim().equals(trimmedName, ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (mapping == null) "添加映射" else "编辑映射") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = signalName,
                    onValueChange = { signalName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("信号名称") },
                    placeholder = { Text("例如 MA-TREND") },
                    supportingText = {
                        if (isDuplicate) {
                            Text("该信号名称已经存在")
                        } else {
                            Text("忽略大小写，按完整名称匹配")
                        }
                    },
                    isError = isDuplicate,
                    singleLine = true,
                )
                Text("选择图标", style = MaterialTheme.typography.titleSmall)
                SignalIconType.entries.forEach { option ->
                    FilterChip(
                        selected = iconType == option,
                        onClick = { iconType = option },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                DirectionIconPair(
                                    iconType = option,
                                    iconSize = 30.dp,
                                    showLabels = false,
                                )
                                Text(option.label)
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        mapping?.copy(signalName = trimmedName, iconType = iconType)
                            ?: SignalIconMapping.create(trimmedName, iconType),
                    )
                },
                enabled = trimmedName.isNotEmpty() && !isDuplicate,
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun DirectionIconPair(
    iconType: SignalIconType,
    iconSize: Dp,
    showLabels: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DirectionIconPreview(
            iconType = iconType,
            direction = DirectionState.LongOnly,
            label = "多",
            iconSize = iconSize,
            showLabel = showLabels,
        )
        DirectionIconPreview(
            iconType = iconType,
            direction = DirectionState.ShortOnly,
            label = "空",
            iconSize = iconSize,
            showLabel = showLabels,
        )
    }
}

@Composable
private fun DirectionIconPreview(
    iconType: SignalIconType,
    direction: DirectionState,
    label: String,
    iconSize: Dp,
    showLabel: Boolean,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(iconType.drawableResource(direction)),
            contentDescription = "${iconType.label}·${direction.label}",
            modifier = Modifier.size(iconSize),
        )
        if (showLabel) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
