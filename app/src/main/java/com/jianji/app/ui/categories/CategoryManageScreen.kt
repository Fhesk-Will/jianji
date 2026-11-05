package com.jianji.app.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jianji.app.JianJiApp
import com.jianji.app.data.entity.CategoryEntity
import com.jianji.app.data.model.RecordType
import com.jianji.app.ui.components.EmojiBadge
import com.jianji.app.ui.components.SegTabs
import com.jianji.app.ui.theme.Danger
import com.jianji.app.ui.theme.Ink
import com.jianji.app.ui.theme.Sub

/** 内置 emoji 图标集（骨架先用 emoji，二期可换矢量图标） */
private val EMOJI_CHOICES = listOf(
    "🍜", "🍲", "☕", "🚇", "🚗", "✈️", "🛒", "🏠", "🎬", "🎮",
    "💊", "📚", "✨", "💰", "🏆", "🧾", "📈", "💼", "🧧", "🎁",
    "🐶", "👕", "💡", "📦",
)

@Composable
fun CategoryManageScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as JianJiApp
    val vm: CategoryViewModel = viewModel(initializer = { CategoryViewModel(app.container.repo) })
    val state by vm.ui.collectAsStateWithLifecycle()

    // null = 关闭；非 null = 编辑该分类（CategoryEntity(id="") 表示新增）
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "分类管理",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { editing = CategoryEntity("", "", "✨", state.tab, 0, 0) }) {
                Icon(Icons.Filled.Add, contentDescription = "新增分类")
            }
        }

        SegTabs(
            options = listOf("支出", "收入"),
            selected = if (state.tab == RecordType.EXPENSE) 0 else 1,
            onSelect = { vm.switchTab(if (it == 0) RecordType.EXPENSE else RecordType.INCOME) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 13.dp, end = 13.dp, top = 10.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        state.categories.forEach { cat ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { editing = cat }
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                            ) {
                                EmojiBadge(cat.emoji, size = 34.dp)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(cat.name, fontSize = 14.sp, color = Ink, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${state.counts[cat.id] ?: 0} 条记录",
                                        fontSize = 11.sp,
                                        color = Sub,
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = "编辑",
                                    tint = Sub,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { cat ->
        CategoryEditDialog(
            initial = cat,
            count = state.counts[cat.id] ?: 0,
            onSave = { name, emoji ->
                if (cat.id.isEmpty()) vm.add(name, emoji) else vm.rename(cat.id, name, emoji)
                editing = null
            },
            onDelete = if (cat.id.isEmpty() ||
                cat.id == CategoryEntity.OTHER_EXPENSE || cat.id == CategoryEntity.OTHER_INCOME
            ) {
                null // 新增中 / 「其他」不可删
            } else {
                { vm.delete(cat.id); editing = null }
            },
            onDismiss = { editing = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditDialog(
    initial: CategoryEntity,
    count: Int,
    onSave: (name: String, emoji: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(if (initial.id.isEmpty()) "" else initial.name) }
    var emoji by remember { mutableStateOf(initial.emoji.ifBlank { "✨" }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initial.id.isEmpty()) "新增分类" else "编辑分类", fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EMOJI_CHOICES.forEach { e ->
                        val selected = e == emoji
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .then(
                                    if (selected) {
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { emoji = e },
                        ) {
                            Text(e, fontSize = 19.sp)
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(8) },
                    singleLine = true,
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                if (onDelete != null && count > 0) {
                    Text(
                        "删除后，该分类下 $count 条记录将归入「其他」",
                        fontSize = 11.sp,
                        color = Sub,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, emoji) }, enabled = name.isNotBlank()) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = Danger, modifier = Modifier.size(16.dp))
                        Text("删除", color = Danger)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
