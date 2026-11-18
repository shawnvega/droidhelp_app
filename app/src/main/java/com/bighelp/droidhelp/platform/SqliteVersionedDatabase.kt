package com.bighelp.droidhelp.platform

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * A versioned key-value store using SQLite.
 */
class SqliteVersionedDatabase(private val dbFile: File) : VersionedDatabase {

    private val connection: Connection by lazy {
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        val conn = DriverManager.getConnection(url)
        conn.createStatement().use { stmt ->
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS versioned_records (
                    key TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    value TEXT NOT NULL,
                    PRIMARY KEY (key, version)
                )
            """.trimIndent())
        }
        conn
    }

    override fun insert(key: String, value: String): Int {
        connection.autoCommit = false
        try {
            val lastVersion = listVersions(key).lastOrNull() ?: 0
            val newVersion = lastVersion + 1
            connection.prepareStatement("INSERT INTO versioned_records (key, version, value) VALUES (?, ?, ?)").use { pstmt ->
                pstmt.setString(1, key)
                pstmt.setInt(2, newVersion)
                pstmt.setString(3, value)
                pstmt.executeUpdate()
            }
            connection.commit()
            return newVersion
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    override fun retrieve(key: String, version: Int?): String? {
        val sql = if (version == null) {
            "SELECT value FROM versioned_records WHERE key = ? ORDER BY version DESC LIMIT 1"
        } else {
            "SELECT value FROM versioned_records WHERE key = ? AND version = ?"
        }

        return connection.prepareStatement(sql).use { pstmt ->
            if (version == null) {
                pstmt.setString(1, key)
            } else {
                pstmt.setString(1, key)
                pstmt.setInt(2, version)
            }
            val rs = pstmt.executeQuery()
            if (rs.next()) rs.getString("value") else null
        }
    }

    override fun queryByVersion(key: String, startVersion: Int, endVersion: Int): List<String> {
        val results = mutableListOf<String>()
        connection.prepareStatement("SELECT value FROM versioned_records WHERE key = ? AND version BETWEEN ? AND ?").use { pstmt ->
            pstmt.setString(1, key)
            pstmt.setInt(2, startVersion)
            pstmt.setInt(3, endVersion)
            val rs = pstmt.executeQuery()
            while (rs.next()) {
                results.add(rs.getString("value"))
            }
        }
        return results
    }

    override fun delete(key: String) {
        connection.autoCommit = false
        try {
            connection.prepareStatement("DELETE FROM versioned_records WHERE key = ?").use { pstmt ->
                pstmt.setString(1, key)
                pstmt.executeUpdate()
            }
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    override fun listVersions(key: String): List<Int> {
        val versions = mutableListOf<Int>()
        connection.prepareStatement("SELECT version FROM versioned_records WHERE key = ? ORDER BY version ASC").use { pstmt ->
            pstmt.setString(1, key)
            val rs = pstmt.executeQuery()
            while (rs.next()) {
                versions.add(rs.getInt("version"))
            }
        }
        return versions
    }

    override fun listKeys(): Set<String> {
        val keys = mutableSetOf<String>()
        connection.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT DISTINCT key FROM versioned_records")
            while (rs.next()) {
                keys.add(rs.getString("key"))
            }
        }
        return keys
    }
}
