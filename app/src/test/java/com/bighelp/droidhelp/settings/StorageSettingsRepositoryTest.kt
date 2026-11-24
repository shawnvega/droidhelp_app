package com.bighelp.droidhelp.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class StorageSettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: StorageSettingsRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        repository = StorageSettingsRepository(context)
        
        // Clear everything before each test to ensure a clean state
        repository.clearCache()
        repository.clearData()
    }

    @Test
    fun `test getCacheSize`() {
        val file = File(context.cacheDir, "test_cache_file")
        file.writeText("hello")
        
        val size = repository.getCacheSize()
        assertEquals(file.length(), size)
    }

    @Test
    fun `test getDataUsage`() {
        val cacheFile = File(context.cacheDir, "test_cache_file")
        cacheFile.writeText("cache")
        
        val filesFile = File(context.filesDir, "test_files_file")
        filesFile.writeText("files")
        
        val expectedSize = cacheFile.length() + filesFile.length()
        assertEquals(expectedSize, repository.getDataUsage())
    }

    @Test
    fun `test clearCache`() {
        val file = File(context.cacheDir, "test_cache_file")
        file.writeText("cache")
        
        repository.clearCache()
        
        assertEquals(0L, repository.getCacheSize())
        assertEquals(false, file.exists())
    }

    @Test
    fun `test clearData`() {
        val cacheFile = File(context.cacheDir, "test_cache_file")
        cacheFile.writeText("cache")
        
        val filesFile = File(context.filesDir, "test_files_file")
        filesFile.writeText("files")
        
        repository.clearData()
        
        assertEquals(0L, repository.getCacheSize())
        assertEquals(false, cacheFile.exists())
        assertEquals(false, filesFile.exists())
    }
}
