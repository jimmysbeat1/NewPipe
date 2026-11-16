/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.movies.library

import java.util.Locale

/**
 * Decides whether the licence a source publishes for a film permits downloading it.
 *
 * This is an allowlist: only Creative Commons licences and public-domain declarations that are
 * recognised with certainty are accepted, everything else (no licence, "all rights reserved",
 * unknown or custom terms, free-text claims) is rejected. The check verifies what the source
 * declares in machine-readable form; it cannot verify that the declaration itself is correct.
 */
object LicenceAudit {
    private val CC_URL = Regex(
        "^https?://(?:www\\.)?creativecommons\\.org/(licenses|publicdomain)/([a-z-]+)" +
            "(?:/(\\d\\.\\d))?(?:/((?!deed|legalcode)[a-z]{2,8}))?/?(?:deed(?:\\.[A-Za-z_-]+)?|legalcode)?$"
    )

    private val LICENCES = mapOf(
        "by" to LicenceKind.CC_BY,
        "by-sa" to LicenceKind.CC_BY_SA,
        "by-nc" to LicenceKind.CC_BY_NC,
        "by-nc-sa" to LicenceKind.CC_BY_NC_SA,
        "by-nd" to LicenceKind.CC_BY_ND,
        "by-nc-nd" to LicenceKind.CC_BY_NC_ND
    )

    /** @return the licence a `creativecommons.org` URL stands for, or `null` if not accepted */
    fun fromUrl(url: String?): FilmLicence? {
        val trimmed = url?.trim().orEmpty()
        val match = CC_URL.find(trimmed) ?: return null
        val (family, code, version, jurisdiction) = match.destructured
        val kind = when {
            family == "publicdomain" && code == "mark" -> LicenceKind.PUBLIC_DOMAIN
            family == "publicdomain" && code == "zero" -> LicenceKind.CC0
            // the retired "Public Domain Dedication and Certification"
            family == "licenses" && code == "publicdomain" -> LicenceKind.PUBLIC_DOMAIN
            family == "licenses" -> LICENCES[code]
            else -> null
        } ?: return null
        val name = buildString {
            append(kind.shortName)
            if (kind != LicenceKind.PUBLIC_DOMAIN && version.isNotEmpty()) {
                append(' ').append(version)
            }
            if (kind != LicenceKind.PUBLIC_DOMAIN && jurisdiction.isNotEmpty()) {
                append(' ').append(jurisdiction.uppercase(Locale.ROOT))
            }
        }
        return FilmLicence(kind, name, trimmed.replaceFirst("http://", "https://"), trimmed)
    }

    /**
     * Evaluates the licence fields of the Wikimedia Commons `extmetadata` of a file.
     *
     * @param code the machine-readable `License` value, e.g. `cc-by-sa-4.0` or `pd`
     * @param licenceUrl the `LicenseUrl` value, absent for public-domain files
     * @param shortName the `LicenseShortName` value, used for display only
     */
    fun fromCommons(code: String?, licenceUrl: String?, shortName: String?): FilmLicence? {
        val normalized = code?.trim()?.lowercase(Locale.ROOT).orEmpty()
        fromUrl(licenceUrl)?.let { return it.copy(evidence = "$normalized $licenceUrl".trim()) }
        val kind = when {
            normalized == "pd" || normalized.startsWith("pd-") -> LicenceKind.PUBLIC_DOMAIN
            normalized == "cc0" || normalized.startsWith("cc-zero") -> LicenceKind.CC0
            normalized.startsWith("cc-by-nc-nd") -> LicenceKind.CC_BY_NC_ND
            normalized.startsWith("cc-by-nc-sa") -> LicenceKind.CC_BY_NC_SA
            normalized.startsWith("cc-by-nc") -> LicenceKind.CC_BY_NC
            normalized.startsWith("cc-by-nd") -> LicenceKind.CC_BY_ND
            normalized.startsWith("cc-by-sa") -> LicenceKind.CC_BY_SA
            normalized.startsWith("cc-by") -> LicenceKind.CC_BY
            else -> return null
        }
        val name = shortName?.trim()?.takeIf { it.isNotEmpty() } ?: kind.shortName
        return FilmLicence(kind, name, null, normalized)
    }
}
