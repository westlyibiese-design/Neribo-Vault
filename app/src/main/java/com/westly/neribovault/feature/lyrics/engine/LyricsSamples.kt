package com.westly.neribovault.feature.lyrics.engine

/** Original sample lyrics for the debug tools, the self-test and the phone tests. */
object LyricsSamples {

    // Ọmọ = \u1ECCm\u1ECD ; Ẹ̀gbọ́n = \u1EB8\u0300gb\u1ECD\u0301n
    val sampleA: String = listOf(
        "[Intro]",
        "Mmm, Lagos lights dey call my name",
        "",
        "[Verse 1]",
        "Morning come, the danfo dey honk",
        "I carry my dream inside my pocket",
        "Mama pray for me before I go",
        "She say, \"My pikin, the road go open\"",
        "",
        "[Pre-Chorus]",
        "Small small, step by step",
        "No rush, no rush, no rush",
        "",
        "[Chorus]",
        "I go reach, I go reach",
        "Even if the road be long",
        "I go reach, I go reach",
        "My song dey follow my feet",
        "",
        "[Verse 2]",
        "Evening come, the generator dey sing",
        "Neighbours dey laugh, the pepper soup dey boil",
        "\u1ECCm\u1ECD mi, don't you worry for tomorrow",
        "\u1EB8\u0300gb\u1ECD\u0301n mi dey wait for me at the bus stop",
        "",
        "[Chorus]",
        "I go reach, I go reach",
        "Even if the road be long",
        "I go reach, I go reach",
        "My song dey follow my feet",
        "",
        "[Bridge]",
        "When the light go off, I go sing louder",
        "When the rain fall, I go dance in it",
        "",
        "[Outro]",
        "I go reach",
        "Lagos, I don reach",
    ).joinToString("\n") + "\n"

    /** Sample A four times, one blank line between the repeats. */
    val sampleB: String = List(4) { sampleA.removeSuffix("\n") }.joinToString("\n\n") + "\n"

    val sampleC: String = listOf(
        "[Verse 1]",
        "I go reach the place",
        "I go see my face",
        "I go win the race",
        "Tomorrow, tomorrow, when the morning sun come out and shine on all of us",
    ).joinToString("\n") + "\n"
}
