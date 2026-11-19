package com.droidhelp.persistence

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Type converters for Room database.
 * Handles conversion between complex types and database-compatible types.
 */
class JournalConverters {
    private val gson = Gson()
    
    @TypeConverter
    fun mapToJson(map: Map<String, String>?): String? {
        return if (map == null || map.isEmpty()) null else gson.toJson(map)
    }
    
    @TypeConverter
    fun jsonToMap(json: String?): Map<String, String> {
        return if (json == null || json.isEmpty()) emptyMap()
        else gson.fromJson(json, object : TypeToken<Map<String, String>>() {}.type)
    }
    
    @TypeConverter
    fun journalTypeToString(type: JournalType): String {
        return type.name
    }
    
    @TypeConverter
    fun stringToJournalType(type: String): JournalType {
        return JournalType.valueOf(type)
    }
}