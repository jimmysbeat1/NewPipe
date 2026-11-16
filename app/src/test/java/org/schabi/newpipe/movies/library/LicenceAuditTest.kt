/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LicenceAuditTest {
    private fun kind(url: String) = LicenceAudit.fromUrl(url)?.kind

    @Test
    fun `accepts the licence urls found in the feature films collection`() {
        // the values the Internet Archive actually returns, most frequent first
        assertEquals(LicenceKind.PUBLIC_DOMAIN, kind("https://creativecommons.org/publicdomain/mark/1.0/"))
        assertEquals(LicenceKind.PUBLIC_DOMAIN, kind("http://creativecommons.org/publicdomain/mark/1.0/"))
        assertEquals(LicenceKind.PUBLIC_DOMAIN, kind("http://creativecommons.org/licenses/publicdomain/"))
        assertEquals(LicenceKind.CC_BY_NC_ND, kind("https://creativecommons.org/licenses/by-nc-nd/4.0/"))
        assertEquals(LicenceKind.CC0, kind("http://creativecommons.org/publicdomain/zero/1.0/"))
        assertEquals(LicenceKind.CC_BY, kind("http://creativecommons.org/licenses/by/3.0/"))
        assertEquals(LicenceKind.CC_BY_ND, kind("https://creativecommons.org/licenses/by-nd/4.0/"))
        assertEquals(LicenceKind.CC_BY_NC, kind("https://creativecommons.org/licenses/by-nc/4.0/"))
        assertEquals(LicenceKind.CC_BY_NC_SA, kind("http://creativecommons.org/licenses/by-nc-sa/4.0/"))
        assertEquals(LicenceKind.CC_BY_ND, kind("http://creativecommons.org/licenses/by-nd/3.0/us/"))
        assertEquals(LicenceKind.CC_BY_NC, kind("http://creativecommons.org/licenses/by-nc/2.5/scotland/"))
        assertEquals(LicenceKind.CC_BY_SA, kind("http://creativecommons.org/licenses/by-sa/3.0/es/"))
        assertEquals(LicenceKind.CC_BY, kind("https://creativecommons.org/licenses/by/3.0"))
        assertEquals(LicenceKind.CC_BY_SA, kind("https://creativecommons.org/licenses/by-sa/4.0/deed.en"))
    }

    @Test
    fun `names the licence with version and jurisdiction`() {
        assertEquals("CC BY-NC 2.5 SCOTLAND", LicenceAudit.fromUrl("http://creativecommons.org/licenses/by-nc/2.5/scotland/")?.name)
        assertEquals("CC BY 3.0", LicenceAudit.fromUrl("http://creativecommons.org/licenses/by/3.0/")?.name)
        assertEquals("Public domain", LicenceAudit.fromUrl("https://creativecommons.org/publicdomain/mark/1.0/")?.name)
        assertEquals("CC0 1.0", LicenceAudit.fromUrl("http://creativecommons.org/publicdomain/zero/1.0/")?.name)
        assertEquals(
            "https://creativecommons.org/licenses/by/3.0/",
            LicenceAudit.fromUrl("http://creativecommons.org/licenses/by/3.0/")?.url
        )
    }

    @Test
    fun `rejects everything that is not a recognised free licence`() {
        assertNull(LicenceAudit.fromUrl(null))
        assertNull(LicenceAudit.fromUrl(""))
        assertNull(LicenceAudit.fromUrl("public domain"))
        assertNull(LicenceAudit.fromUrl("https://example.com/creativecommons.org/licenses/by/4.0/"))
        assertNull(LicenceAudit.fromUrl("https://creativecommons.org.evil.example/licenses/by/4.0/"))
        assertNull(LicenceAudit.fromUrl("http://creativecommons.org/licenses/sampling+/1.0/"))
        assertNull(LicenceAudit.fromUrl("http://creativecommons.org/licenses/devnations/2.0/"))
        assertNull(LicenceAudit.fromUrl("https://creativecommons.org/licenses/"))
        assertNull(LicenceAudit.fromUrl("https://rightsstatements.org/vocab/InC/1.0/"))
    }

    @Test
    fun `evaluates wikimedia commons licence fields`() {
        assertEquals(LicenceKind.PUBLIC_DOMAIN, LicenceAudit.fromCommons("pd", null, "Public domain")?.kind)
        assertEquals("Public domain", LicenceAudit.fromCommons("pd", null, "Public domain")?.name)
        assertEquals(LicenceKind.PUBLIC_DOMAIN, LicenceAudit.fromCommons("PD-US-expired", null, null)?.kind)
        assertEquals(LicenceKind.CC0, LicenceAudit.fromCommons("cc0", null, "CC0")?.kind)
        assertEquals(LicenceKind.CC_BY_SA, LicenceAudit.fromCommons("cc-by-sa-4.0", null, "CC BY-SA 4.0")?.kind)
        assertEquals(LicenceKind.CC_BY, LicenceAudit.fromCommons("cc-by-3.0", "https://creativecommons.org/licenses/by/3.0", "CC BY 3.0")?.kind)
        assertEquals("CC BY 3.0", LicenceAudit.fromCommons("cc-by-3.0", "https://creativecommons.org/licenses/by/3.0", "x")?.name)

        assertNull(LicenceAudit.fromCommons(null, null, null))
        assertNull(LicenceAudit.fromCommons("", null, "Fair use"))
        assertNull(LicenceAudit.fromCommons("gfdl", "http://www.gnu.org/copyleft/fdl.html", "GFDL"))
        assertNull(LicenceAudit.fromCommons("copyrighted", null, "Copyrighted"))
    }
}
