package com.westly.neribovault.feature.authenticator.backup

import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.TotpAccountEntity
import com.westly.neribovault.data.repository.TotpAccountsRepository
import com.westly.neribovault.feature.authenticator.add.SaveResult
import com.westly.neribovault.feature.authenticator.security.AuthenticatorCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Encrypts and stores accounts from a backup file under this phone's key. Notes and the pinned flag
 * are kept. Existing accounts are never changed. Every secret array it is given is zero-filled.
 */
class BackupRestoreSaver(private val accounts: TotpAccountsRepository) {

    /** Saves [items] in order. An item that cannot be encrypted or stored is skipped and counted. */
    suspend fun save(items: List<BackupAccount>): SaveResult = withContext(Dispatchers.Default) {
        var nextOrder = accounts.maxSortOrder()
        var saved = 0
        var failed = 0
        for (item in items) {
            try {
                val sealed = AuthenticatorCrypto.encrypt(item.secret)
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
                            issuer = item.issuer.trim().take(MAX_NAME),
                            accountName = item.accountName.trim().take(MAX_NAME),
                            secretCipher = sealed.ciphertext,
                            secretIv = sealed.iv,
                            algorithm = item.algorithm,
                            digits = item.digits,
                            periodSeconds = item.periodSeconds,
                            isPinned = item.isPinned,
                            sortOrder = nextOrder,
                            notes = item.notes,
                        ),
                    )
                    saved++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
            } finally {
                item.secret.fill(0)
            }
        }
        SaveResult(saved, failed)
    }

    private companion object {
        const val MAX_NAME = 60
    }
}
