package com.westly.neribovault.feature.recent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.search.GlobalIndex
import com.westly.neribovault.core.search.IndexedItem
import com.westly.neribovault.core.vault.VaultCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One vault filter chip. */
data class RecentChip(val vaultId: String, val label: String)

/** Everything the Recent screen shows. */
data class RecentUiState(
    val isLoading: Boolean = true,
    val hasAnyItems: Boolean = false,
    val chips: List<RecentChip> = emptyList(),
    val selectedVault: String? = null,
    val sections: List<RecentSection> = emptyList(),
)

/**
 * The most recently changed items across every vault that is not locked. Reloaded each time the
 * screen is shown again, so edits made elsewhere appear straight away.
 */
class RecentViewModel(private val index: GlobalIndex) : ViewModel() {

    private val _state = MutableStateFlow(RecentUiState())
    val state: StateFlow<RecentUiState> = _state.asStateFlow()

    /** Every indexed item, newest first. */
    private var all: List<IndexedItem> = emptyList()
    private var selectedVault: String? = null
    private var loadJob: Job? = null

    /** Reloads the list without the vaults in [hiddenVaults]. */
    fun refresh(hiddenVaults: Set<String>) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val loaded = try {
                index.load(hiddenVaults)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            all = withContext(Dispatchers.Default) { loaded.sortedByDescending { it.updatedAt } }
            publish()
        }
    }

    /** Filters to one vault, or to every vault when [vaultId] is null. */
    fun selectVault(vaultId: String?) {
        selectedVault = vaultId
        publish()
    }

    private fun publish() {
        val present = HashSet<String>()
        for (item in all) present.add(item.vaultId)
        val chips = VaultCatalog.all
            .filter { it.id in present }
            .map { RecentChip(it.id, it.name) }
        val effective = selectedVault?.takeIf { it in present }
        val filtered = if (effective == null) all else all.filter { it.vaultId == effective }
        val sections = groupRecent(filtered.take(RECENT_LIMIT), System.currentTimeMillis())
        _state.value = RecentUiState(
            isLoading = false,
            hasAnyItems = all.isNotEmpty(),
            chips = chips,
            selectedVault = effective,
            sections = sections,
        )
    }
}
