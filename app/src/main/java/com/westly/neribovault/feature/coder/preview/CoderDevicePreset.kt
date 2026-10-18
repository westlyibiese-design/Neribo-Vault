package com.westly.neribovault.feature.coder.preview

/**
 * The page widths the preview can be tried at. [widthDp] is the exact width in dp the page is laid out at,
 * or null for [Full], which uses the whole preview area.
 */
enum class CoderDevicePreset(val label: String, val widthDp: Int?) {
    Full("Full", null),
    Phone("Phone", 360),
    Tablet("Tablet", 768),
    Desktop("Desktop", 1200),
    ;

    /** The text on the size chip: "Full", "Phone 360", "Tablet 768" or "Desktop 1200". */
    val chipText: String get() = if (widthDp == null) label else "$label $widthDp"

    companion object {
        /** The preset called [name], or [Full] for an unknown or empty name (for example after an update). */
        fun fromName(name: String?): CoderDevicePreset = values().firstOrNull { it.name == name } ?: Full
    }
}

/** The width in dp the page is laid out at: the preset's own width, or [paneWidthDp] for [CoderDevicePreset.Full]. */
fun presetWidthDp(preset: CoderDevicePreset, paneWidthDp: Int): Int = preset.widthDp ?: paneWidthDp
