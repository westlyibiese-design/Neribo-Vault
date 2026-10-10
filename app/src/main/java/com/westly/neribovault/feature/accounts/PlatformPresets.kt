package com.westly.neribovault.feature.accounts

/**
 * One platform the owner can pick when creating an account. [id] is what is stored in
 * `AccountEntity.platform`; [url] is the login or dashboard link; [defaultItemType] and
 * [itemWord] describe what usually lives under that account ("Projects", "Pages and handles").
 * [logoName] is the name of the bundled logo in res/drawable-nodpi ("logo_<id>"), or null when
 * the preset has none; a name whose file is missing falls back to the letter avatar.
 */
data class PlatformPreset(
    val id: String,
    val name: String,
    val category: String,
    val url: String,
    val defaultItemType: String,
    val itemWord: String,
    val logoName: String? = "logo_$id",
)

/** The platforms offered when creating an account, plus helpers for showing a stored platform. */
object PlatformPresets {
    /** The id of the "Other platform" preset, which asks for a typed name. */
    const val CUSTOM_ID = "custom"

    /** The presets in the order they are listed. */
    val all: List<PlatformPreset> = listOf(
        PlatformPreset("supabase", "Supabase", "Developer", "https://supabase.com/dashboard", "project", "Projects"),
        PlatformPreset("firebase", "Firebase", "Developer", "https://console.firebase.google.com", "project", "Projects"),
        PlatformPreset("cloudflare", "Cloudflare", "Developer", "https://dash.cloudflare.com", "domain", "Sites and domains"),
        PlatformPreset("github", "GitHub", "Developer", "https://github.com/login", "project", "Repositories"),
        PlatformPreset("netlify", "Netlify", "Developer", "https://app.netlify.com", "project", "Sites"),
        PlatformPreset("vercel", "Vercel", "Developer", "https://vercel.com/login", "project", "Projects"),
        PlatformPreset("replit", "Replit", "Developer", "https://replit.com/login", "project", "Projects"),
        PlatformPreset("google_cloud", "Google Cloud", "Developer", "https://console.cloud.google.com", "project", "Projects"),
        PlatformPreset("play_console", "Google Play Console", "Developer", "https://play.google.com/console", "app", "Apps"),
        PlatformPreset("apple_developer", "Apple Developer", "Developer", "https://developer.apple.com/account", "app", "Apps"),
        PlatformPreset("google", "Google account", "Accounts", "https://accounts.google.com", "other", "Items"),
        PlatformPreset("instagram", "Instagram", "Social", "https://www.instagram.com/accounts/login", "page", "Pages and handles"),
        PlatformPreset("facebook", "Facebook", "Social", "https://www.facebook.com/login", "page", "Pages"),
        PlatformPreset("x", "X (Twitter)", "Social", "https://x.com/login", "page", "Handles"),
        PlatformPreset("tiktok", "TikTok", "Social", "https://www.tiktok.com/login", "page", "Handles"),
        PlatformPreset("linkedin", "LinkedIn", "Social", "https://www.linkedin.com/login", "page", "Pages"),
        PlatformPreset("youtube", "YouTube", "Social", "https://www.youtube.com", "page", "Channels"),
        PlatformPreset("telegram", "Telegram", "Social", "https://web.telegram.org", "page", "Channels"),
        PlatformPreset("namecheap", "Namecheap", "Domains and hosting", "https://www.namecheap.com/myaccount/login", "domain", "Domains"),
        PlatformPreset("godaddy", "GoDaddy", "Domains and hosting", "https://sso.godaddy.com", "domain", "Domains"),
        PlatformPreset("paystack", "Paystack", "Payments", "https://dashboard.paystack.com", "api", "Integrations"),
        PlatformPreset("flutterwave", "Flutterwave", "Payments", "https://app.flutterwave.com", "api", "Integrations"),
        PlatformPreset(CUSTOM_ID, "Other platform", "Other", "", "other", "Items", logoName = null),
    )

    /** The categories in the order they first appear in [all]. */
    val categories: List<String> = all.map { it.category }.distinct()

    /** The preset with this [id], or null when the stored platform is a custom name. */
    fun find(id: String): PlatformPreset? = all.firstOrNull { it.id == id }

    /** What to show for a stored platform: the preset's name, or the custom text itself. */
    fun displayName(platform: String): String = find(platform)?.name ?: platform.trim()

    /** The single upper-case letter shown in the avatar, or "?" when there is no name. */
    fun avatarLetter(platform: String): String {
        val first = displayName(platform).firstOrNull { !it.isWhitespace() } ?: return "?"
        return first.uppercase()
    }

    /** The sign-in method a new account starts with: Google for the Google account, phone for Telegram. */
    fun defaultSignInMethod(presetId: String): String = when (presetId) {
        "google" -> SIGN_IN_GOOGLE
        "telegram" -> SIGN_IN_PHONE
        else -> SIGN_IN_EMAIL_PASSWORD
    }
}

const val SIGN_IN_GOOGLE = "google"
const val SIGN_IN_EMAIL_PASSWORD = "email_password"
const val SIGN_IN_PHONE = "phone"
const val SIGN_IN_GITHUB = "github"
const val SIGN_IN_APPLE = "apple"
const val SIGN_IN_OTHER = "other"

const val ACCOUNT_STATUS_ACTIVE = "active"
const val ACCOUNT_STATUS_INACTIVE = "inactive"
const val ACCOUNT_STATUS_CLOSED = "closed"

/**
 * The sign-in method as shown to the owner. For "other", [otherName] (the app or service the
 * owner typed) is shown instead of the word "Other" when it is not blank.
 */
fun signInMethodLabel(code: String, otherName: String? = null): String = when (code) {
    SIGN_IN_GOOGLE -> "Google"
    SIGN_IN_EMAIL_PASSWORD -> "Email and password"
    SIGN_IN_PHONE -> "Phone number"
    SIGN_IN_GITHUB -> "GitHub"
    SIGN_IN_APPLE -> "Apple"
    else -> otherName?.trim()?.takeIf { it.isNotEmpty() } ?: "Other"
}

/** Whether this sign-in method has a password field (only email and password). */
fun signInMethodUsesPassword(code: String): Boolean = code == SIGN_IN_EMAIL_PASSWORD

/** The item type as shown to the owner. */
fun itemTypeLabel(code: String): String = when (code) {
    "project" -> "Project"
    "app" -> "App"
    "page" -> "Page or handle"
    "domain" -> "Domain"
    "database" -> "Database"
    "bucket" -> "Storage bucket"
    "api" -> "API or integration"
    "server" -> "Server"
    else -> "Other"
}

/** The two-factor setting as shown to the owner. */
fun twoFactorLabel(code: String): String = when (code) {
    "none" -> "None"
    "authenticator" -> "Authenticator app"
    "sms" -> "SMS"
    "email" -> "Email"
    "hardware" -> "Security key"
    else -> "Other"
}

/** The account status as shown to the owner. */
fun accountStatusLabel(code: String): String = when (code) {
    ACCOUNT_STATUS_INACTIVE -> "Inactive"
    ACCOUNT_STATUS_CLOSED -> "Closed"
    else -> "Active"
}

/** The item status as shown to the owner. */
fun itemStatusLabel(code: String): String = when (code) {
    "paused" -> "Paused"
    "archived" -> "Archived"
    else -> "Active"
}
