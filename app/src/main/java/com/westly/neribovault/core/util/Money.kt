package com.westly.neribovault.core.util

/** Formats a whole-naira amount as "₦1,250,000". Negative amounts read "-₦1,000". */
fun formatNaira(amount: Long): String {
    val digits = amount.toString().removePrefix("-")
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    val sign = if (amount < 0) "-" else ""
    return "${sign}₦$grouped"
}
