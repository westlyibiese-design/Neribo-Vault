package com.westly.neribovault.feature.screenplays.engine

/** Three fixed Fountain texts used by the debug tools, the engine self-test and the phone tests. */
object ScriptSamples {
    /** "Short sample": two pages, three scenes. */
    val sampleA: String = buildSampleA()

    /** "Three-page sample": sample A repeated three times, one blank line between the repeats. */
    val sampleB: String = buildSampleB()

    /** "Page-break test": a long action and a long speech that must split across two pages. */
    val sampleC: String = buildSampleC()

    private fun buildSampleA(): String {
        val paragraphs = listOf(
            "FADE IN:",
            "INT. TAILOR'S SHOP - BENIN CITY - MORNING",
            "A cramped shop. Bolts of ankara hang from the ceiling. " +
                "A radio plays highlife through the hum of a generator.",
            "ADAEZE (30s), calm and exact, pins a hem on a mannequin. " +
                "Her apprentice, TUNDE (19), watches the door.",
            "TUNDE\nMadam, the customer is outside. She says the dress was due yesterday.",
            "ADAEZE\n(not looking up)\nThen she is early for tomorrow.",
            "Tunde swallows a laugh. Adaeze turns at last, a pin between her teeth.",
            "ADAEZE (CONT'D)\nTell her to come in. A customer should see how the work is done.",
            "EXT. MARKET STREET - CONTINUOUS",
            "Okadas weave between hawkers. MRS. OSAGIE (50s), in a green gele, " +
                "checks her watch and sighs.",
            "MRS. OSAGIE\n(into her phone)\nYes, I am still waiting. No, I will not leave.",
            "TUNDE (V.O.)\nMadam says to come in. Please, ma.",
            "A danfo blares its horn. She flinches.",
            "CUT TO:",
            "INT. TAILOR'S SHOP - DAY",
            "Adaeze holds up the finished dress. Light from the window turns the fabric to gold.",
            "ADAEZE\nLook at the hem. Straight as a ruler.",
            "MRS. OSAGIE\nIt is beautiful. It is also one day late.",
            "ADAEZE\nThen I will make the next one a day early.",
            "FADE OUT.",
        )
        return paragraphs.joinToString("\n\n") + "\n"
    }

    private fun buildSampleB(): String = listOf(sampleA, sampleA, sampleA).joinToString("\n")

    private fun buildSampleC(): String {
        val s1 = "The generator coughs, the lights flicker, and nobody in the room says a word."
        val s2 = "I have waited three days for this dress and I will wait three more if I must."
        val action = List(25) { s1 }.joinToString(" ")
        val speech = List(20) { s2 }.joinToString(" ")
        return "INT. WORKSHOP - NIGHT\n\n" + action + "\n\nADAEZE\n" + speech +
            "\n\nShe finally looks up.\n\nTUNDE\nYes, madam.\n"
    }
}
