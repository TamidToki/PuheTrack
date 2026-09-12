package com.puhetrack.app.study

import com.puhetrack.app.data.Seg

data class VocabItem(val fi: String, val count: Int, var en: String = "")

private val FI_STOPWORDS = setOf(
    "ja", "on", "ei", "että", "se", "hän", "ne", "tämä", "tama", "nämä", "nama",
    "joka", "jotka", "jossa", "josta", "johon", "jolloin", "joten", "kuin",
    "myös", "myos", "myöskin", "myoskin", "vain", "ainoastaan", "jo", "vielä", "viela",
    "nyt", "sitten", "sit", "niin", "näin", "nain", "noin", "miten", "miksi", "mikä",
    "mika", "mitä", "mita", "kuka", "ketkä", "ketka", "missä", "missa", "mihin",
    "mistä", "mista", "milloin", "kuinka", "paljon", "vähän", "vahan", "liikaa",
    "kaikki", "jokainen", "moni", "usea", "muutama", "sama", "saman", "toinen",
    "toiset", "muu", "muut", "oma", "omat", "minun", "sinun", "hänen", "hanen",
    "meidän", "meidan", "teidän", "teidan", "heidän", "heidan", "minä", "mina",
    "sinä", "sina", "me", "te", "he", "minua", "sinua", "häntä", "hanta",
    "meidät", "meidat", "teidät", "teidat", "heidät", "heidat", "minulle",
    "sinulle", "hänelle", "hanelle", "meille", "teille", "heille", "minussa",
    "sinussa", "hänessä", "hanessa", "meissä", "meissa", "teissä", "teissa",
    "heissä", "heissa", "minusta", "sinusta", "hänestä", "hanesta", "meistä",
    "meista", "teistä", "teista", "heistä", "heista", "minuun", "sinuun",
    "häneen", "haneen", "meihin", "teihin", "heihin", "kanssa", "ilman",
    "kanssaan", "mukaan", "vuoksi", "takia", "aikana", "jälkeen", "jalkeen",
    "ennen", "asti", "saakka", "kohti", "vastaan", "puolesta", "ohi", "läpi",
    "lapi", "yli", "ali", "ympäri", "ympari", "kesken", "keskellä", "keskella",
    "vieressä", "vieressa", "edessä", "edessa", "takana", "alla", "päällä",
    "paalla", "sisällä", "sisalla", "ulkona", "kotona", "täällä", "taalla",
    "siellä", "siella", "tuolla", "kaikkialla", "ei", "en", "et", "emme",
    "ette", "eivät", "eivat", "älä", "ala", "älkää", "alkaa", "ole", "olen",
    "olet", "olemme", "olette", "ovat", "oli", "olivat", "olisi", "olleet",
    "oleva", "olevat", "olla", "ollut", "tai", "vai", "vaan", "mutta", "mut",
    "sillä", "silla", "koska", "kun", "jos", "vaikka", "jotta", "kunnes",
    "ellei", "ettei", "kunhan", "niin", "siis", "eli", "tosin", "kuitenkin",
    "kuitenkaan", "silti", "siksi", "siten", "sittenkin", "muuten", "muutenkin",
    "toki", "tosiaan", "kyllä", "kylla", "eikä", "eika", "eivätkä", "eivatka",
    "niinkuin", "niinkään", "niinkaan", "sekä", "seka", "että", "ettäkin",
    "myös", "tässä", "tassa", "tuossa", "siinä", "siina", "tästä", "tasta",
    "tuosta", "siitä", "siita", "tähän", "tahan", "tuohon", "siihen", "tänä",
    "tana", "tänään", "tanaan", "huomenna", "eilen", "tänne", "tanne",
    "tuonne", "sinne", "täältä", "taalta", "tuolta", "sieltä", "sielta",
    "täten", "taten", "siten", "tuoten", "no", "ni", "joo", "jaa", "niin",
    "aivan", "ihan", "todella", "erittäin", "erittain", "hyvin", "kovin",
    "melko", "aika", "suurin", "suurinpiirtein", "noin", "ehkä", "ehka",
    "varmaan", "tietysti", "tietenkin", "luultavasti", "ehdottomasti",
    "oletan", "okei", "ok", "kiitos", "hyvä", "hyva", "ole",
    "päivää", "paivaa", "huomenta", "iltaa", "näkemiin", "nakemiin", "hei",
    "terve", "moi", "haloo", "anteeksi", "sori", "hetkinen", "valmis"
)

fun contentTokens(text: String): List<String> =
    text.lowercase()
        .replace(Regex("[^\\p{L}\\s-]"), " ")
        .split(Regex("\\s+"))
        .map { it.trim('-') }
        .filter { it.length >= 3 && it !in FI_STOPWORDS && !it.all(Char::isDigit) }

fun topVocabulary(segs: List<Seg>, n: Int = 25): List<VocabItem> {
    val counts = LinkedHashMap<String, Int>()
    segs.forEach { s ->
        contentTokens(s.fi).forEach { t -> counts[t] = (counts[t] ?: 0) + 1 }
    }
    return counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(n)
        .map { VocabItem(it.key, it.value) }
}

fun keySentences(segs: List<Seg>, n: Int = 6): List<Seg> {
    if (segs.isEmpty()) return emptyList()
    val scored = segs.mapIndexed { i, s ->
        val content = contentTokens(s.fi).size
        // Content-rich sentences win; tiny fragments lose.
        Triple(i, s, content * 2.0 + s.fi.length / 60.0)
    }.filter { it.third >= 3.0 }
        .sortedByDescending { it.third }
    val picked = mutableListOf<Pair<Int, Seg>>()
    for ((i, s, _) in scored) {
        if (picked.size >= n) break
        // Spread across the lesson: skip neighbours of already-picked ones.
        if (picked.any { kotlin.math.abs(it.first - i) < 2 }) continue
        picked.add(i to s)
    }
    return picked.sortedBy { it.first }.map { it.second }
}
