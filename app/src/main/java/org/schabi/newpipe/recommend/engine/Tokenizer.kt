/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.recommend.engine

/**
 * Turns titles and search queries into the terms the recommendation engine works with:
 * lowercased words without filler, plus two-word phrases, which make much better topics than
 * single words ("mechanical keyboard" instead of "mechanical" and "keyboard").
 */
object Tokenizer {
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}']+")

    private val STOP_WORDS = (
        "a an and are as at be but by for from has have how i if in into is it its just me my " +
            "no not of on or our out so than that the their them then there these they this " +
            "to too up was we were what when where which who why will with you your vs via " +
            "about after all also any been before can did do does get got had her here him his " +
            "more most new now off one only over own she some such very " +
            // filler that is common in video titles and carries no topical meaning
            "official video videos hd 4k 8k uhd full episode ep part pt feat ft featuring " +
            "live stream watch best top ever reaction shorts short clip trailer teaser " +
            "ultimate amazing review reviews update updates day days year years time times " +
            "el la los las un una de del en que der die das und le les des et il di"
        ).split(' ').toHashSet()

    /** @return the meaningful single words of [text], in order of appearance */
    fun words(text: String?): List<String> {
        if (text.isNullOrBlank()) {
            return emptyList()
        }
        return text.lowercase().split(SEPARATORS)
            .map { it.trim('\'').removeSuffix("'s") }
            .filter { it.length >= 3 && it !in STOP_WORDS && !it.all(Char::isDigit) }
    }

    /** @return the distinct words of [text] and the distinct phrases of two adjacent words */
    fun terms(text: String?): Set<String> {
        val words = words(text)
        val terms = LinkedHashSet<String>(words)
        for (i in 0 until words.size - 1) {
            if (words[i] != words[i + 1]) {
                terms.add(words[i] + " " + words[i + 1])
            }
        }
        return terms
    }
}
