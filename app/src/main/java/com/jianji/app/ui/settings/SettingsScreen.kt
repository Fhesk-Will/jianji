package com.jianji.app.ui.settings

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
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
import androidx.compose.material.icons.filled.DateRange
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

data class ShareRequest(val uri: Uri, val mimeType: String, val title: String)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val ui: StateFlow<Settings> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    private val _msg = MutableStateFlow<String?>(null)
    val msg: StateFlow<String?> = _msg.asStateFlow()

    private val _shareRequest = MutableStateFlow<ShareRequest?>(null)
    val shareRequest: StateFlow<ShareRequest?> = _shareRequest.asStateFlow()

    fun setAppLock(v: Boolean) {
        viewModelScope.launch { container.settings.setAppLock(v) }
    }

    /** 导出 CSV 到缓存并准备分享 */
    fun exportCsv(fromDay: Int, toDay: Int) {
        viewModelScope.launch {
            val file = sharedFile(CsvManager.suggestedCsvName())
            container.csv.exportToFile(file, fromDay, toDay)
                .onSuccess { count ->
                    publishShare(file, CsvManager.CSV_MIME, "导出 CSV", "已导出 $count 条记录", "导出")
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

    fun shareExcelTemplate() {
        viewModelScope.launch {
            val file = sharedFile(XlsxTemplate.FILE_NAME)
            container.csv.createExcelTemplate(file)
                .onSuccess { publishShare(file, XlsxTemplate.MIME, "分享 Excel 模板", null, "模板生成") }
                .onFailure { _msg.value = "模板生成失败：${it.message}" }
        }
    }

    fun shareCsvTemplate() {
        viewModelScope.launch {
            val file = sharedFile(CsvManager.CSV_TEMPLATE_NAME)
            container.csv.createCsvTemplate(file)
                .onSuccess { publishShare(file, CsvManager.CSV_MIME, "分享 CSV 模板", null, "模板生成") }
                .onFailure { _msg.value = "模板生成失败：${it.message}" }
        }
    }

    /** 外部文件名称只用于导入确认，读取放在后台线程 */
    suspend fun displayName(uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            container.appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "所选文件"
    }

    private fun sharedFile(name: String): File =
        File(File(container.appContext.cacheDir, "share"), name)

    private fun publishShare(
        file: File,
        mimeType: String,
        title: String,
        successMsg: String?,
        operation: String,
    ) {
        val ctx = container.appContext
        val uri = runCatching {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        }.getOrNull()
        if (uri == null) {
            _msg.value = "${operation}失败：无法生成分享文件"
        } else {
            _shareRequest.value = ShareRequest(uri, mimeType, title)
            successMsg?.let { _msg.value = it }
        }
    }

    fun consumeMsg() {
        _msg.value = null
    }

    fun consumeShare() {
        _shareRequest.value = null
    }
}

@Composable
fun SettingsScreen(
    onCategories: () -> Unit,
    incomingImportUri: Uri? = null,
    onIncomingImportConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as JianJiApp
    val vm: SettingsViewModel = viewModel(initializer = { SettingsViewModel(app.container) })
    val settings by vm.ui.collectAsStateWithLifecycle()
    val msg by vm.msg.collectAsStateWithLifecycle()
    val shareRequest by vm.shareRequest.collectAsStateWithLifecycle()

    LaunchedEffect(msg) {
        msg?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.consumeMsg()
        }
    }

    // 导出账单或生成模板后，统一拉起系统分享面板
    LaunchedEffect(shareRequest) {
        val share = shareRequest ?: return@LaunchedEffect
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = share.mimeType
                putExtra(Intent.EXTRA_STREAM, share.uri)
                clipData = ClipData.newRawUri(share.title, share.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, share.title))
        }.onFailure {
            Log.w("SettingsScreen", "无法打开分享面板", it)
            Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_LONG).show()
        }
        vm.consumeShare()
    }

    var showExportDialog by remember { mutableStateOf(false) }
    var exportStart by remember { mutableStateOf(LocalDate.now().minusYears(1)) }
    var exportEnd by remember { mutableStateOf(LocalDate.now()) }
    var showImportHelp by remember { mutableStateOf(false) }
    var externalFileName by remember { mutableStateOf("所选文件") }

    LaunchedEffect(incomingImportUri) {
        incomingImportUri?.let { externalFileName = vm.displayName(it) }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importCsv)
    }
    val fallbackImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(vm::importCsv)
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
                sub = "支持 .xlsx 与 .csv，可先分享模板",
                onClick = { showImportHelp = true },
            )
            IconRow(
                icon = Icons.Filled.Share,
                title = "导出 CSV",
                sub = "选择起止日期，导出后可直接分享",
                onClick = {
                    exportEnd = LocalDate.now()
                    exportStart = exportEnd.minusYears(1)
                    showExportDialog = true
                },
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
        fun pickDate(initial: LocalDate, onPicked: (LocalDate) -> Unit) {
            android.app.DatePickerDialog(
                context,
                { _, year, month, day -> onPicked(LocalDate.of(year, month + 1, day)) },
                initial.year,
                initial.monthValue - 1,
                initial.dayOfMonth,
            ).show()
        }
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出账单") },
            text = {
                Column {
                    IconRow(
                        icon = Icons.Filled.DateRange,
                        title = "开始日期",
                        sub = exportStart.toString(),
                        onClick = { pickDate(exportStart) { exportStart = it } },
                    )
                    IconRow(
                        icon = Icons.Filled.DateRange,
                        title = "结束日期",
                        sub = exportEnd.toString(),
                        onClick = { pickDate(exportEnd) { exportEnd = it } },
                    )
                    if (exportStart.isAfter(exportEnd)) {
                        Text("开始日期不能晚于结束日期", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !exportStart.isAfter(exportEnd),
                    onClick = {
                        showExportDialog = false
                        vm.exportCsv(Dates.epochDay(exportStart), Dates.epochDay(exportEnd))
                    },
                ) { Text("导出并分享") }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("取消") }
            },
        )
    }

    if (showImportHelp) {
        fun launchImportPicker() {
            val primaryError = runCatching {
                importPicker.launch(
                    arrayOf(
                        "text/csv",
                        "text/comma-separated-values",
                        "application/vnd.ms-excel",
                        XlsxTemplate.MIME,
                    )
                )
            }.exceptionOrNull() ?: return
            Log.w("SettingsScreen", "系统文档选择器不可用，尝试其他文件应用", primaryError)
            val fallbackError = runCatching { fallbackImportPicker.launch("*/*") }.exceptionOrNull()
            if (fallbackError != null) {
                Log.w("SettingsScreen", "无法打开文件选择器", fallbackError)
                Toast.makeText(
                    context,
                    "无法打开文件选择器，请检查系统文件应用是否被停用；也可在微信中用简记打开文件",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        AlertDialog(
            onDismissRequest = { showImportHelp = false },
            title = { Text("导入账单") },
            text = {
                Column {
                    Text(
                        "模板可直接分享到微信 / WPS。填写后从这里选择文件，或在微信中选择“用其他应用打开 → 简记”。",
                        fontSize = 13.sp,
                        color = Ink,
                        lineHeight = 21.sp,
                    )
                    Spacer(Modifier.size(4.dp))
                    IconRow(
                        icon = Icons.Filled.Share,
                        title = "分享 CSV 模板",
                        sub = "文本表格，Excel / WPS 可直接打开",
                        onClick = {
                            showImportHelp = false
                            vm.shareCsvTemplate()
                        },
                    )
                    IconRow(
                        icon = Icons.Filled.Share,
                        title = "分享 Excel 模板",
                        sub = ".xlsx，第一个工作表",
                        onClick = {
                            showImportHelp = false
                            vm.shareExcelTemplate()
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showImportHelp = false
                    launchImportPicker()
                }) { Text("选择文件") }
            },
            dismissButton = {
                TextButton(onClick = { showImportHelp = false }) { Text("取消") }
            },
        )
    }

    incomingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = onIncomingImportConsumed,
            title = { Text("导入外部文件") },
            text = {
                Column {
                    Text(externalFileName, fontSize = 14.sp, color = Ink, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "确认将此文件中的账单导入简记吗？取消不会修改现有数据。",
                        fontSize = 13.sp,
                        color = Ink,
                        lineHeight = 21.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onIncomingImportConsumed()
                    vm.importCsv(uri)
                }) { Text("确认导入") }
            },
            dismissButton = {
                TextButton(onClick = onIncomingImportConsumed) { Text("取消") }
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
