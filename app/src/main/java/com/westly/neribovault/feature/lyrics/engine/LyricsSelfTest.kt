package com.westly.neribovault.feature.lyrics.engine

/** The outcome of one engine check. [detail] says what was expected and what was found. */
data class CheckResult(val name: String, val passed: Boolean, val detail: String)

private inline fun runCheck(name: String, body: () -> CheckResult): CheckResult =
    try {
        body()
    } catch (e: Exception) {
        CheckResult(name, false, "threw " + e.javaClass.simpleName + ": " + (e.message ?: "no message"))
    }

private fun expectEqual(name: String, expected: Any?, actual: () -> Any?): CheckResult =
    runCheck(name) {
        val found = actual()
        CheckResult(name, found == expected, "expected $expected, found $found")
    }

private fun expectTrue(name: String, detail: () -> Pair<Boolean, String>): CheckResult =
    runCheck(name) {
        val (ok, text) = detail()
        CheckResult(name, ok, text)
    }

private class SampleExpectation(
    val label: String,
    val text: String,
    val sections: Int,
    val lines: Int,
    val words: Int,
    val syllables: Int,
    val longestLine: Int,
)

// Ọmọ = \u1ECCm\u1ECD ; ọmọ = \u1ECDm\u1ECD ; Ẹ̀gbọ́n = \u1EB8\u0300gb\u1ECD\u0301n
private const val EGBON = "\u1EB8\u0300gb\u1ECD\u0301n"
private const val OMO = "\u1ECDm\u1ECD"

private fun wordChecks(): CheckResult = runCheck("Word syllable checks (16 words)") {
    val expected = listOf(
        "love" to 1, "table" to 2, "come" to 1, "the" to 1, "dey" to 1, OMO to 2, EGBON to 2,
        "generator" to 4, "neighbours" to 2, "beautiful" to 3, "Jerusalema" to 5, "Lagos" to 2,
        "pikin" to 2, "tomorrow" to 3, "rhythm" to 1, "Mmm" to 1,
    )
    val wrong = expected.filter { (word, count) -> SyllableCounter.countWord(word) != count }
    CheckResult(
        "Word syllable checks (16 words)",
        wrong.isEmpty(),
        if (wrong.isEmpty()) "all 16 match" else "wrong: " + wrong.joinToString { (w, c) ->
            "$w expected $c found ${SyllableCounter.countWord(w)}"
        },
    )
}

private fun lineChecks(): CheckResult = runCheck("Line syllable checks (4 lines)") {
    val expected = listOf(
        "Even if the road be long" to 7,
        "$EGBON mi dey wait for me at the bus stop" to 11,
        "Mmm, Lagos lights dey call my name" to 8,
        "I go reach" to 3,
    )
    val wrong = expected.filter { (line, count) -> SyllableCounter.countLine(line) != count }
    CheckResult(
        "Line syllable checks (4 lines)",
        wrong.isEmpty(),
        if (wrong.isEmpty()) "all 4 match" else "wrong: " + wrong.joinToString { (l, c) ->
            "\"$l\" expected $c found ${SyllableCounter.countLine(l)}"
        },
    )
}

private fun labelChecks(): CheckResult = runCheck("Label checks (7 cases)") {
    fun one(text: String): SongSection = LyricsFormat.parse(text).single()
    val problems = ArrayList<String>()
    fun expectType(text: String, type: SectionType, label: String) {
        val section = one(text)
        if (section.type != type || section.label != label) {
            problems.add("${text.lineSequence().first()} gave ${section.type} \"${section.label}\"")
        }
    }
    expectType("[verse]\nla", SectionType.VERSE, "")
    expectType("[Verse 3]\nla", SectionType.VERSE, "")
    expectType("[PRE CHORUS]\nla", SectionType.PRE_CHORUS, "")
    expectType("[prechorus]\nla", SectionType.PRE_CHORUS, "")
    expectType("[Chorus x2]\nla", SectionType.OTHER, "Chorus x2")
    expectType("[Bridge 2]\nla", SectionType.OTHER, "Bridge 2")
    expectType("just a line\nanother line", SectionType.OTHER, "")
    CheckResult(
        "Label checks (7 cases)",
        problems.isEmpty(),
        if (problems.isEmpty()) "all 7 match" else problems.joinToString("; "),
    )
}

private fun edgeRoundTrip(): CheckResult = runCheck("Edge round trip") {
    val sections = listOf(
        SongSection(SectionType.OTHER, "", "[wow] this"),
        SongSection(SectionType.OTHER, "Chorus x2", "la la"),
        SongSection(SectionType.OTHER, "Bridge 2", "ooh"),
        SongSection(SectionType.VERSE, "", "a\nb"),
        SongSection(SectionType.CHORUS, "", "c"),
    )
    val expectedText = "\\[wow] this\n\n[Chorus x2]\nla la\n\n[Bridge 2]\nooh\n\n[Verse 1]\na\nb\n\n[Chorus]\nc\n"
    val written = LyricsFormat.serialize(sections)
    val back = LyricsFormat.parse(written)
    val ok = written == expectedText && back == sections
    CheckResult("Edge round trip", ok, if (ok) "matches" else "wrote \"$written\", parsed back $back")
}

