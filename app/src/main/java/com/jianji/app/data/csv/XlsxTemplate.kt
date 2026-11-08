package com.jianji.app.data.csv

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 生成导入模板：手写最小 .xlsx（zip + xml），不引第三方库。
 * 表头与 [CsvManager] 的解析规则一致，第二行给一条示例；
 * 全部用 inlineStr 文本单元格，金额用数值单元格，与导入解析的读取路径相同。
 */
object XlsxTemplate {

    const val FILE_NAME = "简记导入模板.xlsx"

    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    fun write(out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            put(zip, "[Content_Types].xml", CONTENT_TYPES)
            put(zip, "_rels/.rels", ROOT_RELS)
            put(zip, "xl/workbook.xml", WORKBOOK)
            put(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            put(zip, "xl/styles.xml", STYLES)
            put(zip, "xl/worksheets/sheet1.xml", SHEET)
        }
    }

    private fun put(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private const val CONTENT_TYPES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            "</Types>"

    private const val ROOT_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"

    private const val WORKBOOK =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheets><sheet name=\"账单\" sheetId=\"1\" r:id=\"rId1\"/></sheets>" +
            "</workbook>"

    private const val WORKBOOK_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            "</Relationships>"

    private const val STYLES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>" +
            "<fill><patternFill patternType=\"gray125\"/></fill></fills>" +
            "<borders count=\"1\"><border/></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>" +
            "</styleSheet>"

    private const val SHEET =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
            "<row r=\"1\">" +
            "<c r=\"A1\" t=\"inlineStr\"><is><t>日期</t></is></c>" +
            "<c r=\"B1\" t=\"inlineStr\"><is><t>类型</t></is></c>" +
            "<c r=\"C1\" t=\"inlineStr\"><is><t>一级分类</t></is></c>" +
            "<c r=\"D1\" t=\"inlineStr\"><is><t>二级分类</t></is></c>" +
            "<c r=\"E1\" t=\"inlineStr\"><is><t>金额(元)</t></is></c>" +
            "<c r=\"F1\" t=\"inlineStr\"><is><t>备注</t></is></c>" +
            "</row>" +
            "<row r=\"2\">" +
            "<c r=\"A2\" t=\"inlineStr\"><is><t>2026-11-05</t></is></c>" +
            "<c r=\"B2\" t=\"inlineStr\"><is><t>支出</t></is></c>" +
            "<c r=\"C2\" t=\"inlineStr\"><is><t>饮食</t></is></c>" +
            "<c r=\"D2\" t=\"inlineStr\"><is><t>餐饮</t></is></c>" +
            "<c r=\"E2\"><v>25.8</v></c>" +
            "<c r=\"F2\" t=\"inlineStr\"><is><t>午饭</t></is></c>" +
            "</row>" +
            "</sheetData></worksheet>"
}
