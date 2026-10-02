package com.fpink.core.model

import java.text.Normalizer

/**
 * Offline, dependency-free fuzzy search over notes. Handwriting recognition is imperfect, so a
 * query token may match a note token exactly, as a prefix, as a substring, within a small edit
 * distance, or as an in-order subsequence. Every query token must match (AND semantics); notes
 * are ranked by the summed per-token quality, with ties keeping the input order.
 */
object NoteSearch {
    private const val EXACT = 100.0
    private const val PREFIX = 80.0
    private const val SUBSTRING = 60.0
    private const val ONE_EDIT = 50.0
    private const val TWO_EDITS = 35.0
    private const val SUBSEQUENCE = 20.0
    private const val ORDER_BONUS = 10.0
    private const val SECONDARY_FIELD_WEIGHT = 0.6

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    fun rank(notes: List<Note>, query: String): List<Note> {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return notes
        return notes
            .mapIndexedNotNull { index, note -> score(note, queryTokens)?.let { Triple(note, it, index) } }
            .sortedWith(compareByDescending<Triple<Note, Double, Int>> { it.second }.thenBy { it.third })
            .map { it.first }
    }

    /** Returns null when [note] does not match every token of [query]. Blank queries match with score 0. */
    fun score(note: Note, query: String): Double? {
        val queryTokens = tokenize(query)
        return if (queryTokens.isEmpty()) 0.0 else score(note, queryTokens)
    }

    internal fun tokenize(value: String): List<String> =
        normalize(value).split(SEPARATORS).filter { it.isNotEmpty() }

    internal fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase()

    private fun score(note: Note, queryTokens: List<String>): Double? {
        val textTokens = tokenize(note.text)
        val secondaryTokens = (note.tags + listOfNotNull(note.inkColorName)).flatMap(::tokenize)
        var total = 0.0
        var previousPosition = -1
        var inOrder = 0
        for (queryToken in queryTokens) {
            var best = 0.0
            var bestPosition = -1
            textTokens.forEachIndexed { position, token ->
                val value = tokenScore(queryToken, token)
                if (value > best) {
                    best = value
                    bestPosition = position
                }
            }
            for (token in secondaryTokens) {
                val value = tokenScore(queryToken, token) * SECONDARY_FIELD_WEIGHT
                if (value > best) {
                    best = value
                    bestPosition = -1
                }
            }
            if (best == 0.0) return null
            if (bestPosition > previousPosition && previousPosition >= 0) inOrder++
            if (bestPosition >= 0) previousPosition = bestPosition
            total += best
        }
        return total + inOrder * ORDER_BONUS
    }

    private fun tokenScore(query: String, token: String): Double = when {
        token == query -> EXACT
        token.startsWith(query) -> PREFIX
        query.length >= 3 && token.contains(query) -> SUBSTRING
        else -> {
            val allowed = maxEdits(query.length)
            val distance = if (allowed == 0) Int.MAX_VALUE else boundedEditDistance(query, token, allowed)
            when {
                distance <= 1 -> ONE_EDIT
                distance <= allowed -> TWO_EDITS
                query.length >= 3 && isSubsequence(query, token) -> SUBSEQUENCE
                else -> 0.0
            }
        }
    }

    private fun maxEdits(length: Int): Int = when {
        length < 4 -> 0
        length < 8 -> 1
        else -> 2
    }

    private fun isSubsequence(query: String, token: String): Boolean {
        var index = 0
        for (char in token) {
            if (index < query.length && query[index] == char) index++
        }
        return index == query.length
    }

    /**
     * Optimal string alignment distance (Damerau–Levenshtein with adjacent transpositions),
     * returning a value above [limit] as soon as the limit cannot be met.
     */
    internal fun boundedEditDistance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var previous2 = IntArray(b.length + 1)
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowMinimum = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var value = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    value = minOf(value, previous2[j - 2] + 1)
                }
                current[j] = value
                if (value < rowMinimum) rowMinimum = value
            }
            if (rowMinimum > limit) return limit + 1
            val recycled = previous2
            previous2 = previous
            previous = current
            current = recycled
        }
        return previous[b.length]
    }
}
