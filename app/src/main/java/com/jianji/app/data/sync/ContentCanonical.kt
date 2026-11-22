package com.jianji.app.data.sync

import java.security.MessageDigest

/**
 * SnapshotV1 内容指纹，与服务端 sync/app/canonical.py 逐字节对齐。
 *
 * 两条记录块的字段按固定顺序拼接，updatedAt 一律排除；记录与分类分别按
 * id 的 UTF-8 字节序排序。任何规则改动都必须双端同提交并更新 golden fixture。
 */
object ContentCanonical {

    private const val FIELD_SEPARATOR = '\u001F'
    private const val ROW_SEPARATOR = '\u001E'
    private const val BLOCK_SEPARATOR = '\u001C'

    private val recordFields = listOf(
        "amountMinor", "categoryId", "dateEpochDay", "id",
        "isDeleted", "note", "type", "createdAtEpochMs",
    )
    private val categoryFields = listOf(
        "emoji", "id", "isDeleted", "name", "parentId", "sortOrder", "type",
    )

    fun hash(snapshot: SnapshotV1): String {
        val canonical = recordBlock(snapshot.records) +
            BLOCK_SEPARATOR +
            categoryBlock(snapshot.categories)
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun recordBlock(records: List<SnapshotRecordV1>): String =
        records
            .sortedWith(::compareUtf8Ids)
            .joinToString(ROW_SEPARATOR.toString()) {
                fieldRow(
                    listOf(
                        it.amountMinor, it.categoryId, it.dateEpochDay, it.id,
                        it.isDeleted, it.note, it.type, it.createdAtEpochMs,
                    ),
                )
            }

    private fun categoryBlock(categories: List<SnapshotCategoryV1>): String =
        categories
            .sortedWith(::compareUtf8Ids)
            .joinToString(ROW_SEPARATOR.toString()) {
                fieldRow(
                    listOf(
                        it.emoji, it.id, it.isDeleted, it.name,
                        it.parentId, it.sortOrder, it.type,
                    ),
                )
            }

    private fun fieldRow(values: List<Any?>): String =
        values.joinToString(FIELD_SEPARATOR.toString()) { canonicalValue(it) }

    private fun canonicalValue(value: Any?): String = when (value) {
        null -> ""
        is Boolean -> value.toString()
        else -> value.toString()
    }

    /** Python canonical.py 按 UTF-8 字节序排序，这里显式按无符号字节比较。 */
    private fun compareUtf8Ids(a: Any, b: Any): Int {
        val left = idOf(a).encodeToByteArray()
        val right = idOf(b).encodeToByteArray()
        val common = minOf(left.size, right.size)
        for (i in 0 until common) {
            val l = left[i].toInt() and 0xFF
            val r = right[i].toInt() and 0xFF
            if (l != r) return l - r
        }
        return left.size - right.size
    }

    private fun idOf(value: Any): String = when (value) {
        is SnapshotRecordV1 -> value.id
        is SnapshotCategoryV1 -> value.id
        else -> error("snapshot item 必须是记录或分类")
    }
}
