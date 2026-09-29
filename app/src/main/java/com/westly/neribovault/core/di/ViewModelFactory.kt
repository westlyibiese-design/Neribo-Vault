package com.westly.neribovault.core.di

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.westly.neribovault.NeriboApp

/**
 * Gets or creates a ViewModel that receives the [AppContainer].
 * Always pass a [key] when the ViewModel depends on a route argument.
 */
@Composable
inline fun <reified VM : ViewModel> neriboViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val app = LocalContext.current.applicationContext as NeriboApp
    val factory = viewModelFactory {
        initializer { create(app.container) }
    }
    return viewModel(key = key, factory = factory)
}
