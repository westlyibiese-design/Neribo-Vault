package com.westly.neribovault.feature.coder.guidance

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.NeriboApp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.feature.coder.GuidanceMode

/**
 * A small, muted "What is this?" text button. It opens a bottom sheet with the plain explanation of the glossary
 * term [termId]. It draws nothing in Pro mode, while the mode is still loading, or for a term that does not exist.
 */
@Composable
fun WhatIsThis(termId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember(context) { (context.applicationContext as NeriboApp).container.coderPrefs }
    val mode by prefs.guidanceMode.collectAsStateWithLifecycle<GuidanceMode?>(initialValue = null)
    val term = remember(termId) { CoderGlossary.find(termId) }
    var open by rememberSaveable(termId) { mutableStateOf(false) }
    if (mode != GuidanceMode.Beginner || term == null) return

    TextButton(onClick = { open = true }, modifier = modifier.heightIn(min = 48.dp)) {
        Text(
            text = "What is this?",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) {
        NeriboBottomSheet(onDismiss = { open = false }) {
            Text(
                text = term.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(NeriboTheme.spacing.sm))
            Text(
                text = term.explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(NeriboTheme.spacing.lg))
            NeriboButton(text = "Close", onClick = { open = false })
        }
    }
}
