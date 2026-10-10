package com.westly.neribovault.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One two-step-verification account in the Authenticator vault. The secret key is never stored
 * in plain text: [secretCipher] and [secretIv] hold the AES-GCM output (Base64, no wrap) made
 * with an Android Keystore key, so it can only be read on this phone.
 */
@Entity(tableName = "totp_accounts")
data class TotpAccountEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val issuer: String,
    val accountName: String,
    val secretCipher: String,
    val secretIv: String,
    val algorithm: String,
    val digits: Int,
    val periodSeconds: Int,
    val isPinned: Boolean,
    val sortOrder: Int,
    val notes: String,
)
