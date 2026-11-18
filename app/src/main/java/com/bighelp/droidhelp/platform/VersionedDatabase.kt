package com.bighelp.droidhelp.platform

import java.io.File

/**
 * A versioned key-value store.
 */
interface VersionedDatabase {
    fun insert(key: String, value: String): Int
    fun retrieve(key: String, version: Int? = null): String?
    fun queryByVersion(key: String, startVersion: Int, endVersion: Int): List<String>
    fun delete(key: String)
    fun listVersions(key: String): List<Int>
    fun listKeys(): Set<String>
}

/**
 * A file-based implementation of [VersionedDatabase] for testing and simplicity.
 * Each entry is stored as a line: key|version|value
 */
class FileBasedVersionedDatabase(private val file: File) : VersionedDatabase {

    private data class Record(val key: String, val version: Int, val value: String)

    private var records = mutableListOf<Record>()

    init {
        if (file.exists()) {
            file.readLines().forEach { line ->
                val parts = line.split("|", limit = 3)
                if (parts.size == 3) {
                    records.add(Record(parts[0], parts[1].toInt(), parts[2]))
                }
            }
        }
    }

    private fun save() {
        val content = records.joinToString("\n") { "${it.key}|${it.version}|${it.value}" }
        file.writeText(content)
    }

    override fun insert(key: String, value: String): Int {
        val lastVersion = listVersions(key).lastOrNull() ?: 0
        val newVersion = lastVersion + 1
        records.add(Record(key, newVersion, value))
        save()
        return newVersion
    }

    override fun retrieve(key: String, version: Int?): String? {
        return if (version == null) {
            val lastVersion = listVersions(key).lastOrNull()
            if (lastVersion != null) {
                records.find { it.key == key && it.version == lastVersion }?.value
            } else null
        } else {
            records.find { it.key == key && it.version == version }?.value
        }
    }

    override fun queryByVersion(key: String, startVersion: Int, endVersion: Int): List<String> {
        return records.filter { it.key == key && it.version in startVersion..endVersion }.map { it.value }
    }

    override fun delete(key: String) {
        records.removeIf { it.key == key }
        save()
    }

    override fun listVersions(key: String): List<Int> {
        return records.filter { it.key == key }.map { it.version }.sorted()
    }

    override fun listKeys(): Set<String> {
        return records.map { it.key }.toSet()
    }
}
