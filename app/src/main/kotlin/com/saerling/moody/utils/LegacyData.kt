// LegacyData.kt
// installs from before the rename kept their library, settings, link pairings and keystore key under the old
// name. this moves the files once so nothing is lost; the old prefix lives only here.

package com.saerling.moody.utils

import android.content.Context
import java.io.File

object LegacyData {
    // written in two parts so a find-and-replace of the old name never turns it into the new one
    internal val OldPrefix = "mo" + "chy"
    internal const val NewPrefix = "moody"

    @Volatile private var done = false

    // renames databases/old*.db(-wal, -shm) and shared_prefs/old*.xml to the new name, when the new one isn't there yet
    fun migrate(context: Context) {
        if (done) return
        synchronized(this) {
            if (done) return
            runCatching {
                val data = context.applicationInfo.dataDir?.let(::File)
                listOfNotNull(
                    context.getDatabasePath("x").parentFile,
                    data?.let { File(it, "shared_prefs") },
                ).forEach(::renameIn)
            }
            done = true
        }
    }

    private fun renameIn(dir: File) {
        dir.listFiles()?.filter { it.name.startsWith(OldPrefix) }?.forEach { old ->
            val target = File(dir, NewPrefix + old.name.removePrefix(OldPrefix))
            if (!target.exists()) old.renameTo(target)
        }
    }

    // a file name saved before the rename (a tv profile's database), as it's called now
    fun renamed(name: String): String = if (name.startsWith(OldPrefix)) NewPrefix + name.removePrefix(OldPrefix) else name

    // the keystore can't rename a key, so an install that already sealed secrets keeps opening them with its old alias
    val OldVaultAlias get() = OldPrefix + "_secrets"
}
