package com.jianji.app.ui.record

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jianji.app.JianJiApp
import com.jianji.app.data.entity.CategoryEntity
import com.jianji.app.data.model.RecordType
import com.jianji.app.ui.components.EmojiBadge
import com.jianji.app.ui.components.SegTabs
import com.jianji.app.ui.theme.Accent
import com.jianji.app.ui.theme.Danger
import com.jianji.app.ui.theme.Ink
import com.jianji.app.ui.theme.Income
import com.jianji.app.ui.theme.OnPrimaryContainer
import com.jianji.app.ui.theme.PrimaryDeep
import com.jianji.app.ui.theme.Sub
import com.jianji.app.util.Dates
import com.jianji.app.util.Money
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRecordScreen(recordId: String?, onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as JianJiApp
    val vm: AddRecordViewModel = viewModel(
        key = recordId ?: "new",
        initializer = { AddRecordViewModel(app.container.repo, recordId) },
    )
    val state by vm.ui.collectAsStateWithLifecycle()
    val finished by vm.finished.collectAsStateWithLifecycle()

    LaunchedEffect(finished) { if (finished) onDone() }

    Column(Modifier.fillMaxSize()) {
        // 顶栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) {
                Icon(Icons.Filled.Close, contentDescription = "关闭")
            }
            Text(
                if (state.editing) "编辑记录" else "记一笔",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (state.editing) {
                IconButton(onClick = vm::delete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除", tint = Danger)
                }
            }
        }

        // 支出 / 收入
        SegTabs(
            options = listOf("支出", "收入"),
            selected = if (state.type == RecordType.EXPENSE) 0 else 1,
            onSelect = { vm.setType(if (it == 0) RecordType.EXPENSE else RecordType.INCOME) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        // 金额区：左当前分类，右为纯数字大金额 或 算式+实时结果
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            val cat = state.categories.firstOrNull { it.id == state.categoryId }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    if (cat == null) "选分类" else "${cat.emoji} ${cat.name}",
                    fontSize = 13.sp,
                    color = OnPrimaryContainer,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.weight(1f))
            if (Money.hasOperator(state.amountInput)) {
                // 算式模式：大字算式 + 下方实时「= ¥结果」
                val resultMinor = remember(state.amountInput) {
                    Money.evalExprMinor(state.amountInput)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        state.amountInput,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (state.type == RecordType.INCOME) Income else Ink,
                        maxLines = 1,
                        softWrap = false,
                    )
                    resultMinor?.let {
                        Text(
                            "= " + Money.yuan(it),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "¥",
                        fontSize = 17.sp,
                        color = Sub,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (state.amountInput.isEmpty()) "0" else state.amountInput,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (state.type == RecordType.INCOME) Income else Ink,
                        modifier = Modifier.width(160.dp),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
        if (state.error) {
            Text(
                "算式不完整 / 结果 ≤ 0 / 未选分类",
                fontSize = 12.sp,
                color = Danger,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        // 分类宫格
        CategoryGrid(
            categories = state.categories,
            selectedId = state.categoryId,
            onSelect = vm::selectCategory,
            modifier = Modifier.padding(horizontal = 14.dp),
        )

        // 日期 + 备注
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            DateField(date = state.date, onPick = vm::setDate)
            OutlinedTextField(
                value = state.note,
                onValueChange = vm::setNote,
                singleLine = true,
                placeholder = { Text("备注（选填）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.weight(1f))

        Keypad(
            onKey = vm::onKey,
            onBackspace = vm::backspace,
            onSave = { vm.save(false) },
            onSaveNew = { vm.save(true) },
        )
    }
}

// ---------- 分类宫格（非懒加载，数量少） ----------

@Composable
private fun CategoryGrid(
    categories: List<CategoryEntity>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        categories.chunked(4).forEach { rowCats ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowCats.forEach { cat ->
                    val selected = cat.id == selectedId
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else androidx.compose.ui.graphics.Color.Transparent,
                            )
                            .clickable { onSelect(cat.id) }
                            .padding(vertical = 8.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .then(
                                    if (selected) {
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            Text(cat.emoji, fontSize = 21.sp)
                        }
                        Text(
                            cat.name,
                            fontSize = 12.sp,
                            color = if (selected) OnPrimaryContainer else Sub,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
                // 补齐空位，保持 4 列对齐
                repeat(4 - rowCats.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// ---------- 日期 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(date: LocalDate, onPick: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }) {
        Text(Dates.dayLabel(date), fontSize = 13.sp, maxLines = 1)
    }
    if (open) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = Dates.toUtcMillis(date))
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { onPick(Dates.fromUtcMillis(it)) }
                    open = false
                }) { Text("确定") }
            },
        ) {
            DatePicker(state = dpState)
        }
    }
}

// ---------- 自定义键盘 ----------

@Composable
private fun Keypad(
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSave: () -> Unit,
    onSaveNew: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // 5 列：数字 3 列 + ⌫/. 列 + 运算符列（÷ × ＋ −）
        listOf(
            listOf("7", "8", "9", "⌫", "÷"),
            listOf("4", "5", "6", ".", "×"),
            listOf("1", "2", "3", "＋", "−"),
        ).forEach { rowKeys ->
            Row(Modifier.fillMaxWidth()) {
                rowKeys.forEach { k ->
                    val operator = k in listOf("÷", "×", "＋", "−")
                    when (k) {
                        "⌫" -> KeyBox(
                            modifier = Modifier.weight(1f),
                            height = 58.dp,
                            onClick = onBackspace,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = "退格（长按清空为二期）",
                                tint = Sub,
                            )
                        }
                        else -> KeyBox(
                            modifier = Modifier.weight(1f),
                            height = 58.dp,
                            operator = operator,
                            onClick = {
                                // 显示符号 -> 求值字符：÷/ ×* ＋+ −-
                                val emit = when (k) {
                                    "÷" -> '/'
                                    "×" -> '*'
                                    "＋" -> '+'
                                    "−" -> '-'
                                    else -> k[0]
                                }
                                onKey(emit)
                            },
                        ) {
                            if (k == ".") {
                                Text(".", fontSize = 21.sp, color = Sub, fontWeight = FontWeight.Medium)
                            } else if (operator) {
                                Text(
                                    k,
                                    fontSize = 19.sp,
                                    color = PrimaryDeep,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            } else {
                                Text(k, fontSize = 21.sp, color = Ink, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            KeyBox(
                modifier = Modifier.weight(2f),
                height = 62.dp,
                onClick = { onKey('0') },
            ) {
                Text("0", fontSize = 21.sp, color = Ink, fontWeight = FontWeight.Medium)
            }
            KeyBox(
                modifier = Modifier.weight(1f),
                height = 62.dp,
                onClick = onSaveNew,
            ) {
                Text("再记", fontSize = 14.sp, color = Sub, fontWeight = FontWeight.Medium)
            }
            KeyBox(
                modifier = Modifier.weight(2f),
                height = 62.dp,
                primary = true,
                onClick = onSave,
            ) {
                Text(
                    "完成",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,   // 琥珀跳色上用深字，保证对比度
                )
            }
        }
    }
}

@Composable
private fun KeyBox(
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp,
    primary: Boolean = false,
    operator: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(height)
            .background(
                when {
                    primary -> Accent
                    operator -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surface
                },
            )
            .clickable(onClick = onClick),
    ) {
        content()
    }
}
