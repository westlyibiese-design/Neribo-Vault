package com.westly.neribovault.feature.screenplays.engine

/** The outcome of one engine check. [detail] says what was expected and what was found. */
data class CheckResult(val name: String, val passed: Boolean, val detail: String)

/** What the engine produced for one sample text. */
private class SampleRun(
    val blocks: List<ScriptBlock>,
    val paginated: PaginatedScript,
    val characters: Int,
    val contdCues: Int,
)

/** What the reference implementation produced for one sample (see the S1 prompt). */
private class SampleExpectation(
    val label: String,
    val text: String,
    val blocks: Int,
    val pages: Int,
    val scenes: Int,
    val characters: Int,
    val contdCues: Int,
    val rowsPerPage: List<Int>?,
    val scenePages: List<Int>?,
)

private fun analyse(text: String): SampleRun {
    val blocks = Fountain.parse(text)
    val paginated = ScriptPaginator.paginate(blocks)
    val characters = blocks
        .filter { it.type == BlockType.CHARACTER }
        .map { speakerOf(it.text).uppercase() }
        .toSet()
        .size
    // Count cues that carry the automatic marker; the repeated cue after a split speech does not count.
    val contdCues = paginated.pages
        .flatMap { it.rows }
        .filter { row ->
            row.type == BlockType.CHARACTER &&
                row.text.endsWith("(CONT'D)") &&
                blocks.getOrNull(row.blockIndex)?.type == BlockType.CHARACTER
        }
        .map { it.blockIndex }
        .toSet()
        .size
    return SampleRun(blocks, paginated, characters, contdCues)
}

private fun rowsUsed(page: ScriptPage): Int = (page.rows.maxOfOrNull { it.row } ?: -1) + 1

private inline fun runCheck(name: String, body: () -> CheckResult): CheckResult =
    try {
        body()
    } catch (e: Exception) {
        CheckResult(name, false, "threw " + e.javaClass.simpleName + ": " + (e.message ?: "no message"))
    }

private fun expectEqual(name: String, expected: Any, actual: () -> Any): CheckResult =
    runCheck(name) {
        val found = actual()
        CheckResult(name, found == expected, "expected $expected, got $found")
    }

private fun sampleChecks(spec: SampleExpectation): List<CheckResult> {
    val label = spec.label
    val run = lazy { analyse(spec.text) }
    val results = ArrayList<CheckResult>()
    results.add(expectEqual("$label: block count", spec.blocks) { run.value.blocks.size })
    results.add(expectEqual("$label: page count", spec.pages) { run.value.paginated.pageCount })
    results.add(expectEqual("$label: scene count", spec.scenes) { run.value.paginated.scenes.size })
    results.add(expectEqual("$label: distinct characters", spec.characters) { run.value.characters })
    results.add(expectEqual("$label: CONT'D cues", spec.contdCues) { run.value.contdCues })
    val rowsPerPage = spec.rowsPerPage
    if (rowsPerPage != null) {
        results.add(
            expectEqual("$label: rows used per page", rowsPerPage) {
                run.value.paginated.pages.map { rowsUsed(it) }
            },
        )
    }
    val scenePages = spec.scenePages
    if (scenePages != null) {
        results.add(
            expectEqual("$label: scene page numbers", scenePages) {
                run.value.paginated.scenes.map { it.page }
            },
        )
    }
    return results
}

private fun pageBreakChecks(): List<CheckResult> {
    val run = lazy { analyse(ScriptSamples.sampleC) }
    val more = runCheck("C: last row of page 1 is (MORE)") {
        val last = run.value.paginated.pages[0].rows.last()
        CheckResult(
            "C: last row of page 1 is (MORE)",
            last.text == "(MORE)" && last.col == 37,
            "expected (MORE) at column 37, got \"${last.text}\" at column ${last.col}",
        )
    }
    val contd = runCheck("C: page 2 starts with the repeated cue") {
        val first = run.value.paginated.pages[1].rows.first()
        CheckResult(
            "C: page 2 starts with the repeated cue",
            first.text == "ADAEZE (CONT'D)" && first.row == 0 && first.col == 37,
            "expected ADAEZE (CONT'D) at row 0, column 37, got \"${first.text}\" at row ${first.row}, column ${first.col}",
        )
    }
    return listOf(more, contd)
}

private fun roundTripCheck(label: String, text: String): CheckResult {
    val name = "$label: parse, serialize, parse round trip"
    return runCheck(name) {
        val once = Fountain.parse(text)
        val twice = Fountain.parse(Fountain.serialize(once))
        CheckResult(name, once == twice, "${once.size} blocks, then ${twice.size} blocks")
    }
}

private fun forcedMarkerCheck(): CheckResult {
    val name = "Forced markers are written and read back"
    return runCheck(name) {
        val blocks = listOf(
            ScriptBlock(BlockType.ACTION, "INT. is how scripts start"),
            ScriptBlock(BlockType.ACTION, "BANG!"),
            ScriptBlock(BlockType.SCENE_HEADING, "THE BAR"),
            ScriptBlock(BlockType.CHARACTER, "ADAEZE"),
            ScriptBlock(BlockType.DIALOGUE, "Hello."),
            ScriptBlock(BlockType.TRANSITION, "FADE OUT"),
            ScriptBlock(BlockType.PAGE_BREAK, ""),
        )
        val expectedText =
            "!INT. is how scripts start\n\nBANG!\n\n.THE BAR\n\nADAEZE\nHello.\n\n>FADE OUT\n\n===\n"
        val text = Fountain.serialize(blocks)
        val back = Fountain.parse(text)
        CheckResult(
            name,
            text == expectedText && back == blocks,
            "text matches: ${text == expectedText}, blocks match: ${back == blocks}",
        )
    }
}

/** Runs every engine check and returns one result per check. Never throws. */
fun runEngineSelfTest(): List<CheckResult> {
    val results = ArrayList<CheckResult>()
    results.addAll(
        sampleChecks(
            SampleExpectation(
                label = "A",
                text = ScriptSamples.sampleA,
                blocks = 30,
                pages = 2,
                scenes = 3,
                characters = 3,
                contdCues = 1,
                rowsPerPage = listOf(53, 9),
                scenePages = listOf(1, 1, 1),
            ),
        ),
    )
    results.addAll(
        sampleChecks(
            SampleExpectation(
                label = "B",
                text = ScriptSamples.sampleB,
                blocks = 90,
                pages = 4,
                scenes = 9,
                characters = 3,
                contdCues = 3,
                rowsPerPage = listOf(53, 53, 52, 30),
                scenePages = listOf(1, 1, 1, 2, 2, 3, 3, 3, 4),
            ),
        ),
    )
    results.addAll(
        sampleChecks(
            SampleExpectation(
                label = "C",
                text = ScriptSamples.sampleC,
                blocks = 7,
                pages = 2,
                scenes = 1,
                characters = 2,
                contdCues = 0,
                rowsPerPage = null,
                scenePages = null,
            ),
        ),
    )
    results.addAll(pageBreakChecks())
    results.add(roundTripCheck("A", ScriptSamples.sampleA))
    results.add(roundTripCheck("B", ScriptSamples.sampleB))
    results.add(roundTripCheck("C", ScriptSamples.sampleC))
    results.add(forcedMarkerCheck())
    return results
}
