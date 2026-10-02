package com.westly.neribovault.core.search

import com.westly.neribovault.core.util.snippet
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/*
 * Pure search functions. Nothing here touches Android, the database or any shared state, so each
 * one can be reasoned about on its own. User input is only ever used with plain string search,
 * never to build a Regex.
 */

/** The most results one search returns. */
const val MAX_SEARCH_RESULTS = 200

/** The most separate words of one query that are used. */
private const val MAX_TERMS = 8

/** The most places one word is highlighted in a single piece of text. */
private const val MAX_HIGHLIGHTS_PER_TERM = 20

private val WHITESPACE = Regex("\\s+")

private val foldCache = ConcurrentHashMap<Char, Char>()

/** Folds one character: lowercase with accents removed. Always returns exactly one character. */
private fun foldChar(ch: Char): Char {
    val code = ch.code
    if (code < 128) {
        return if (ch in 'A'..'Z') (code + 32).toChar() else ch
    }
    val cached = foldCache[ch]
    if (cached != null) return cached
    val computed = computeFold(ch)
    foldCache[ch] = computed
    return computed
}

private fun computeFold(ch: Char): Char {
    if (Character.isSurrogate(ch)) return ch
    val decomposed = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
    val base = decomposed.firstOrNull { c -> Character.getType(c) != Character.NON_SPACING_MARK.toInt() }
    return (base ?: ch).lowercaseChar()
}

/**
 * Lowercases [text] and strips accents ("Ọlá" becomes "ola"). The result always has the same
 * length as [text], character for character, so an index found in the folded text is also the
 * right index in the original text.
 */
fun foldForSearch(text: String): String {
    if (text.isEmpty()) return text
    val chars = CharArray(text.length)
    for (i in text.indices) chars[i] = foldChar(text[i])
    return String(chars)
}

/** The folded, distinct words of [query]. Empty for a blank query. */
fun parseSearchTerms(query: String): List<String> {
    val folded = foldForSearch(query).trim()
    if (folded.isEmpty()) return emptyList()
    return folded.split(WHITESPACE)
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_TERMS)
}

/** An [IndexedItem] with its title and body folded once, ready for fast matching. */
class SearchableItem(
    val item: IndexedItem,
    val foldedTitle: String,
    val foldedBody: String,
)

/** Folds the title and body of this item once, so searching it later is cheap. */
fun IndexedItem.toSearchable(): SearchableItem =
    SearchableItem(this, foldForSearch(title), foldForSearch(body))

/**
 * One matching item. [tier] is 0 when every word is in the title, 1 when some words are, and 2
 * when the match is in the body only. [bodyMatchIndex] is where the earliest word starts in the
 * body, or -1 when no word is in the body.
 */
class SearchHit(
    val item: IndexedItem,
    val tier: Int,
    val bodyMatchIndex: Int,
)

/** The hits of one vault, best first. */
class SearchGroup(
    val vaultId: String,
    val vaultLabel: String,
    val hits: List<SearchHit>,
)

/**
 * Finds the items in [index] where every word of [terms] appears in the title or the body.
 * Title matches rank above body matches, then the most recently edited comes first. At most
 * [limit] hits are returned; an empty [terms] list returns nothing.
 */
fun searchItems(
    index: List<SearchableItem>,
    terms: List<String>,
    limit: Int = MAX_SEARCH_RESULTS,
): List<SearchHit> {
    if (terms.isEmpty() || limit <= 0) return emptyList()
    val hits = ArrayList<SearchHit>()
    for (entry in index) {
        val hit = matchEntry(entry, terms)
        if (hit != null) hits.add(hit)
    }
    hits.sortWith(compareBy<SearchHit> { it.tier }.thenByDescending { it.item.updatedAt })
    return if (hits.size > limit) ArrayList(hits.subList(0, limit)) else hits
}

private fun matchEntry(entry: SearchableItem, terms: List<String>): SearchHit? {
    var wordsInTitle = 0
    var firstBodyIndex = -1
    for (term in terms) {
        val inTitle = entry.foldedTitle.contains(term)
        val bodyIndex = entry.foldedBody.indexOf(term)
        if (!inTitle && bodyIndex < 0) return null
        if (inTitle) wordsInTitle++
        if (bodyIndex >= 0 && (firstBodyIndex < 0 || bodyIndex < firstBodyIndex)) {
            firstBodyIndex = bodyIndex
        }
    }
    val tier = when {
        wordsInTitle == terms.size -> 0
        wordsInTitle > 0 -> 1
        else -> 2
    }
    return SearchHit(entry.item, tier, firstBodyIndex)
}

/** Groups [hits] by vault. Groups appear in the order of their best hit. */
fun groupHitsByVault(hits: List<SearchHit>): List<SearchGroup> {
    val byVault = LinkedHashMap<String, MutableList<SearchHit>>()
    for (hit in hits) {
        byVault.getOrPut(hit.item.vaultId) { ArrayList() }.add(hit)
    }
    val groups = ArrayList<SearchGroup>(byVault.size)
    for ((vaultId, list) in byVault) {
        groups.add(SearchGroup(vaultId, list.first().item.vaultLabel, list))
    }
    return groups
}

/**
 * A one-line preview of [body] around the character at [matchIndex], with an ellipsis on either
 * side where text was cut. With no match (negative index) it is simply the start of the body.
 */
fun snippetAround(body: String, matchIndex: Int, maxChars: Int = 120): String {
    if (body.isBlank()) return ""
    if (matchIndex < 0 || matchIndex >= body.length) return snippet(body, maxChars)
    var start = (matchIndex - LEAD_CHARS).coerceAtLeast(0)
    if (start > 0) {
        // Begin just after a space when there is one before the match, so words are not cut.
        var i = start
        while (i < matchIndex && !body[i].isWhitespace()) i++
        if (i < matchIndex) start = i + 1
    }
    val end = (start + maxChars).coerceAtMost(body.length)
    val middle = body.substring(start, end).replace(WHITESPACE, " ").trim()
    if (middle.isEmpty()) return snippet(body, maxChars)
    val prefix = if (start > 0) "\u2026" else ""
    val suffix = if (end < body.length) "\u2026" else ""
    return prefix + middle + suffix
}

private const val LEAD_CHARS = 28

/**
 * The parts of [text] that match any of [terms], as sorted, non-overlapping character ranges
 * (last index inclusive). Matching ignores case and accents.
 */
fun highlightRanges(text: String, terms: List<String>): List<IntRange> {
    if (text.isEmpty() || terms.isEmpty()) return emptyList()
    val folded = foldForSearch(text)
    val found = ArrayList<IntRange>()
    for (term in terms) {
        if (term.isEmpty()) continue
        var from = 0
        var count = 0
        while (count < MAX_HIGHLIGHTS_PER_TERM) {
            val at = folded.indexOf(term, from)
            if (at < 0) break
            found.add(at until at + term.length)
            from = at + term.length
            count++
        }
    }
    if (found.isEmpty()) return emptyList()
    found.sortBy { it.first }
    val merged = ArrayList<IntRange>()
    var current = found[0]
    for (i in 1 until found.size) {
        val next = found[i]
        if (next.first <= current.last + 1) {
            current = current.first..maxOf(current.last, next.last)
        } else {
            merged.add(current)
            current = next
        }
    }
    merged.add(current)
    return merged
}
