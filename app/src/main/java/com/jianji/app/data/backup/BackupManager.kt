package com.jianji.app.data.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.jianji.app.data.model.RecordType
import com.jianji.app.data.repository.LedgerRepository
import com.jianji.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 备份 / 恢复 / CSV 导出。
 * 全部走 SAF（Storage Access Framework）：用户自选目录（推荐网盘同步文件夹），
 * 应用不申请任何存储权限。
 */
class BackupManager(
    private val context: Context,
    private val repo: LedgerRepository,
) {

    /** 全量 JSON 备份（含墓碑，幂等合并） */
    suspend fun backupTo(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val snapshot = SnapshotV1(
                exportedAtEpochMs = System.currentTimeMillis(),
                categories = repo.snapshotCategories().map { CategoryDto.from(it) },
                records = repo.snapshotRecords().map { RecordDto.from(it) },
            )
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(BackupCodec.encode(snapshot).toByteArray(Charsets.UTF_8))
            } ?: error("无法写入 $uri")
        }
    }

    /** 恢复：按 id 幂等合并，不覆盖更新的本地数据（REPLACE 语义下以备份为准，见 README 说明） */
    suspend fun restoreFrom(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: error("无法读取 $uri")
            val snap = BackupCodec.decode(text)
            repo.mergeSnapshot(
                snap.categories.map { it.toEntity() },
                snap.records.map { it.toEntity() },
            )
            snap.records.size
        }
    }

    /** CSV 导出（UTF-8；备注含逗号/引号/换行会正确转义） */
    suspend fun exportCsv(uri: Uri, fromDay: Int, toDay: Int): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rows = repo.csvRows(fromDay, toDay)
                val sb = StringBuilder("日期,类型,分类,金额(元),备注\n")
                rows.forEach { r ->
                    sb.append(LocalDate.ofEpochDay(r.dateEpochDay.toLong())).append(',')
                        .append(if (r.type == RecordType.EXPENSE) "支出" else "收入").append(',')
                        .append(csv("${r.categoryEmoji} ${r.categoryName}")).append(',')
                        .append(Money.csvAmount(r.amountMinor)).append(',')
                        .append(csv(r.note)).append('\n')
                }
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(sb.toString().toByteArray(Charsets.UTF_8))
                } ?: error("无法写入 $uri")
                rows.size
            }
        }

    private fun csv(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }

    companion object {
        private const val BACKUP_PREFIX = "jianji-backup-"
        private val stampFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

        /** 在备份目录里创建带时间戳的备份文件 */
        fun newBackupFile(context: Context, dirUri: String): Uri? {
            val dir = DocumentFile.fromTreeUri(context, Uri.parse(dirUri)) ?: return null
            if (!dir.isDirectory) return null
            val stamp = LocalDateTime.now().format(stampFmt)
            return dir.createFile("application/json", "$BACKUP_PREFIX$stamp.json")?.uri
        }

        /** 只保留最近 maxKeep 份备份 */
        fun prune(context: Context, dirUri: String, maxKeep: Int = 30): Int {
            val dir = DocumentFile.fromTreeUri(context, Uri.parse(dirUri)) ?: return 0
            val backups = dir.listFiles()
                .filter { it.name?.startsWith(BACKUP_PREFIX) == true }
                .sortedByDescending { it.name }
            var removed = 0
            backups.drop(maxKeep).forEach { if (it.delete()) removed++ }
            return removed
        }

        /** 设置页展示目录名（尽量友好） */
        fun dirDisplayName(context: Context, dirUri: String?): String {
            if (dirUri == null) return "未选择"
            return runCatching {
                DocumentFile.fromTreeUri(context, Uri.parse(dirUri))?.name
                    ?: Uri.parse(dirUri).lastPathSegment ?: dirUri
            }.getOrDefault(dirUri)
        }

        /** 导出文件名建议值：jianji-export-20261104.csv */
        fun suggestedCsvName(): String =
            "jianji-export-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".csv"
    }
}
