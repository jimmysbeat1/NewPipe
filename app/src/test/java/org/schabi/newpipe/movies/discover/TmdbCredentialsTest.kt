/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.discover

import java.io.File
import java.security.GeneralSecurityException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TmdbCredentialsTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Stands in for the Android Keystore, which only exists on a device. */
    private class ReversingBox : SecretBox {
        override fun seal(plain: String) = "sealed:" + plain.reversed()

        override fun open(sealed: String): String? {
            return if (sealed.startsWith("sealed:")) sealed.removePrefix("sealed:").reversed() else null
        }
    }

    private class BrokenBox : SecretBox {
        override fun seal(plain: String): String = throw GeneralSecurityException("no keystore")

        override fun open(sealed: String): String? = null
    }

    private fun file() = File(folder.root, "no_backup/tmdb_credential")

    @Test
    fun theKeyIsStoredSealedAndReadBackAfterARestart() {
        val key = TmdbCredential.parse(TmdbFixtures.V3_KEY)!!
        val credentials = TmdbCredentials(file(), ReversingBox())
        assertFalse(credentials.has())
        assertNull(credentials.get())

        credentials.set(key)
        assertTrue(credentials.persistent)
        assertTrue(credentials.has())
        // the key itself is not what is written to storage
        assertFalse(file().readText().contains(TmdbFixtures.V3_KEY))

        val reopened = TmdbCredentials(file(), ReversingBox())
        assertEquals(TmdbFixtures.V3_KEY, reopened.get()!!.secret)
        assertFalse(reopened.get()!!.bearer)
    }

    @Test
    fun removingTheKeyDeletesItFromStorage() {
        val credentials = TmdbCredentials(file(), ReversingBox())
        credentials.set(TmdbCredential.parse(TmdbFixtures.V4_TOKEN)!!)
        assertTrue(file().isFile)

        credentials.clear()
        assertFalse(credentials.has())
        assertFalse(file().exists())
        assertFalse(TmdbCredentials(file(), ReversingBox()).has())
    }

    @Test
    fun aKeyThatCannotBeEncryptedIsKeptInMemoryOnlyAndNeverWrittenInTheClear() {
        val credentials = TmdbCredentials(file(), BrokenBox())
        credentials.set(TmdbCredential.parse(TmdbFixtures.V3_KEY)!!)

        assertFalse(credentials.persistent)
        assertEquals(TmdbFixtures.V3_KEY, credentials.get()!!.secret)
        assertFalse(file().exists())
        assertFalse(TmdbCredentials(file(), BrokenBox()).has())
    }

    @Test
    fun aFileThatCannotBeOpenedOrDoesNotHoldAKeyCountsAsNoKey() {
        file().parentFile!!.mkdirs()
        file().writeText("not sealed by this device")
        assertFalse(TmdbCredentials(file(), ReversingBox()).has())

        file().writeText("sealed:" + "this is not a key".reversed())
        assertFalse(TmdbCredentials(file(), ReversingBox()).has())
    }
}
