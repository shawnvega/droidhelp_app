package com.bighelp.droidhelp.settings

import android.content.Context
import java.io.File

class StorageSettingsRepository(private val context: Context) {
    fun getCacheSize(): Long {
        return getDirectorySize(context.cacheDir)
    }

    fun getDataUsage(): Long {
        return getDirectorySize(context.cacheDir) + getDirectorySize(context.filesDir)
    }

    fun clearCache() {
        context.cacheDir.deleteRecursively()
    }

    fun clearData() {
        context.cacheDir.deleteRecursively()
        context.filesDir.deleteRecursively()
    }

    private fun getDirectorySize(directory: File): Long {
        return directory.walk()
            .filter { it.isFile }
            .sumOf { it.length() }
    }
}
