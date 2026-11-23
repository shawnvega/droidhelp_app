package com.bighelp.droidhelp.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsScreenTest {

    @Test
    fun testSettingsSectionEntriesOrderAndTitles() {
        val expected = listOf(
            SettingsSection.ACCOUNT to "Account",
            SettingsSection.BOTS to "Bots",
            SettingsSection.NOTIFICATIONS to "Notifications",
            SettingsSection.APPEARANCE to "Appearance",
            SettingsSection.PRIVACY to "Privacy",
            SettingsSection.STORAGE to "Storage",
            SettingsSection.ABOUT to "About"
        )

        assertEquals("Size mismatch", expected.size, SettingsSection.entries.size)

        expected.forEachIndexed { index, (section, title) ->
            assertEquals("Title mismatch at index $index", title, section.title)
            assertEquals("Order mismatch at index $index", section, SettingsSection.entries[index])
        }
    }
}
