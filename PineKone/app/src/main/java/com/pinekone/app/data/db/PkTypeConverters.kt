package com.pinekone.app.data.db

import androidx.room.TypeConverter
import java.time.Instant

class PkTypeConverters {
    @TypeConverter
    fun fromInstant(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter
    fun toInstant(value: Long?): Instant? = value?.let { Instant.ofEpochMilli(it) }
}
