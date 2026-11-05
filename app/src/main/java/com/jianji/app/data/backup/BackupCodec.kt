package com.jianji.app.data.backup

import com.jianji.app.data.entity.CategoryEntity
import com.jianji.app.data.entity.RecordEntity
import com.jianji.app.data.model.RecordType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 备份 JSON 编解码。
 * 含软删除墓碑记录 —— 与数据库完整状态一一对应，
 * 恢复 = 按 id 幂等合并，天然兼容未来的同步层。
 */
@Serializable
data class RecordDto(
    val id: String,
    val type: RecordType,
    val amountMinor: Long,
    val categoryId: String,
    val dateEpochDay: Int,
    val note: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val isDeleted: Boolean,
) {
    fun toEntity() = RecordEntity(
        id, type, amountMinor, categoryId, dateEpochDay, note,
        createdAtEpochMs, updatedAtEpochMs, isDeleted,
    )

    companion object {
        fun from(e: RecordEntity) = RecordDto(
            e.id, e.type, e.amountMinor, e.categoryId, e.dateEpochDay, e.note,
            e.createdAtEpochMs, e.updatedAtEpochMs, e.isDeleted,
        )
    }
}

@Serializable
data class CategoryDto(
    val id: String,
    val name: String,
    val emoji: String,
    val type: RecordType,
    val sortOrder: Int,
    val updatedAtEpochMs: Long,
    val isDeleted: Boolean,
) {
    fun toEntity() = CategoryEntity(
        id, name, emoji, type, sortOrder, updatedAtEpochMs, isDeleted,
    )

    companion object {
        fun from(e: CategoryEntity) = CategoryDto(
            e.id, e.name, e.emoji, e.type, e.sortOrder, e.updatedAtEpochMs, e.isDeleted,
        )
    }
}

@Serializable
data class SnapshotV1(
    val version: Int = 1,
    val exportedAtEpochMs: Long,
    val categories: List<CategoryDto>,
    val records: List<RecordDto>,
)

object BackupCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true   // 前向兼容：未来新增字段不炸旧版本
        encodeDefaults = true
    }

    fun encode(s: SnapshotV1): String = json.encodeToString(SnapshotV1.serializer(), s)

    fun decode(text: String): SnapshotV1 = json.decodeFromString(SnapshotV1.serializer(), text)
}
