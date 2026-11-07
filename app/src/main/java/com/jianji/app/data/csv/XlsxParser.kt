package com.jianji.app.data.csv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 轻量 XLSX 读取：xlsx = zip + xml，只用系统内置 ZipInputStream + XmlPullParser，
 * 不引入 Apache POI。只读第一个工作表，返回从第一行开始的所有单元格文本。
 */
object XlsxParser {

    /** 单元格：numeric 表示原始值是数值（日期序列值、金额数字） */
    data class Cell(val text: String, val numeric: Boolean = false)

    data class SheetRow(val number: Int, val cells: List<Cell>)

    fun readFirstSheet(input: InputStream): List<SheetRow> {
        var sharedBytes: ByteArray? = null
        var workbookBytes: ByteArray? = null
        var relsBytes: ByteArray? = null
        val sheets = LinkedHashMap<String, ByteArray>()

        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                when {
                    name == "xl/sharedStrings.xml" -> sharedBytes = zip.readBytes()
                    name == "xl/workbook.xml" -> workbookBytes = zip.readBytes()
                    name == "xl/_rels/workbook.xml.rels" -> relsBytes = zip.readBytes()
                    name.startsWith("xl/worksheets/") && name.endsWith(".xml") ->
                        sheets[name] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }

        val shared = sharedBytes?.let(::parseSharedStrings).orEmpty()
        val sheetBytes = resolveFirstSheet(workbookBytes, relsBytes, sheets) ?: return emptyList()
        return parseSheet(sheetBytes, shared)
    }

    /** 通过 workbook.xml 的首个 sheet r:id + rels 定位文件；解析不出来就退回 sheet1.xml */
    private fun resolveFirstSheet(
        workbook: ByteArray?,
        rels: ByteArray?,
        sheets: Map<String, ByteArray>,
    ): ByteArray? {
        val relId = workbook?.let(::firstSheetRelId)
        if (relId != null && rels != null) {
            val target = parseRels(rels)[relId]
            if (target != null) {
                val path = when {
                    target.startsWith("/") -> target.removePrefix("/")
                    target.startsWith("xl/") -> target
                    else -> "xl/$target"
                }.replace("../", "")
                sheets[path]?.let { return it }
            }
        }
        return sheets["xl/worksheets/sheet1.xml"] ?: sheets.values.firstOrNull()
    }

    private fun firstSheetRelId(bytes: ByteArray): String? {
        val parser = Xml.newPullParser()
        parser.setInput(bytes.inputStream(), null)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
                // 未开启命名空间处理时属性名带前缀，两种都试
                return parser.getAttributeValue(null, "r:id")
                    ?: parser.getAttributeValue(
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                        "id",
                    )
            }
            parser.next()
        }
        return null
    }

    private fun parseRels(bytes: ByteArray): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val parser = Xml.newPullParser()
        parser.setInput(bytes.inputStream(), null)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id")
                val target = parser.getAttributeValue(null, "Target")
                if (id != null && target != null) map[id] = target
            }
            parser.next()
        }
        return map
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val list = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setInput(bytes.inputStream(), null)
        var inSi = false
        val sb = StringBuilder()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> { inSi = true; sb.setLength(0) }
                    "t" -> if (inSi) sb.append(parser.nextText())
                }
                XmlPullParser.END_TAG -> if (parser.name == "si") {
                    list.add(sb.toString())
                    inSi = false
                }
            }
            parser.next()
        }
        return list
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<SheetRow> {
        val rows = mutableListOf<SheetRow>()
        val parser = Xml.newPullParser()
        parser.setInput(bytes.inputStream(), null)
        var rowNumber = 0
        var cells: MutableList<Cell>? = null
        var colIndex = -1
        var cellType: String? = null
        var numeric = true
        var value = ""

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> {
                        rowNumber = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: (rowNumber + 1)
                        cells = mutableListOf()
                    }
                    "c" -> {
                        colIndex = columnIndex(parser.getAttributeValue(null, "r"))
                        cellType = parser.getAttributeValue(null, "t")
                        numeric = cellType == null || cellType == "n"
                        value = ""
                    }
                    "v" -> if (colIndex >= 0) {
                        value = parser.nextText()
                    }
                    "t" -> if (cellType == "inlineStr") {
                        value = parser.nextText()
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "c" -> {
                        if (colIndex >= 0) {
                            val text = when (cellType) {
                                "s" -> shared.getOrElse(value.trim().toIntOrNull() ?: -1) { "" }
                                else -> value
                            }
                            val row = cells
                            if (row != null) {
                                while (row.size <= colIndex) row.add(Cell(""))
                                row[colIndex] = Cell(text, numeric && text.isNotBlank())
                            }
                        }
                        colIndex = -1
                        cellType = null
                    }
                    "row" -> {
                        cells?.let { if (it.any { c -> c.text.isNotBlank() }) rows.add(SheetRow(rowNumber, it)) }
                        cells = null
                    }
                }
            }
            parser.next()
        }
        return rows
    }

    /** "B12" → 1；无法解析时返回 -1 */
    private fun columnIndex(ref: String?): Int {
        if (ref.isNullOrEmpty()) return -1
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break
        }
        return if (n > 0) n - 1 else -1
    }
}
