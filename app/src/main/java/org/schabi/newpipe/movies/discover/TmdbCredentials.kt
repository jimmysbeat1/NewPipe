/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.discover

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets for storage on the device. */
interface SecretBox {
    @Throws(GeneralSecurityException::class)
    fun seal(plain: String): String

    /** @return the secret, or `null` if it cannot be recovered */
    fun open(sealed: String): String?
}

/**
 * Encrypts with an AES-256-GCM key that lives in the Android Keystore. The key cannot be
 * exported, so the stored file is useless on any other device and in backups.
 */
class KeystoreSecretBox(private val alias: String = "pipehome_tmdb") : SecretBox {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        )
        return generator.generateKey()
    }

    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return encode(cipher.iv) + SEPARATOR + encode(encrypted)
    }

    override fun open(sealed: String): String? {
        val parts = sealed.split(SEPARATOR)
        if (parts.size != 2) {
            return null
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, decode(parts[0])))
            String(cipher.doFinal(decode(parts[1])), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val SEPARATOR = ":"
    }
}

/**
 * Keeps the TMDB key of the user.
 *
 * The key is the user's own and is never part of the app or of its source code. It is stored
 * encrypted (see [SecretBox]) in a directory that Android does not back up, it is not part of
 * NewPipe's settings export, and it is only ever sent to `api.themoviedb.org`. If the device
 * cannot encrypt it, it is kept in memory until the app is closed instead of being written to
 * storage unprotected.
 */
class TmdbCredentials(private val file: File, private val box: SecretBox) {
    private var loaded = false
    private var current: TmdbCredential? = null

    /** Whether the key survives a restart of the app. `false` if it could not be encrypted. */
    @get:Synchronized
    var persistent = true
        private set

    @Synchronized
    fun get(): TmdbCredential? {
        if (!loaded) {
            loaded = true
            current = try {
                if (file.isFile) TmdbCredential.parse(box.open(file.readText().trim())) else null
            } catch (e: IOException) {
                null
            } catch (e: RuntimeException) {
                // keystore implementations of some devices fail with unchecked exceptions
                null
            }
        }
        return current
    }

    @Synchronized
    fun has(): Boolean = get() != null

    @Synchronized
    fun set(credential: TmdbCredential) {
        loaded = true
        current = credential
        persistent = try {
            val sealed = box.seal(credential.secret)
            file.parentFile?.mkdirs()
            file.writeText(sealed)
            true
        } catch (e: GeneralSecurityException) {
            file.delete()
            false
        } catch (e: IOException) {
            file.delete()
            false
        } catch (e: RuntimeException) {
            // keystore implementations of some devices fail with unchecked exceptions
            file.delete()
            false
        }
    }

    @Synchronized
    fun clear() {
        loaded = true
        current = null
        persistent = true
        file.delete()
    }

    companion object {
        private const val FILE_NAME = "tmdb_credential"

        @Volatile
        private var instance: TmdbCredentials? = null

        /** Tests replace it: the Android Keystore does not exist off a device. */
        @Volatile
        var boxFactory: () -> SecretBox = { KeystoreSecretBox() }

        fun get(context: Context): TmdbCredentials {
            return instance ?: synchronized(this) {
                instance ?: TmdbCredentials(
                    File(context.applicationContext.noBackupFilesDir, FILE_NAME),
                    boxFactory()
                ).also { instance = it }
            }
        }
    }
}
