package com.bighelp.droidhelp.session

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun main() {
    val map = mapOf("key" to "value")
    val jsonStr = Json.encodeToString(map)
    println("Serialized: $jsonStr")
    val deserialized = Json.decodeFromString<Map<String, String>>(jsonStr)
    println("Deserialized: $deserialized")
    if (map == deserialized) {
        println("SUCCESS")
    } else {
        println("FAILURE")
    }
}
