package com.jianji.app.data.sync

import com.jianji.app.data.entity.CategoryEntity
import com.jianji.app.data.entity.RecordEntity
import com.jianji.app.data.model.RecordType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** SnapshotV1 单条记账记录，字段名与服务端 validate.py 的契约一致。 */
@Serializable
data class SnapshotRecordV1(
    val id: String,
    val type: String,
    val amountMinor: Long,
    val categoryId: String,
    val dateEpochDay: Int,
    val note: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val isDeleted: Boolean,
)

/** SnapshotV1 分类；parentId 为 null 表示一级分类。 */
@Serializable
data class SnapshotCategoryV1(
    val id: String,
    val name: String,
    val emoji: String,
    val type: String,
    val parentId: String? = null,
    val sortOrder: Int,
    val updatedAtEpochMs: Long,
    val isDeleted: Boolean,
)

/** App 推送到服务端的完整账本快照。 */
@Serializable
data class SnapshotV1(
    // 不设默认值：encodeDefaults=false 时也必须始终提交协议版本。
    val version: Int,
    val exportedAtEpochMs: Long,
    val categories: List<SnapshotCategoryV1>,
    val records: List<SnapshotRecordV1>,
)

/** SnapshotV1 的唯一序列化入口，避免不同调用点产生不一致的 JSON。 */
object SnapshotCodec {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        // 与服务端 canonical.py 的 get() 规则一致：parentId=null 与缺失键等价。
        explicitNulls = false
    }

    fun build(
        records: List<RecordEntity>,
        categories: List<CategoryEntity>,
        exportedAtEpochMs: Long,
    ): SnapshotV1 = SnapshotV1(
        version = 1,
        exportedAtEpochMs = exportedAtEpochMs,
        records = records.map {
            SnapshotRecordV1(
                id = it.id,
                type = it.type.name,
                amountMinor = it.amountMinor,
                categoryId = it.categoryId,
                dateEpochDay = it.dateEpochDay,
                note = it.note,
                createdAtEpochMs = it.createdAtEpochMs,
                updatedAtEpochMs = it.updatedAtEpochMs,
                isDeleted = it.isDeleted,
            )
        },
        categories = categories.map {
            SnapshotCategoryV1(
                id = it.id,
                name = it.name,
                emoji = it.emoji,
                type = it.type.name,
                parentId = it.parentId,
                sortOrder = it.sortOrder,
                updatedAtEpochMs = it.updatedAtEpochMs,
                isDeleted = it.isDeleted,
            )
        },
    )

    fun encode(snapshot: SnapshotV1): ByteArray = json.encodeToString(snapshot).encodeToByteArray()

    fun decode(bytes: ByteArray): SnapshotV1 = json.decodeFromString(bytes.decodeToString())

    /** 云端快照 → 本地实体；RecordType 等字段非法时抛 IllegalArgumentException。 */
    fun toEntities(snapshot: SnapshotV1): Pair<List<RecordEntity>, List<CategoryEntity>> =
        snapshot.records.map {
            RecordEntity(
                id = it.id,
                type = RecordType.valueOf(it.type),
                amountMinor = it.amountMinor,
                categoryId = it.categoryId,
                dateEpochDay = it.dateEpochDay,
                note = it.note,
                createdAtEpochMs = it.createdAtEpochMs,
                updatedAtEpochMs = it.updatedAtEpochMs,
                isDeleted = it.isDeleted,
            )
        } to snapshot.categories.map {
            CategoryEntity(
                id = it.id,
                name = it.name,
                emoji = it.emoji,
                type = RecordType.valueOf(it.type),
                sortOrder = it.sortOrder,
                updatedAtEpochMs = it.updatedAtEpochMs,
                isDeleted = it.isDeleted,
                parentId = it.parentId,
            )
        }

    /**
     * 账本内容指纹：与上传内容一致但排除 exportedAt 时间戳，
     * 同一账本数据无论何时构建都得到相同值，用于自动同步的"是否有未同步修改"判断。
     */
    fun fingerprint(records: List<RecordEntity>, categories: List<CategoryEntity>): Long =
        encode(build(records, categories, exportedAtEpochMs = 0L)).contentHashCode().toLong()
}
