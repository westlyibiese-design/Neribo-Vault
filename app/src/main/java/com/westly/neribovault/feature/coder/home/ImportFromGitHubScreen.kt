package com.westly.neribovault.feature.coder.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboSearchField
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.feature.coder.github.CoderGitHubRepos
import com.westly.neribovault.feature.coder.github.GitHubRepoSummary
import com.westly.neribovault.feature.coder.zip.ImportReportDialog
import com.westly.neribovault.feature.coder.zip.ZipMessageDialog

/**
 * Import from GitHub: type `owner/name` or paste a GitHub address, or pick one of your own repositories when GitHub is
 * connected, and the code becomes a new project. Without a connection only public repositories can be imported.
 * [onOpenProject] opens the new project; [onBack] leaves (which also stops a running import).
 */
@Composable
fun ImportFromGitHubScreen(onBack: () -> Unit, onOpenProject: (String) -> Unit) {
    val cacheDir = LocalContext.current.applicationContext.cacheDir
    val vm = neriboViewModel { c ->
        ImportFromGitHubViewModel(c.coderProjectsRepository, c.coderFileStore, c.coderGitHubSettings, cacheDir)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val visibleRepos = CoderGitHubRepos.filterRepos(state.repos, state.filter)

    NeriboScaffold(topBar = { NeriboTopBar(title = "Import from GitHub", onBack = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = spacing.screen, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                Text(
                    text = "Type a repository like owner/name, or paste its GitHub address. It becomes a new project on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                NeriboTextField(
                    value = state.reference,
                    onValueChange = { vm.setReference(it) },
                    label = "Repository",
                    placeholder = "owner/name",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Uri),
                )
            }
            item {
                if (state.busy) {
                    BusyCard(state = state, onCancel = { vm.cancelImport() })
                } else {
                    NeriboButton(
                        text = "Import",
                        onClick = { vm.startImport() },
                        enabled = state.reference.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (!state.connected) {
                item {
                    Text(
                        text = "Connect GitHub to import private repositories.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item { SectionHeader(text = "Your repositories", modifier = Modifier.padding(top = spacing.md)) }
                item {
                    NeriboSearchField(query = state.filter, onQueryChange = { vm.setFilter(it) }, placeholder = "Search your repositories")
                }
                val message = state.reposMessage
                when {
                    state.reposLoading -> item {
                        Text(
                            text = "Loading your repositories…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    message != null -> item {
                        Column {
                            Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { vm.loadRepos() }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(text = "Try again", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    visibleRepos.isEmpty() -> item {
                        Text(
                            text = if (state.repos.isEmpty()) "You have no repositories yet." else "No repository matches your search.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> items(visibleRepos, key = { it.fullName }) { repo ->
                        RepoRow(repo = repo, enabled = !state.busy, onChoose = { vm.choose(repo) })
                    }
                }
            }
        }
    }

    val failure = state.error
    if (failure != null) {
        ZipMessageDialog(title = "Couldn't import", message = failure, onDismiss = { vm.dismissError() })
    }
    val done = state.done
    if (done != null) {
        ImportReportDialog(
            report = done.report,
            onOpen = {
                vm.consumeDone()
                onOpenProject(done.projectId)
            },
            onClose = {
                vm.consumeDone()
                onBack()
            },
        )
    }
}

/** The running import: what it is doing, a bar (filled when the size is known) and Cancel. */
@Composable
private fun BusyCard(state: ImportFromGitHubUiState, onCancel: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val title = when (state.phase) {
        ImportPhase.Checking -> "Checking the repository…"
        ImportPhase.Downloading -> if (state.totalBytes > 0L) {
            "Downloading ${megabytes(state.downloadedBytes)} of ${megabytes(state.totalBytes)}"
        } else {
            "Downloading ${megabytes(state.downloadedBytes)}"
        }
        ImportPhase.Unpacking -> "Unpacking…"
        ImportPhase.Idle -> ""
    }
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.lg)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            Spacer(modifier = Modifier.height(spacing.sm))
            if (state.phase == ImportPhase.Downloading && state.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = (state.downloadedBytes.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.primary,
                    trackColor = colors.surfaceVariant,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = colors.primary, trackColor = colors.surfaceVariant)
            }
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(text = "Cancel", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** One repository of the list: its name, whether it is private and its size. A tap puts it in the field. */
@Composable
private fun RepoRow(repo: GitHubRepoSummary, enabled: Boolean, onChoose: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val details = buildList {
        add(if (repo.isPrivate) "Private" else "Public")
        if (repo.sizeKb > 0L) add(megabytes(repo.sizeKb * 1024L))
    }.joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, onClickLabel = "Use ${repo.fullName}") { onChoose() }
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = repo.fullName,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = details, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(spacing.sm))
    }
}

/** A size in megabytes with one decimal, such as "12.4 MB"; "under 0.1 MB" for tiny sizes. */
private fun megabytes(bytes: Long): String {
    val tenths = (bytes * 10L) / (1024L * 1024L)
    if (tenths <= 0L) return "under 0.1 MB"
    return (tenths / 10L).toString() + "." + (tenths % 10L).toString() + " MB"
}
