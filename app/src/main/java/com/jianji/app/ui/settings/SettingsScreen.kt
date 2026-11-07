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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jianji.app.AppContainer
import com.jianji.app.BuildConfig
import com.jianji.app.JianJiApp
import com.jianji.app.data.csv.CsvManager
import com.jianji.app.data.csv.XlsxTemplate
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
import java.io.File
import java.time.YearMonth

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val ui: StateFlow<Settings> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    private val _msg = MutableStateFlow<String?>(null)
    val msg: StateFlow<String?> = _msg.asStateFlow()

    /** 待分享的 CSV URI */
    private val _shareUri = MutableStateFlow<Uri?>(null)
    val shareUri: StateFlow<Uri?> = _shareUri.asStateFlow()

    fun setAppLock(v: Boolean) {
        viewModelScope.launch { container.settings.setAppLock(v) }
    }

    /** 导出 CSV 到缓存并准备分享 */
    fun exportCsv(fromDay: Int, toDay: Int) {
        viewModelScope.launch {
            val ctx = container.appContext
            val file = File(File(ctx.cacheDir, "share"), CsvManager.suggestedCsvName())
            container.csv.exportToFile(file, fromDay, toDay)
                .onSuccess {
                    _shareUri.value = runCatching {
                        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                    }.getOrNull()
                    if (_shareUri.value == null) _msg.value = "导出失败：无法生成分享文件"
                    else _msg.value = "已导出 $it 条记录"
                }
                .onFailure { _msg.value = "导出失败：${it.message}" }
        }
    }

    fun importCsv(uri: Uri) {
        viewModelScope.launch {
            container.csv.importFrom(uri)
                .onSuccess { r ->
                    _msg.value = when {
                        r.skipped > 0 && r.firstError != null ->
                            "已导入 ${r.imported} 条，跳过 ${r.skipped} 条：${r.firstError}"
                        r.skipped > 0 ->
                            "已导入 ${r.imported} 条，跳过 ${r.skipped} 条"
                        else -> "已导入 ${r.imported} 条记录"
                    }
                }
                .onFailure { _msg.value = "导入失败：${it.message}" }
        }
    }

    /** 模板写入用户选定的保存位置 */
    fun saveTemplate(uri: Uri) {
        viewModelScope.launch {
            container.csv.saveTemplate(uri)
                .onSuccess { _msg.value = "Excel 模板已保存" }
                .onFailure { _msg.value = "模板保存失败：${it.message}" }
        }
    }

    /** CSV 模板写入用户选定的保存位置 */
    fun saveCsvTemplate(uri: Uri) {
        viewModelScope.launch {
            container.csv.saveCsvTemplate(uri)
                .onSuccess { _msg.value = "CSV 模板已保存" }
                .onFailure { _msg.value = "模板保存失败：${it.message}" }
        }
    }

    fun consumeMsg() {
        _msg.value = null
    }

    fun consumeShare() {
        _shareUri.value = null
    }
}

@Composable
fun SettingsScreen(onCategories: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as JianJiApp
    val vm: SettingsViewModel = viewModel(initializer = { SettingsViewModel(app.container) })
    val settings by vm.ui.collectAsStateWithLifecycle()
    val msg by vm.msg.collectAsStateWithLifecycle()
    val shareUri by vm.shareUri.collectAsStateWithLifecycle()

    LaunchedEffect(msg) {
        msg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeMsg()
        }
    }

    // 导出完成后拉起系统分享面板
    LaunchedEffect(shareUri) {
        val uri = shareUri ?: return@LaunchedEffect
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "导出 CSV"))
        }.onFailure {
            Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
        }
        vm.consumeShare()
    }

    // CSV 导出范围：弹窗选择 → 写缓存并分享
    var showExportDialog by remember { mutableStateOf(false) }
    // 导入前的格式说明
    var showImportHelp by remember { mutableStateOf(false) }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importCsv)
    }

    // 保存导入模板：由用户选位置，取消则什么都不做
    val templatePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(XlsxTemplate.MIME)
    ) { uri ->
        uri?.let(vm::saveTemplate)
    }

    val csvTemplatePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CsvManager.CSV_MIME)
    ) { uri ->
        uri?.let(vm::saveCsvTemplate)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
            IconRow(
                icon = Icons.Filled.FileDownload,
                title = "导入 CSV / Excel",
                sub = "支持 .xlsx 与 .csv，可先下载模板",
                onClick = { showImportHelp = true },
            )
            IconRow(
                icon = Icons.Filled.Share,
                title = "导出 CSV",
                sub = "可选范围，导出后可直接分享",
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
                sub = "${BuildConfig.VERSION_NAME} · 本地优先 · 无服务器",
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
                                    vm.exportCsv(range.first, range.second)
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

    if (showImportHelp) {
        fun launchSaf(action: () -> Unit, failMsg: String) {
            runCatching(action).onFailure {
                Toast.makeText(context, failMsg, Toast.LENGTH_SHORT).show()
            }
        }
        AlertDialog(
            onDismissRequest = { showImportHelp = false },
            title = { Text("导入账单") },
            text = {
                Column {
                    Text(
                        "先下载模板，按模板填写后再选择文件导入。",
                        fontSize = 13.sp,
                        color = Ink,
                        lineHeight = 21.sp,
                    )
                    Spacer(Modifier.size(4.dp))
                    IconRow(
                        icon = Icons.Filled.FileDownload,
                        title = "CSV 模板",
                        sub = "文本表格，Excel / WPS 可直接打开",
                        onClick = {
                            showImportHelp = false
                            launchSaf({ csvTemplatePicker.launch(CsvManager.CSV_TEMPLATE_NAME) }, "无法打开系统文件保存器")
                        },
                    )
                    IconRow(
                        icon = Icons.Filled.FileDownload,
                        title = "Excel 模板",
                        sub = ".xlsx，第一个工作表",
                        onClick = {
                            showImportHelp = false
                            launchSaf({ templatePicker.launch(XlsxTemplate.FILE_NAME) }, "无法打开系统文件保存器")
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showImportHelp = false
                    launchSaf(
                        {
                            importPicker.launch(
                                arrayOf(
                                    "text/csv",
                                    "text/comma-separated-values",
                                    "application/vnd.ms-excel",
                                    XlsxTemplate.MIME,
                                )
                            )
                        },
                        "无法打开系统文件选择器",
                    )
                }) { Text("选择文件") }
            },
            dismissButton = {
                TextButton(onClick = { showImportHelp = false }) { Text("取消") }
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
