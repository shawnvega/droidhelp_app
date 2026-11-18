package com.bighelp.droidhelp.session

import com.bighelp.droidhelp.platform.SqliteVersionedDatabase
import com.bighelp.droidhelp.platform.VersionedDatabase
import java.io.File

/**
 * A journal that persists submission intent before dispatch.
 */
class SubmissionJournal(storageFile: File) : Journal by SubmissionJournalImpl(SqliteVersionedDatabase(storageFile))
