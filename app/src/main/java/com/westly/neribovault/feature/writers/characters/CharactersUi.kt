package com.westly.neribovault.feature.writers.characters

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/**
 * STUB from Phase 6. Phase 7 replaces this file with the real characters list.
 * The public signatures must not change.
 */
@Composable
fun StoryCharactersTab(
    storyId: String,
    onOpenCharacter: (characterId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        icon = Icons.Outlined.Schedule,
        title = "Coming soon",
        message = "This section is being built.",
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * STUB from Phase 6. Phase 7 replaces this file with the real character editor.
 * `characterId == "new"` will create a character.
 */
@Composable
fun CharacterEditorScreen(
    storyId: String,
    characterId: String,
    onBack: () -> Unit,
) {
    PlaceholderScreen(
        title = "Character",
        message = "This section is being built.",
        onBack = onBack,
    )
}
