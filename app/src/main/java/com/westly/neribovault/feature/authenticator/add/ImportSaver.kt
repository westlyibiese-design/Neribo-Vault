package com.westly.neribovault.feature.authenticator.add

import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.TotpAccountEntity
import com.westly.neribovault.data.repository.TotpAccountsRepository
import com.westly.neribovault.feature.authenticator.engine.OtpEntry
import com.westly.neribovault.feature.authenticator.security.AuthenticatorCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How many accounts were saved and how many could not be. */
class SaveResult(val saved: Int, val failed: Int)

/** Encrypts and stores imported accounts. Every secret array it is given is zero-filled after use. */
class ImportSaver(private val accounts: TotpAccountsRepository) {

    /** Saves [entries] in order. An entry that cannot be encrypted or stored is skipped and counted. */
    suspend fun save(entries: List<OtpEntry>): SaveResult = withContext(Dispatchers.Default) {
        var nextOrder = accounts.maxSortOrder()
        var saved = 0
        var failed = 0
        for (entry in entries) {
            try {
                val sealed = AuthenticatorCrypto.encrypt(entry.secret)
                if (sealed == null) {
                    failed++
                } else {
                    val now = System.currentTimeMillis()
                    nextOrder += 1
                    accounts.upsert(
                        TotpAccountEntity(
                            id = newId(),
                            createdAt = now,
                            updatedAt = now,
                            issuer = entry.issuer.trim().take(MAX_NAME),
                            accountName = entry.accountName.trim().take(MAX_NAME),
                            secretCipher = sealed.ciphertext,
                            secretIv = sealed.iv,
                            algorithm = entry.algorithm.name,
                            digits = entry.digits,
                            periodSeconds = entry.periodSeconds,
                            isPinned = false,
                            sortOrder = nextOrder,
                            notes = "",
                        ),
                    )
                    saved++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
            } finally {
                entry.secret.fill(0)
            }
        }
        SaveResult(saved, failed)
    }

    private companion object {
        const val MAX_NAME = 60
    }
}
