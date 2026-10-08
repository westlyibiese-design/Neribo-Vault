package com.westly.neribovault.feature.accounts.editor

import androidx.compose.runtime.Composable
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** A stub that part A2 replaces with the real item editor. [itemId] is "new" to create one. */
@Composable
fun ItemEditorScreen(accountId: String, itemId: String, onBack: () -> Unit) {
    PlaceholderScreen(
        title = "Item",
        message = "This part of Accounts is being built.",
        onBack = onBack,
    )
}
