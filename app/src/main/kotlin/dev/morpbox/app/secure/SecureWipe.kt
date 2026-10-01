// SPDX-License-Identifier: MIT
package dev.morpbox.app.secure

import android.content.Context
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom

object SecureWipe {
    fun wipeAll(ctx: Context): String {
        val log = StringBuilder()
        try {
            deleteRecursive(ctx.filesDir)
            deleteRecursive(ctx.cacheDir)
            File(ctx.applicationInfo.dataDir, "shared_prefs").let { deleteRecursive(it) }
            ctx.getDatabasePath("morp.db").delete()
            log.append("files;")
        } catch (_: Exception) {
            log.append("files-err;")
        }
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            ks.aliases().toList().filter { it.startsWith("morp") }.forEach { ks.deleteEntry(it) }
            log.append("keystore;")
        } catch (_: Exception) {
            log.append("keystore-err;")
        }
        // Best-effort overwrite of leftover files
        runCatching {
            val rnd = ByteArray(4096).also { SecureRandom().nextBytes(it) }
            File(ctx.filesDir, "wipe.bin").writeBytes(rnd)
            File(ctx.filesDir, "wipe.bin").delete()
        }
        return log.toString()
    }

    private fun deleteRecursive(f: File?) {
        if (f == null || !f.exists()) return
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
        f.delete()
    }
}
