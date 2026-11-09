package com.gamechanger.shield

import java.text.Normalizer

/**
 * Decides whether a piece of text (a search, a URL, text on screen) is explicit.
 *
 * Two levels:
 *  - [isExplicitInput]: text the user typed or a URL/address bar. Strict list.
 *  - [isExplicitOnScreen]: any visible text. Only unambiguous terms and sites,
 *    so normal articles and chats don't trigger a block.
 */
object TextFilter {

    /** Known adult sites. Matched as substrings of the text with spaces removed. */
    private val DOMAINS = listOf(
        "pornhub", "xvideos", "xnxx", "xhamster", "redtube", "youporn", "brazzers",
        "onlyfans", "chaturbate", "spankbang", "eporner", "tnaflix", "nhentai",
        "rule34", "fansly", "stripchat", "bongacams", "livejasmin", "cam4.",
        "motherless", "hqporner", "porntrex", "xnxx", "beeg.com", "tube8",
        "youjizz", "pornpics", "hanime", "hentaihaven", "erome", "thothub",
        "sex.com", "porn.com", "imagefap", "literotica", "e-hentai", "f95zone",
    )

    /** Unambiguous English words: block anywhere on screen. */
    private val STRONG_EN = Regex(
        "\\b(porn\\w*|pr0n|hentai|nsfw|xxx+|onlyfans|camgirls?|blowjobs?|handjobs?|cumshots?|" +
            "milfs?|creampie|gangbang|bukkake|deepthroat|threesome|stripteases?)\\b"
    )

    /** Broader English words: block when typed / in a search or URL. */
    private val SEARCH_EN = Regex(
        "\\b(sex|sexy|sexting|nudes?|nudity|naked|nakedness|topless|bottomless|boobs?|boobies|" +
            "tits?|titties|nipples?|pussy|pussies|dick|dicks|cock|cocks|penis|vagina|" +
            "anal|orgasms?|masturbat\\w*|erotic\\w*|playboy|strippers?|stripping|" +
            "lewd|thots?|horny|fetish\\w*|bdsm|hookups?|escorts?|naughty|" +
            "undress\\w*|nsfw|r34|leaked nudes|sex tape)\\b"
    )

    /** Hebrew (matched as substrings, after removing niqqud). Unambiguous. */
    private val STRONG_HE = listOf(
        "פורנו", "פורן", "סרטי כחול", "סרט כחול", "סרטי סקס", "סרט סקס",
        "סקס חינם", "חשפנית", "חשפניות", "חשפנות", "אוננות", "לאונן",
        "מזדיינ", "זיונים", "תמונות עירום", "סרטוני עירום", "ציצים",
    )

    /** Hebrew words that block when typed / searched. */
    private val SEARCH_HE = listOf(
        "סקס", "סקסי", "סקסית", "עירום", "עירומה", "עירומים", "עירומות",
        "ערום", "ערומה", "ערומים", "ערומות", "חשוף", "חשופה", "חשופות", "חזה חשוף",
        "אירוטי", "ארוטי", "אירוטיק", "ארוטיק", "זיון", "לזיין", "מין אוראלי",
        "יחסי מין", "נערות ליווי", "נערת ליווי",
        "דירה דיסקרטית", "דירות דיסקרטיות", "מסאז' אירוטי", "שדיים", "פטמות",
        "איבר מין", "אונן",
    )

    /** Extra search words from level 3 up: underwear / swimwear. */
    private val LEVEL2_HE = listOf("תחתונים", "הלבשה תחתונה", "ביקיני", "בגד ים", "בגדי ים", "חזייה", "חזיה", "לבוש חושפני")
    private val LEVEL2_EN = Regex("\\b(bikinis?|lingerie|underwear|panties|bra|bras|thongs?|swimsuits?|swimwear|cleavage|see through|braless)\\b")

    private val niqqud = Regex("[\\u0591-\\u05C7]")
    private val leet = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '@' to 'a', '$' to 's', '7' to 't')

    fun normalize(raw: CharSequence): String {
        val s = Normalizer.normalize(raw.toString(), Normalizer.Form.NFKC)
            .lowercase()
            .replace(niqqud, "")
        return s
    }

    private fun deLeet(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(leet[c] ?: c)
        return sb.toString()
    }

    private val ws = Regex("\\s+")

    /** Checks each word separately so a site name can't be formed across two words. */
    private fun findDomain(s: String): String? {
        for (token in s.split(ws)) {
            if (token.length < 4) continue
            val t = token.filter { it.isLetterOrDigit() || it == '.' }
            DOMAINS.firstOrNull { t.contains(it) }?.let { return it }
        }
        return null
    }

    /** Returns the matched reason, or null when clean. */
    fun isExplicitOnScreen(raw: CharSequence): String? {
        if (raw.isBlank()) return null
        val s = normalize(raw)
        findDomain(s)?.let { return it }
        STRONG_HE.firstOrNull { s.contains(it) }?.let { return it }
        STRONG_EN.find(s)?.let { return it.value }
        return null
    }

    /** Stricter check for typed text, search boxes and address bars. */
    fun isExplicitInput(raw: CharSequence, textLevel: Int = 1): String? {
        if (raw.isBlank()) return null
        isExplicitOnScreen(raw)?.let { return it }
        val s = normalize(raw)
        // Search engines turn spaces into + or %20 in URLs.
        val spaced = s.replace('+', ' ').replace("%20", " ").replace('_', ' ').replace('-', ' ')
        val variants = listOf(spaced, deLeet(spaced))
        for (v in variants) {
            STRONG_EN.find(v)?.let { return it.value }
            SEARCH_EN.find(v)?.let { return it.value }
            SEARCH_HE.firstOrNull { v.contains(it) }?.let { return it }
            if (textLevel >= 2) {
                LEVEL2_EN.find(v)?.let { return it.value }
                LEVEL2_HE.firstOrNull { v.contains(it) }?.let { return it }
            }
        }
        return findDomain(deLeet(s))
    }
}