/** Runs the 32 engine checks. Every check catches its own exceptions and reports them as a failure. */
fun runLyricsSelfTest(): List<CheckResult> {
    val a = LyricsSamples.sampleA
    val b = LyricsSamples.sampleB
    val c = LyricsSamples.sampleC
    val samples = listOf(
        SampleExpectation("A", a, 8, 23, 150, 176, 46),
        SampleExpectation("B", b, 32, 92, 600, 704, 46),
        SampleExpectation("C", c, 1, 4, 29, 34, 72),
    )
    val results = ArrayList<CheckResult>()

    // 1. Counts for each sample (15 checks).
    for (s in samples) {
        results.add(expectEqual("Sample ${s.label}: sections", s.sections) {
            LyricsAnalysis.stats(LyricsFormat.parse(s.text)).sections
        })
        results.add(expectEqual("Sample ${s.label}: lines", s.lines) {
            LyricsAnalysis.stats(LyricsFormat.parse(s.text)).lines
        })
        results.add(expectEqual("Sample ${s.label}: words", s.words) {
            LyricsAnalysis.stats(LyricsFormat.parse(s.text)).words
        })
        results.add(expectEqual("Sample ${s.label}: syllables (estimate)", s.syllables) {
            LyricsAnalysis.stats(LyricsFormat.parse(s.text)).syllables
        })
        results.add(expectEqual("Sample ${s.label}: longest line", s.longestLine) {
            LyricsAnalysis.stats(LyricsFormat.parse(s.text)).longestLine
        })
    }

    // 2. Serialization (3 checks).
    results.add(expectEqual("Serialize(parse(A)) equals A", true) {
        LyricsFormat.serialize(LyricsFormat.parse(a)) == a
    })
    results.add(expectTrue("Serialized B is renumbered to [Verse 8]") {
        val written = LyricsFormat.serialize(LyricsFormat.parse(b))
        val ok = written.contains("[Verse 8]") && written != b
        ok to (if (ok) "renumbered" else "contains [Verse 8]: ${written.contains("[Verse 8]")}, differs from B: ${written != b}")
    })
    results.add(expectEqual("Serialize(parse(C)) equals C", true) {
        LyricsFormat.serialize(LyricsFormat.parse(c)) == c
    })

    // 3. Round trips (3 checks).
    for (s in samples) {
        results.add(expectEqual("Round trip of ${s.label}", true) {
            val first = LyricsFormat.parse(s.text)
            LyricsFormat.parse(LyricsFormat.serialize(first)) == first
        })
    }

    // 4. Per-section structure (3 checks).
    results.add(expectEqual("Lines per section of A", listOf(1, 4, 2, 4, 4, 4, 2, 2)) {
        LyricsFormat.parse(a).map { section -> section.text.split('\n').count { it.isNotBlank() } }
    })
    results.add(expectEqual(
        "Line syllables per section of A",
        listOf(listOf(8), listOf(8, 9, 9, 10), listOf(5, 6), listOf(6, 7, 6, 7), listOf(11, 10, 10, 11), listOf(6, 7, 6, 7), listOf(10, 9), listOf(3, 5)),
    ) { LyricsAnalysis.lineSyllables(LyricsFormat.parse(a)) })
    results.add(expectEqual("Line syllables per section of C", listOf(listOf(5, 5, 5, 19))) {
        LyricsAnalysis.lineSyllables(LyricsFormat.parse(c))
    })

    // 5. Outliers (2 checks).
    results.add(expectEqual("No outlier lines in A", true) {
        LyricsAnalysis.lineSyllables(LyricsFormat.parse(a)).all { LyricsAnalysis.outlierLines(it).isEmpty() }
    })
    results.add(expectEqual("Outlier line in C is index 3", setOf(3)) {
        LyricsAnalysis.outlierLines(LyricsAnalysis.lineSyllables(LyricsFormat.parse(c)).single())
    })

    // 6. Most repeated line (2 checks).
    results.add(expectEqual("Most repeated line of A", "i go reach, i go reach" to 4) {
        LyricsAnalysis.mostRepeatedLine(LyricsFormat.parse(a))
    })
    results.add(expectEqual("Most repeated line of C is none", null) {
        LyricsAnalysis.mostRepeatedLine(LyricsFormat.parse(c))
    })

    // 7. Words, lines, labels, edge round trip (4 checks).
    results.add(wordChecks())
    results.add(lineChecks())
    results.add(labelChecks())
    results.add(edgeRoundTrip())

    return results
}
