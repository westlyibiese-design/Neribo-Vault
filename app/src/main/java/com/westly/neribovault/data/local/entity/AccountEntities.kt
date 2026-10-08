package com.westly.neribovault.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One online account in the Accounts vault (a platform plus how the owner signs in to it).
 * Only the password is encrypted ([passwordCipher] and [passwordIv], Base64 AES-GCM output);
 * every other field is plain text. [passwordDecoy] is the optional text shown instead of the
 * password in the second-PIN session.
 *
 * [platform] is a preset id (see `PlatformPresets`) or the custom name the owner typed.
 * [signInMethod] is "google", "email_password", "phone", "github", "apple" or "other".
 * [twoFactor] is "none", "authenticator", "sms", "email", "hardware" or "other".
 * [status] is "active", "inactive" or "closed".
 */
@Entity(tableName = "platform_accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val platform: String,
    val name: String,
    val signInMethod: String,
    val loginId: String,
    val url: String,
    val twoFactor: String,
    val recovery: String,
    val notes: String,
    val status: String,
    val tags: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val passwordCipher: String? = null,
    val passwordIv: String? = null,
    val passwordDecoy: String? = null,
)

/**
 * Something that lives under an account: a project, app, page, domain, database, bucket, API or
 * server. [itemType] is "project", "app", "page", "domain", "database", "bucket", "api", "server"
 * or "other"; [status] is "active", "paused" or "archived". Everything here is plain text.
 */
@Entity(tableName = "account_items", indices = [Index("accountId")])
data class AccountItemEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val accountId: String,
    val name: String,
    val itemType: String,
    val url: String,
    val identifier: String,
    val status: String,
    val notes: String,
)

/**
 * A custom field on an account or an item. [ownerType] is "account" or "item". When [isSecret]
 * is false the value is in [valuePlain]; when it is true the value is encrypted in [valueCipher]
 * and [valueIv]. [valueDecoy] is the optional text shown instead of the value in the second-PIN
 * session.
 */
@Entity(tableName = "account_fields", indices = [Index("ownerId")])
data class AccountFieldEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val ownerType: String,
    val ownerId: String,
    val label: String,
    val isSecret: Boolean,
    val valuePlain: String? = null,
    val valueCipher: String? = null,
    val valueIv: String? = null,
    val valueDecoy: String? = null,
    val sortOrder: Int,
)
