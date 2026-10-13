package com.westly.neribovault.feature.coder.ai

/** The three AI companies Coder can talk to. The [id] is stored on the phone, so it must never change. */
enum class CoderAiProvider(val id: String, val displayName: String, val company: String) {
    CLAUDE("claude", "Claude", "Anthropic"),
    GEMINI("gemini", "Gemini", "Google"),
    OPENAI("openai", "OpenAI", "OpenAI");

    /** Where a person makes a key. Fixed text, shown as a help link and never opened automatically. */
    val keysPageUrl: String
        get() = when (this) {
            CLAUDE -> "https://console.anthropic.com/settings/keys"
            GEMINI -> "https://aistudio.google.com/apikey"
            OPENAI -> "https://platform.openai.com/api-keys"
        }

    companion object {
        /** The provider stored under [id], or null for null and for unknown text. */
        fun fromId(id: String?): CoderAiProvider? = values().firstOrNull { it.id == id }
    }
}

/**
 * Everything a request to a provider needs. [apiKey] lives in memory only for the length of one call.
 * [toString] hides the key so it cannot end up in a log or an error message by accident.
 */
data class CoderAiConfig(val provider: CoderAiProvider, val apiKey: String, val model: String) {
    override fun toString(): String = "CoderAiConfig(provider=${provider.id}, model=$model, apiKey=hidden)"
}
