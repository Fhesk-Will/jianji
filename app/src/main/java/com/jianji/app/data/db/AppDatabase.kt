package com.jianji.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.jianji.app.data.dao.CategoryDao
import com.jianji.app.data.dao.RecordDao
import com.jianji.app.data.entity.CategoryEntity
import com.jianji.app.data.entity.RecordEntity

@Database(
    entities = [RecordEntity::class, CategoryEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordDao(): RecordDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "jianji.db")
                .build()
        // 注意：预置分类的播种在 AppContainer.seedIfEmpty() 完成（确定性优于 onCreate 回调竞态）
    }
}
