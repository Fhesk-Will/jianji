package com.jianji.app.data.csv

import android.content.Context
import android.net.Uri
import com.jianji.app.data.entity.CategoryEntity
import com.jianji.app.data.model.RecordType
import com.jianji.app.data.repository.LedgerRepository
import com.jianji.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 表格导入 / 导出。
 * 导出：CSV，写本应用缓存文件，由界面调用系统分享面板发出。
 * 导入：CSV 或 Excel（.xlsx，只读第一个工作表），列名用简记自己的规则；
 *       分类按名称匹配，匹配不到归入「其他」；逐行校验并报告首个错误行号。
 * 所有方法内部切到 IO 线程。
 */
class CsvManager(
    private val context: Context,
    private val repo: LedgerRepository,
) {

    /** 导入结果：成功条数、跳过条数、首个错误说明（若有） */
    data class ImportResult(val imported: Int, val skipped: Int, val firstError: String?)

    // ---------- 导出 ----------

    /** 导出 CSV 到缓存文件，返回写入条数 */
    suspend fun exportToFile(file: File, fromDay: Int, toDay: Int): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rows = repo.csvRows(fromDay, toDay)
                val sb = StringBuilder(HEADER).append('\n')
                rows.forEach { r ->
                    sb.append(LocalDate.ofEpochDay(r.dateEpochDay.toLong())).append(',')
                        .append(if (r.type == RecordType.EXPENSE) "支出" else "收入").append(',')
                        .append(escape("${r.categoryEmoji} ${r.categoryName}")).append(',')
                        .append(Money.csvAmount(r.amountMinor)).append(',')
                        .append(escape(r.note)).append('\n')
                }
                file.parentFile?.mkdirs()
                file.outputStream().buffered().use {
                    it.write(UTF8_BOM)
                    it.write(sb.toString().toByteArray(Charsets.UTF_8))
                }
                rows.size
            }
        }

    // ---------- 导入 ----------

    /** 从用户选中的 CSV / XLSX 导入；整表无可导入行时返回失败 */
    suspend fun importFrom(uri: Uri): Result<ImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("无法读取所选文件")
            val table = when {
                isZip(bytes) -> XlsxParser.readFirstSheet(ByteArrayInputStream(bytes)).map {
                    RawRow(it.number, it.cells)
                }
                isOle(bytes) -> error("暂不支持 .xls，请另存为 .xlsx 或 CSV 后再导入")
                else -> csvTable(decodeText(bytes))
            }
            if (table.isEmpty()) error("没有读到数据，请确认第一行是表头")
            val parsed = parseTable(table)
            if (parsed.rows.isEmpty()) {
                error(parsed.firstError ?: "没有可导入的记录")
            }
            val categories = repo.snapshotCategories()
            val byTypeAndName = categories
                .filter { !it.isDeleted && it.parentId != null }
                .associateBy { it.type to it.name }
            var imported = 0
            parsed.rows.forEach { row ->
                val categoryId = byTypeAndName[row.type to row.categoryName]?.id
                    ?: if (row.type == RecordType.EXPENSE) CategoryEntity.OTHER_EXPENSE
                    else CategoryEntity.OTHER_INCOME
                repo.addRecord(row.type, row.amountMinor, categoryId, row.date, row.note)
                imported++
            }
            ImportResult(imported, parsed.skipped, parsed.firstError)
        }
    }

    // ---------- 导入模板 ----------

    /** 生成待分享的 .xlsx 模板 */
    suspend fun createExcelTemplate(file: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().buffered().use { XlsxTemplate.write(it) }
        }
    }

    /** 生成待分享的 CSV 模板：UTF-8 带 BOM，表头与导入解析一致 */
    suspend fun createCsvTemplate(file: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().buffered().use {
                it.write(UTF8_BOM)
                it.write((HEADER + "\n" + TEMPLATE_ROW + "\n").toByteArray(Charsets.UTF_8))
            }
        }
    }

    private data class RawRow(val number: Int, val cells: List<XlsxParser.Cell>)

    private data class Row(
        val date: LocalDate,
        val type: RecordType,
        val categoryName: String,
        val amountMinor: Long,
        val note: String,
    )

    private data class Parsed(val rows: List<Row>, val skipped: Int, val firstError: String?)

    /** 先按 UTF-8 解码；出现替换字符说明不是 UTF-8（如 Excel 另存的 GBK 文件），改用 GBK 再试一次 */
    private fun decodeText(bytes: ByteArray): String {
        val utf8 = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        if ('\uFFFD' !in utf8) return utf8
        return runCatching { String(bytes, charset("GBK")).removePrefix("\uFEFF") }.getOrDefault(utf8)
    }

    /** CSV → 与 XLSX 相同的中间结构（CSV 单元格按文本处理） */
    private fun csvTable(text: String): List<RawRow> =
        splitRecords(text).mapIndexed { index, cells ->
            RawRow(index + 1, cells.map { XlsxParser.Cell(it.trim()) })
        }

    private fun parseTable(table: List<RawRow>): Parsed {
        val header = table.first().cells.map { it.text.trim() }
        val hasHeader = header.any { it.contains("日期") }
        val body = if (hasHeader) table.drop(1) else table
        val idxDate = header.indexOfFirst { it.contains("日期") }.takeIf { it >= 0 } ?: 0
        val idxType = header.indexOfFirst { it.contains("类型") }.takeIf { it >= 0 } ?: 1
        val idxCategory = header.indexOfFirst { it.contains("分类") }.takeIf { it >= 0 } ?: 2
        val idxAmount = header.indexOfFirst { it.contains("金额") }.takeIf { it >= 0 } ?: 3
        val idxNote = header.indexOfFirst { it.contains("备注") }.takeIf { it >= 0 } ?: 4
        val needCols = maxOf(idxDate, idxType, idxCategory, idxAmount) + 1

        val rows = mutableListOf<Row>()
        var skipped = 0
        var firstError: String? = null
        fun skip(rowNumber: Int, reason: String) {
            skipped++
            if (firstError == null) firstError = "第 $rowNumber 行$reason"
        }

        body.forEach { raw ->
            if (raw.cells.none { it.text.isNotBlank() }) return@forEach
            if (raw.cells.size < needCols) {
                skip(raw.number, "缺少列")
                return@forEach
            }
            val type = when (raw.cells[idxType].text.trim()) {
                "支出" -> RecordType.EXPENSE
                "收入" -> RecordType.INCOME
                else -> null
            }
            if (type == null) {
                skip(raw.number, "类型无法识别（应为支出或收入）")
                return@forEach
            }
            val date = cellDate(raw.cells[idxDate])
            if (date == null) {
                skip(raw.number, "日期格式不对（应如 2026-11-05）")
                return@forEach
            }
            val amount = cellAmount(raw.cells[idxAmount])
            if (amount == null) {
                skip(raw.number, "金额无法识别")
                return@forEach
            }
            val categoryCell = raw.cells[idxCategory].text.trim()
            rows.add(
                Row(
                    date = date,
                    type = type,
                    categoryName = categoryCell.substringAfter(' ').trim().ifEmpty { categoryCell },
                    amountMinor = amount,
                    note = raw.cells.getOrNull(idxNote)?.text?.trim().orEmpty(),
                )
            )
        }
        return Parsed(rows, skipped, firstError)
    }

    private fun cellDate(cell: XlsxParser.Cell): LocalDate? {
        val text = cell.text.trim()
        if (text.isEmpty()) return null
        if (cell.numeric) {
            val serial = text.toBigDecimalOrNull() ?: return null
            return runCatching {
                LocalDate.of(1899, 12, 30).plusDays(serial.toLong())
            }.getOrNull()
        }
        return parseDateText(text)
    }

    /** 文本日期：2026-11-05 / 2026/11/5 / 2026.11.5 */
    private fun parseDateText(text: String): LocalDate? {
        runCatching { LocalDate.parse(text) }.getOrNull()?.let { return it }
        val m = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""").find(text) ?: return null
        return runCatching {
            LocalDate.of(
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt(),
                m.groupValues[3].toInt(),
            )
        }.getOrNull()
    }

    /** 金额：数值单元格或文本（可含 ¥ / 千分位逗号），统一取绝对值转成分 */
    private fun cellAmount(cell: XlsxParser.Cell): Long? {
        val raw = cell.text.trim()
            .removePrefix("¥").removePrefix("￥")
            .removeSuffix("元")
            .replace(",", "").replace("，", "").replace(" ", "")
        if (raw.isEmpty()) return null
        return runCatching {
            BigDecimal(raw).abs()
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2).longValueExact()
        }.getOrNull()?.takeIf { it > 0 }
    }

    /** 按 CSV 规则切行：引号内的逗号与换行不切分，"" 表示一个引号 */
    private fun splitRecords(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var cells = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> {
                    cells.add(sb.toString()); sb.clear()
                }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    cells.add(sb.toString()); sb.clear()
                    if (cells.any { it.isNotEmpty() }) records.add(cells)
                    cells = mutableListOf()
                }
                else -> sb.append(c)
            }
            i++
        }
        cells.add(sb.toString())
        if (cells.any { it.isNotEmpty() }) records.add(cells)
        return records
    }

    private fun escape(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }

    companion object {
        /** 导出表头；同时作为 CSV 模板表头，改这里两边一起变 */
        private const val HEADER = "日期,类型,分类,金额(元),备注"

        /** 模板示例行：分类取默认分类「餐饮」，金额为正数，类型决定收支 */
        private const val TEMPLATE_ROW = "2026-11-05,支出,餐饮,25.80,午饭"

        const val CSV_TEMPLATE_NAME = "简记导入模板.csv"
        const val CSV_MIME = "text/csv"

        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        /** 导出文件名建议值：jianji-export-20261104.csv */
        fun suggestedCsvName(): String =
            "jianji-export-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".csv"

        /** xlsx 本质是 zip，以 PK\003\004 开头 */
        private fun isZip(bytes: ByteArray): Boolean =
            bytes.size > 3 && bytes[0] == 'P'.code.toByte() &&
                bytes[1] == 'K'.code.toByte() && bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

        /** 老式 .xls 是 OLE 复合文档，D0 CF 11 E0 */
        private fun isOle(bytes: ByteArray): Boolean =
            bytes.size > 4 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() &&
                bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte()
    }
}
