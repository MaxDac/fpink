package com.fpink.capture.acceptance

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.fpink.capture.data.CredentialCipher
import com.fpink.capture.data.ImageImportStore
import com.fpink.capture.data.SettingsStore
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

internal class AcceptanceStorage : AutoCloseable {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    val id: String = UUID.randomUUID().toString()
    val root = File(target.cacheDir, "acceptance-$id").apply { check(mkdirs()) }
    val context = object : ContextWrapper(target) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
    }
    val alias = "fpink.acceptance.$id"
    val settingsFile = File(root, "acceptance.preferences_pb")
    private val job = SupervisorJob()
    val preferences = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(job + Dispatchers.IO),
        produceFile = { settingsFile },
    )
    val settings = SettingsStore(preferences, CredentialCipher(alias))
    val images = ImageImportStore(context)

    override fun close() {
        runBlocking { job.cancelAndJoin() }
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(alias)) deleteEntry(alias)
        }
        check(root.deleteRecursively()) { "Could not remove test-owned storage" }
    }
}
