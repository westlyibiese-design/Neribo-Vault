package com.westly.neribovault.feature.church

/**
 * One book of the Bible: its full [name] and the short forms people commonly write,
 * such as "Jn" for John or "1 Cor" for 1 Corinthians.
 */
internal data class BibleBook(val name: String, val abbreviations: List<String>)

/** All 66 books of the Protestant canon, in canon order. Book names only, never scripture text. */
internal val BIBLE_BOOKS: List<BibleBook> = listOf(
    BibleBook("Genesis", listOf("Gen", "Ge", "Gn")),
    BibleBook("Exodus", listOf("Exod", "Exo", "Ex")),
    BibleBook("Leviticus", listOf("Lev", "Le", "Lv")),
    BibleBook("Numbers", listOf("Num", "Nu", "Nm", "Nb")),
    BibleBook("Deuteronomy", listOf("Deut", "Dt", "De")),
    BibleBook("Joshua", listOf("Josh", "Jos", "Jsh")),
    BibleBook("Judges", listOf("Judg", "Jdg", "Jg", "Jdgs")),
    BibleBook("Ruth", listOf("Ru", "Rth")),
    BibleBook("1 Samuel", listOf("1 Sam", "1 Sa", "1 Sm", "1S")),
    BibleBook("2 Samuel", listOf("2 Sam", "2 Sa", "2 Sm", "2S")),
    BibleBook("1 Kings", listOf("1 Kgs", "1 Ki", "1K")),
    BibleBook("2 Kings", listOf("2 Kgs", "2 Ki", "2K")),
    BibleBook("1 Chronicles", listOf("1 Chron", "1 Chr", "1 Ch")),
    BibleBook("2 Chronicles", listOf("2 Chron", "2 Chr", "2 Ch")),
    BibleBook("Ezra", listOf("Ezr", "Ez")),
    BibleBook("Nehemiah", listOf("Neh", "Ne")),
    BibleBook("Esther", listOf("Esth", "Est", "Es")),
    BibleBook("Job", listOf("Jb")),
    BibleBook("Psalms", listOf("Ps", "Psalm", "Psa", "Pss", "Psm")),
    BibleBook("Proverbs", listOf("Prov", "Pro", "Prv", "Pr")),
    BibleBook("Ecclesiastes", listOf("Eccles", "Eccl", "Ecc", "Ec", "Qoh")),
    BibleBook("Song of Solomon", listOf("Song", "Song of Songs", "SOS", "SS", "Canticles", "Cant")),
    BibleBook("Isaiah", listOf("Isa", "Is")),
    BibleBook("Jeremiah", listOf("Jer", "Je", "Jr")),
    BibleBook("Lamentations", listOf("Lam", "La")),
    BibleBook("Ezekiel", listOf("Ezek", "Eze", "Ezk")),
    BibleBook("Daniel", listOf("Dan", "Da", "Dn")),
    BibleBook("Hosea", listOf("Hos", "Ho")),
    BibleBook("Joel", listOf("Jl")),
    BibleBook("Amos", listOf("Am")),
    BibleBook("Obadiah", listOf("Obad", "Ob")),
    BibleBook("Jonah", listOf("Jon", "Jnh")),
    BibleBook("Micah", listOf("Mic", "Mc")),
    BibleBook("Nahum", listOf("Nah", "Na")),
    BibleBook("Habakkuk", listOf("Hab", "Hb")),
    BibleBook("Zephaniah", listOf("Zeph", "Zep", "Zp")),
    BibleBook("Haggai", listOf("Hag", "Hg")),
    BibleBook("Zechariah", listOf("Zech", "Zec", "Zc")),
    BibleBook("Malachi", listOf("Mal", "Ml")),
    BibleBook("Matthew", listOf("Matt", "Mt")),
    BibleBook("Mark", listOf("Mrk", "Mk", "Mr")),
    BibleBook("Luke", listOf("Luk", "Lk")),
    BibleBook("John", listOf("Jn", "Jhn")),
    BibleBook("Acts", listOf("Act", "Ac")),
    BibleBook("Romans", listOf("Rom", "Ro", "Rm")),
    BibleBook("1 Corinthians", listOf("1 Cor", "1 Co")),
    BibleBook("2 Corinthians", listOf("2 Cor", "2 Co")),
    BibleBook("Galatians", listOf("Gal", "Ga")),
    BibleBook("Ephesians", listOf("Eph", "Ephes")),
    BibleBook("Philippians", listOf("Phil", "Php", "Pp")),
    BibleBook("Colossians", listOf("Col")),
    BibleBook("1 Thessalonians", listOf("1 Thess", "1 Thes", "1 Th")),
    BibleBook("2 Thessalonians", listOf("2 Thess", "2 Thes", "2 Th")),
    BibleBook("1 Timothy", listOf("1 Tim", "1 Ti")),
    BibleBook("2 Timothy", listOf("2 Tim", "2 Ti")),
    BibleBook("Titus", listOf("Tit")),
    BibleBook("Philemon", listOf("Philem", "Phm", "Pm")),
    BibleBook("Hebrews", listOf("Heb")),
    BibleBook("James", listOf("Jas", "Jm")),
    BibleBook("1 Peter", listOf("1 Pet", "1 Pe", "1 Pt")),
    BibleBook("2 Peter", listOf("2 Pet", "2 Pe", "2 Pt")),
    BibleBook("1 John", listOf("1 Jn", "1 Jhn")),
    BibleBook("2 John", listOf("2 Jn", "2 Jhn")),
    BibleBook("3 John", listOf("3 Jn", "3 Jhn")),
    BibleBook("Jude", listOf("Jud", "Jd")),
    BibleBook("Revelation", listOf("Rev", "Re", "Rv", "Revelations")),
)

