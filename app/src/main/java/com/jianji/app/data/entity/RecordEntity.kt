package com.jianji.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jianji.app.data.model.RecordType

/**
 * 一条记账记录。
 *
 * 同步口子（为将来多端 / 服务端预留，当前成本≈0）：
 *  - id 用客户端生成的 UUID，不用自增
 *  - updatedAtEpochMs 每次修改都刷新
 *  - 删除是软删除（isDeleted = true），全量导出包含墓碑记录
 * 备份 / 恢复按 id 幂等合并（REPLACE），天然兼容未来的同步层。
 */
@Entity(
    tableName = "records",
    indices = [
        Index("categoryId"),
        Index("dateEpochDay"),
        Index("updatedAtEpochMs"),
    ],
)
data class RecordEntity(
    @PrimaryKey val id: String,
    val type: RecordType,
    /** 金额，单位：分（避免浮点误差） */
    val amountMinor: Long,
    val categoryId: String,
    /** 记账日期（业务日期），LocalDate.toEpochDay() */
    val dateEpochDay: Int,
    val note: String,
    /** 录入时间 */
    val createdAtEpochMs: Long,
    /** 最后修改时间（同步用） */
    val updatedAtEpochMs: Long,
    /** 软删除标记 */
    val isDeleted: Boolean = false,
)
