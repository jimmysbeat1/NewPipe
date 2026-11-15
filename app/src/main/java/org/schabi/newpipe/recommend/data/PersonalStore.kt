/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.recommend.data

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Stores the personalisation data (feedback, cached candidates, movie watchlist, ...) as small
 * JSON files in the app's private storage. Nothing in here is ever sent anywhere, it is not part
 * of the NewPipe database (which therefore stays compatible with upstream NewPipe exports), and
 * deleting the directory resets all personalisation.
 */
class PersonalStore(private val directory: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** @return the stored value, or [default] if there is none or it cannot be read */
    @Synchronized
    fun <T> read(name: String, serializer: KSerializer<T>, default: T): T {
        val file = File(directory, "$name.json")
        if (!file.isFile) {
            return default
        }
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (e: IOException) {
            default
        } catch (e: SerializationException) {
            default
        } catch (e: IllegalArgumentException) {
            default
        }
    }

    /** Atomically replaces the stored value: a crash never leaves a half-written file. */
    @Synchronized
    @Throws(IOException::class)
    fun <T> write(name: String, serializer: KSerializer<T>, value: T) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create $directory")
        }
        val temporary = File(directory, "$name.json.tmp")
        temporary.writeText(json.encodeToString(serializer, value))
        val target = File(directory, "$name.json")
        if (!temporary.renameTo(target)) {
            target.delete()
            if (!temporary.renameTo(target)) {
                throw IOException("Could not replace $target")
            }
        }
    }

    @Synchronized
    fun delete(name: String) {
        File(directory, "$name.json").delete()
    }

    companion object {
        @Volatile
        private var instance: PersonalStore? = null

        fun get(context: Context): PersonalStore {
            return instance ?: synchronized(this) {
                instance ?: PersonalStore(
                    File(context.applicationContext.filesDir, "personalisation")
                ).also { instance = it }
            }
        }
    }
}
