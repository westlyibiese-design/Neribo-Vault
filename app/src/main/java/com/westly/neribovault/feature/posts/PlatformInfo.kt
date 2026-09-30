package com.westly.neribovault.feature.posts

import com.westly.neribovault.core.ui.components.BadgeTone

/** Stored status values of a post. */
const val STATUS_IDEA = "idea"
const val STATUS_DRAFT = "draft"
const val STATUS_SCHEDULED = "scheduled"
const val STATUS_POSTED = "posted"

/** Every status, in pipeline order. */
val POST_STATUSES = listOf(STATUS_IDEA, STATUS_DRAFT, STATUS_SCHEDULED, STATUS_POSTED)

/** Display name of a stored status. */
fun postStatusLabel(status: String): String = when (status) {
    STATUS_IDEA -> "Idea"
    STATUS_DRAFT -> "Draft"
    STATUS_SCHEDULED -> "Scheduled"
    STATUS_POSTED -> "Posted"
    else -> status.replaceFirstChar { it.uppercase() }
}

/** Scheduled posts are the only ones that get the accent badge. */
fun postStatusTone(status: String): BadgeTone =
    if (status == STATUS_SCHEDULED) BadgeTone.Accent else BadgeTone.Neutral

/**
 * What the app knows about one platform: its display name, the caption character limit
 * (null when there is none), the hashtag count above which to warn (null when there is no
 * such limit) and a short piece of hashtag advice.
 */
data class PlatformInfo(
    val key: String,
    val displayName: String,
    val charLimit: Int?,
    val maxHashtags: Int?,
    val hashtagAdvice: String,
)

/** All platforms, in the order the chips are shown. */
val POST_PLATFORMS: List<PlatformInfo> = listOf(
    PlatformInfo(
        key = "instagram",
        displayName = "Instagram",
        charLimit = 2_200,
        maxHashtags = 30,
        hashtagAdvice = "Up to 30 hashtags. Three to ten well-chosen ones usually read best.",
    ),
    PlatformInfo(
        key = "x",
        displayName = "X",
        charLimit = 280,
        maxHashtags = null,
        hashtagAdvice = "One or two is plenty. They share the 280 characters with your caption.",
    ),
    PlatformInfo(
        key = "facebook",
        displayName = "Facebook",
        charLimit = 5_000,
        maxHashtags = null,
        hashtagAdvice = "Hashtags matter little here. One to three is enough.",
    ),
    PlatformInfo(
        key = "tiktok",
        displayName = "TikTok",
        charLimit = 2_200,
        maxHashtags = null,
        hashtagAdvice = "Three to five, mixing broad and niche tags, works well.",
    ),
    PlatformInfo(
        key = "whatsapp_status",
        displayName = "WhatsApp Status",
        charLimit = 700,
        maxHashtags = null,
        hashtagAdvice = "Hashtags do nothing on Status. You can skip them.",
    ),
    PlatformInfo(
        key = "linkedin",
        displayName = "LinkedIn",
        charLimit = 3_000,
        maxHashtags = null,
        hashtagAdvice = "Three to five relevant hashtags at the end of the post works well.",
    ),
    PlatformInfo(
        key = "youtube",
        displayName = "YouTube",
        charLimit = 5_000,
        maxHashtags = null,
        hashtagAdvice = "The first three hashtags appear above the video title.",
    ),
    PlatformInfo(
        key = "other",
        displayName = "Other",
        charLimit = null,
        maxHashtags = null,
        hashtagAdvice = "No limit is set for this platform.",
    ),
)

private val OTHER_PLATFORM: PlatformInfo = POST_PLATFORMS.last()

/** The [PlatformInfo] for a stored platform value; unknown values fall back to Other. */
fun platformInfo(key: String): PlatformInfo =
    POST_PLATFORMS.firstOrNull { it.key == key } ?: OTHER_PLATFORM
