package com.jianji.app.data.db

import androidx.room.TypeConverter
import com.jianji.app.data.model.RecordType

class Converters {
    @TypeConverter
    fun recordTypeToString(t: RecordType): String = t.name

    @TypeConverter
    fun stringToRecordType(s: String): RecordType = RecordType.valueOf(s)
}
