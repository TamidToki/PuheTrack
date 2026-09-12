package com.puhetrack.app.translate

/**
 * Simplified Finnish pronunciation guide for English speakers.
 * Runs instantly, no network or model needed.
 */
object FinnishTranslit {

    fun transliterate(finnish: String): String {
        if (finnish.isBlank()) return ""
        return finnish.lowercase()
            // Double vowels → long mark
            .replace("aa", "ā")
            .replace("ee", "ē")
            .replace("ii", "ī")
            .replace("oo", "ō")
            .replace("uu", "ū")
            .replace("ää", "ǟ")
            .replace("öö", "ȫ")
            .replace("yy", "ȳ")
            // Double consonants → single with gemination mark
            .replace("kk", "kˑ")
            .replace("pp", "pˑ")
            .replace("tt", "tˑ")
            .replace("ll", "lˑ")
            .replace("nn", "nˑ")
            .replace("mm", "mˑ")
            .replace("ss", "sˑ")
            .replace("rr", "rˑ")
            // Common digraphs
            .replace("nk", "ŋk")
            .replace("ng", "ŋŋ")
            // Letters that confuse English speakers
            .replace("j", "y")
            .replace("y", "ü")
            .replace("ä", "æ")
            .replace("ö", "ø")
            .trim()
    }
}