/** A book with its names pre-shrunk for matching, so typing never does repeated work. */
private class IndexedBook(book: BibleBook) {
    val name: String = book.name
    val nameKey: String = matchKey(book.name)
    val aliasKeys: List<String> = book.abbreviations.map { matchKey(it) }
}

private val INDEXED_BOOKS: List<IndexedBook> = BIBLE_BOOKS.map { IndexedBook(it) }

/** Most suggestions shown at once. */
private const val MAX_SUGGESTIONS = 10

/** A reference segment longer than this is clearly not a book name being typed. */
private const val MAX_BOOK_PART_LENGTH = 30

/** Optional leading book number (1 to 3), then letters, spaces and dots. No chapter digits yet. */
private val BOOK_PART = Regex("^\\s*([1-3]?\\s*(?:[A-Za-z][A-Za-z.\\s]*)?)$")

/** Lowercase letters and digits only, so "1 Cor.", "1cor" and "1 COR" all look the same. */
private fun matchKey(text: String): String =
    text.lowercase().filter { it.isLetterOrDigit() }

/**
 * The part of [fieldText] the owner is typing right now: everything after the last semicolon,
 * or null when it already has a chapter number or is not shaped like a book name.
 */
private fun typedBookPart(fieldText: String): String? {
    val segment = fieldText.substringAfterLast(';')
    if (segment.length > MAX_BOOK_PART_LENGTH) return null
    val match = BOOK_PART.matchEntire(segment) ?: return null
    val part = match.groupValues[1]
    return if (part.isBlank()) null else part
}

/**
 * Book names that match what is being typed at the end of a scripture field, best first.
 *
 * Works for full names ("Jo", "Romans"), abbreviations ("Jn", "1 Cor", "Rom") and forms without
 * spaces ("1cor"). Returns an empty list for anything else, including empty input, text that
 * already has a chapter number, and a book that has just been completed.
 */
internal fun suggestBooks(fieldText: String): List<String> {
    val part = typedBookPart(fieldText) ?: return emptyList()
    val key = matchKey(part)
    if (key.isEmpty()) return emptyList()
    val justCompleted = part.last().isWhitespace() &&
        INDEXED_BOOKS.any { it.nameKey == key }
    if (justCompleted) return emptyList()
    return INDEXED_BOOKS
        .mapIndexedNotNull { index, book ->
            val rank = when {
                book.nameKey == key || key in book.aliasKeys -> 0
                book.nameKey.startsWith(key) -> 1
                book.aliasKeys.any { it.startsWith(key) } -> 2
                else -> null
            }
            if (rank == null) null else Triple(rank, index, book.name)
        }
        .sortedWith(compareBy({ it.first }, { it.second }))
        .take(MAX_SUGGESTIONS)
        .map { it.third }
}

/**
 * Replaces the book part being typed at the end of [fieldText] with [bookName] and a space,
 * keeping every reference before the last semicolon. The owner carries on with chapter and verse.
 */
internal fun completeBookName(fieldText: String, bookName: String): String {
    val cut = fieldText.lastIndexOf(';')
    val head = if (cut >= 0) fieldText.substring(0, cut + 1) + " " else ""
    return "$head$bookName "
}
