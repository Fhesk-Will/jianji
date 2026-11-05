package com.jianji.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jianji.app.AppContainer
import com.jianji.app.JianJiApp
import com.jianji.app.data.backup.BackupManager
import com.jianji.app.data.prefs.Settings
import com.jianji.app.ui.theme.Ink
import com.jianji.app.ui.theme.Sub
import com.jianji.app.util.Dates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val ui: StateFlow<Settings> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    private val _msg = MutableStateFlow<String?>(null)
    val msg: StateFlow<String?> = _msg.asStateFlow()

    fun setAutoBackup(v: Boolean) {
        viewModelScope.launch { container.settings.setAutoBackup(v) }
    }

    fun setAppLock(v: Boolean) {
        viewModelScope.launch { container.settings.setAppLock(v) }
    }

    fun setBackupDir(uri: Uri) {
        viewModelScope.launch {
            container.settings.setBackupDir(uri.toString())
            _msg.value = "备份目录已设置"
        }
    }

    fun backupNow() {
        viewModelScope.launch {
            val dir = ui.value.backupDirUri
            if (dir == null) {
                _msg.value = "请先选择备份目录"
                return@launch
            }
            val file = BackupManager.newBackupFile(container.appContext, dir)
            if (file == null) {
                _msg.value = "备份目录不可用"
                return@launch
            }
            container.backup.backupTo(file)
                .onSuccess {
                    container.settings.setLastBackup(System.currentTimeMillis())
                    BackupManager.prune(container.appContext, dir)
                    _msg.value = "备份完成"
                }
                .onFailure { _msg.value = "备份失败：${it.message}" }
        }
    }

    fun restore(uri: Uri) {
        viewModelScope.launch {
            container.backup.restoreFrom(uri)
                .onSuccess { _msg.value = "已恢复 $it 条记录（按 id 幂等合并）" }
                .onFailure { _msg.value = "恢复失败：${it.message}" }
        }
    }

    /** @param fromDay/toDay epochDay 区间（含端点） */
    fun exportCsv(uri: Uri, fromDay: Int, toDay: Int) {
        viewModelScope.launch {
            container.backup.exportCsv(uri, fromDay, toDay)
                .onSuccess { _msg.value = "已导出 $it 条记录" }
                .onFailure { _msg.value = "导出失败：${it.message}" }
        }
    }

    fun consumeMsg() {
        _msg.value = null
    }
}

@Composable
fun SettingsScreen(onCategories: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as JianJiApp
    val vm: SettingsViewModel = viewModel(initializer = { SettingsViewModel(app.container) })
    val settings by vm.ui.collectAsStateWithLifecycle()
    val msg by vm.msg.collectAsStateWithLifecycle()

    LaunchedEffect(msg) {
        msg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeMsg()
        }
    }

    // CSV 导出范围：弹窗选择 → 记住区间 → SAF 建档
    var showExportDialog by remember { mutableStateOf(false) }
    var pendingExportRange by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // SAF：选备份目录（可持久化授权，推荐指向网盘同步文件夹）
    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            vm.setBackupDir(it)
        }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::restore)
    }
    val csvCreator = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val range = pendingExportRange
        pendingExportRange = null
        if (uri != null && range != null) vm.exportCsv(uri, range.first, range.second)
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "设置",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Ink,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )

        GroupCard("记账") {
            IconRow(Icons.Filled.Category, "分类管理", "支出 8 · 收入 7", onClick = onCategories)
        }

        GroupCard("数据") {
            SwitchRow(
                icon = Icons.Filled.CloudUpload,
                title = "自动备份",
                sub = "每日一次，写入备份目录，保留最近 30 份",
                checked = settings.autoBackup,
                onToggle = vm::setAutoBackup,
            )
            IconRow(
                icon = Icons.Filled.Folder,
                title = "备份目录",
                sub = BackupManager.dirDisplayName(context, settings.backupDirUri) +
                    "（建议选网盘同步文件夹）",
                onClick = { dirPicker.launch(null) },
            )
            IconRow(
                icon = Icons.Filled.Restore,
                title = "立即备份",
                sub = "上次备份：${Dates.backupTimeLabel(settings.lastBackupAtMs)}",
                onClick = vm::backupNow,
            )
            IconRow(
                icon = Icons.Filled.Restore,
                title = "从备份恢复",
                sub = "选择 .json 备份文件，按 id 合并不覆盖",
                onClick = { restorePicker.launch(arrayOf("application/json")) },
            )
            IconRow(
                icon = Icons.Filled.FileDownload,
                title = "导出 CSV",
                sub = "可选范围：全部 / 今年 / 最近三个月 / 本月",
                onClick = { showExportDialog = true },
            )
        }

        GroupCard("安全") {
            SwitchRow(
                icon = Icons.Filled.Lock,
                title = "应用锁",
                sub = "进入 App 需指纹或 PIN（回到前台同样需验证）",
                checked = settings.appLock,
                onToggle = vm::setAppLock,
            )
        }

        GroupCard("关于") {
            IconRow(
                icon = Icons.Filled.Info,
                title = "版本",
                sub = "0.1.0 · 本地优先 · 无服务器",
                onClick = null,
            )
        }
    }

    if (showExportDialog) {
        val now = YearMonth.now()
        val options = listOf(
            "本月" to Dates.monthRange(now),
            "最近三个月" to (Dates.monthRange(now.minusMonths(2)).first to Dates.monthRange(now).second),
            "今年" to (Dates.monthRange(YearMonth.of(now.year, 1)).first to Dates.monthRange(YearMonth.of(now.year, 12)).second),
            "全部" to (0 to 999_999),
        )
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出范围") },
            text = {
                Column {
                    options.forEach { (label, range) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    showExportDialog = false
                                    pendingExportRange = range
                                    csvCreator.launch(BackupManager.suggestedCsvName())
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                        ) {
                            Text(
                                label,
                                fontSize = 14.5.sp,
                                color = Ink,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${Dates.ofEpochDay(range.first)} ~ ${if (range.second >= 999_999) "至今" else Dates.ofEpochDay(range.second).toString()}",
                                fontSize = 12.sp,
                                color = Sub,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun GroupCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 13.dp)) {
        Text(
            title,
            fontSize = 10.5.sp,
            color = Sub,
            modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
        )
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun IconRow(
    icon: ImageVector,
    title: String,
    sub: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        IconBox(icon)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(sub, fontSize = 11.sp, color = Sub)
        }
        Spacer(Modifier.weight(1f))
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Sub,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    sub: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        IconBox(icon)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(sub, fontSize = 11.sp, color = Sub)
        }
        Spacer(Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun IconBox(icon: ImageVector) {
    androidx.compose.foundation.layout.Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(18.dp),
        )
    }
}
