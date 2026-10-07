package com.westly.neribovault.feature.lyrics.details

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.util.LifecycleSaveEffect
import com.westly.neribovault.feature.lyrics.STATUS_DRAFT
import com.westly.neribovault.feature.lyrics.STATUS_FINISHED
import com.westly.neribovault.feature.lyrics.STATUS_IDEA

private val KEY_NOTES = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
private val MOODS = listOf("Joyful", "Reflective", "Worship", "Love", "Party", "Protest", "Sad", "Hopeful", "Prayer")
private val STATUSES = listOf("Idea" to STATUS_IDEA, "Draft" to STATUS_DRAFT, "Finished" to STATUS_FINISHED)

/**
 * Title, writer, key, tempo (with tap tempo), mood, status and private notes of one song.
 * Everything saves by itself and never touches the lyrics.
 */
@Composable
fun SongDetailsScreen(songId: String, onBack: () -> Unit) {
    val vm = neriboViewModel(key = "lyrics-details-$songId") { c ->
        SongDetailsViewModel(c.songsRepository, songId)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme

    LifecycleSaveEffect(onSave = { vm.flush() })

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Song details",
                onBack = {
                    vm.flush()
                    onBack()
                },
                subtitle = if (state.isLoading || state.notFound) null else if (state.isSaving) "Saving\u2026" else "Saved",
            )
        },
    ) { padding ->
        when {
            state.isLoading -> Spacer(modifier = Modifier.fillMaxSize().padding(padding))
            state.notFound -> EmptyState(
                icon = Icons.Outlined.MusicNote,
                title = "Song not found",
                message = "This song may have been deleted or moved.",
                modifier = Modifier.fillMaxSize().padding(padding),
                actionLabel = "Back",
                onAction = onBack,
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen, vertical = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                NeriboTextField(
                    value = state.title,
                    onValueChange = vm::onTitleChange,
                    label = "Title",
                    placeholder = "Lagos Lights",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                NeriboTextField(
                    value = state.writer,
                    onValueChange = vm::onWriterChange,
                    label = "Writer",
                    placeholder = "Writer or artist",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )

                SectionHeader(text = "Key", modifier = Modifier.padding(top = spacing.sm))
                NeriboTextField(
                    value = state.songKey,
                    onValueChange = vm::onKeyChange,
                    placeholder = "e.g. G major",
                )
                val currentMode = keyMode(state.songKey)
                val currentNote = keyWithoutMode(state.songKey)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    KEY_NOTES.forEach { note ->
                        NeriboChip(
                            label = note,
                            selected = currentNote == note,
                            onClick = { vm.onKeyNote(note) },
                        )
                    }
                    NeriboChip(
                        label = "major",
                        selected = currentMode == "major",
                        onClick = { vm.onKeyMode("major") },
                    )
                    NeriboChip(
                        label = "minor",
                        selected = currentMode == "minor",
                        onClick = { vm.onKeyMode("minor") },
                    )
                }

                SectionHeader(text = "Tempo", modifier = Modifier.padding(top = spacing.sm))
                NeriboTextField(
                    value = state.tempoText,
                    onValueChange = vm::onTempoChange,
                    placeholder = "Beats per minute, e.g. 96",
                    isError = state.tempoError,
                    supportingText = if (state.tempoError) "Enter a number from 20 to 300" else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    NeriboButton(
                        text = "Tap tempo",
                        onClick = vm::onTapTempo,
                        style = ButtonStyle.Secondary,
                    )
                    val detected = state.tapBpm
                    if (detected != null) {
                        Text(
                            text = "$detected BPM",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        NeriboButton(
                            text = "Use",
                            onClick = vm::useTapTempo,
                            style = ButtonStyle.Primary,
                        )
                    } else {
                        Text(
                            text = "Tap along to the beat",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                SectionHeader(text = "Mood", modifier = Modifier.padding(top = spacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    MOODS.forEach { mood ->
                        NeriboChip(
                            label = mood,
                            selected = state.mood.trim().equals(mood, ignoreCase = true),
                            onClick = { vm.onMoodChip(mood) },
                        )
                    }
                }
                NeriboTextField(
                    value = state.mood,
                    onValueChange = vm::onMoodChange,
                    placeholder = "Or describe the mood in your own words",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )

                SectionHeader(text = "Status", modifier = Modifier.padding(top = spacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    STATUSES.forEach { (label, value) ->
                        NeriboChip(
                            label = label,
                            selected = state.status == value,
                            onClick = { vm.onStatusChange(value) },
                        )
                    }
                }

                SectionHeader(text = "Private notes", modifier = Modifier.padding(top = spacing.sm))
                NeriboTextField(
                    value = state.notes,
                    onValueChange = vm::onNotesChange,
                    placeholder = "Ideas for the arrangement, who to thank, what the song is really about",
                    singleLine = false,
                    minLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Text(
                    text = "Only you can see these. Notes are never printed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(spacing.xl))
            }
        }
    }
}
