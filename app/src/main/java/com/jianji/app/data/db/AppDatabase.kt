package com.jianji.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jianji.app.data.DefaultCategories
import com.jianji.app.data.dao.CategoryDao
import com.jianji.app.data.dao.RecordDao
import com.jianji.app.data.entity.CategoryEntity
import com.jianji.app.data.entity.RecordEntity
import com.jianji.app.data.model.RecordType

@Database(
    entities = [RecordEntity::class, CategoryEntity::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordDao(): RecordDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 先读出旧自定义分类；旧内置分类按固定映射迁移，自定义分类保留为一级分类。
                val customCategories = mutableListOf<CategoryEntity>()
                database.query(
                    "SELECT id, name, emoji, type, sortOrder, updatedAtEpochMs, isDeleted FROM categories",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        if (id !in DefaultCategories.legacyCategoryMap && cursor.getInt(6) == 0) {
                            customCategories.add(
                                CategoryEntity(
                                    id = id,
                                    name = cursor.getString(1),
                                    emoji = cursor.getString(2),
                                    type = RecordType.valueOf(cursor.getString(3)),
                                    sortOrder = cursor.getInt(4),
                                    updatedAtEpochMs = cursor.getLong(5),
                                ),
                            )
                        }
                    }
                }

                database.execSQL("ALTER TABLE categories ADD COLUMN parentId TEXT")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_categories_parentId ON categories(parentId)",
                )

                fun insertMissing(cat: CategoryEntity) {
                    database.execSQL(
                        "INSERT OR IGNORE INTO categories " +
                            "(id, name, emoji, type, sortOrder, updatedAtEpochMs, isDeleted, parentId) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            cat.id, cat.name, cat.emoji, cat.type.name, cat.sortOrder,
                            cat.updatedAtEpochMs, if (cat.isDeleted) 1 else 0, cat.parentId,
                        ),
                    )
                }

                DefaultCategories.list().forEach(::insertMissing)
                val now = System.currentTimeMillis()
                DefaultCategories.legacyCategoryMap.forEach { (oldId, newId) ->
                    database.execSQL(
                        "UPDATE records SET categoryId = ?, updatedAtEpochMs = ? WHERE categoryId = ?",
                        arrayOf(newId, now, oldId),
                    )
                    database.execSQL(
                        "UPDATE categories SET isDeleted = 1, updatedAtEpochMs = ? WHERE id = ?",
                        arrayOf(now, oldId),
                    )
                }
                customCategories.forEach { parent ->
                    val child = parent.copy(
                        id = "cat-legacy-${parent.id.removePrefix("cat-")}-other",
                        name = "其它",
                        sortOrder = 0,
                        updatedAtEpochMs = now,
                        parentId = parent.id,
                    )
                    insertMissing(child)
                    database.execSQL(
                        "UPDATE records SET categoryId = ?, updatedAtEpochMs = ? WHERE categoryId = ?",
                        arrayOf(child.id, now, parent.id),
                    )
                }
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "jianji.db")
                .addMigrations(MIGRATION_1_2)
                .build()
        // 预置分类在 AppContainer 启动时幂等补齐，避免覆盖用户修改。
    }
}
