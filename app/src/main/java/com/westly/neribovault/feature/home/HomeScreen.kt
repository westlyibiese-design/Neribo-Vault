package com.westly.neribovault.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.R
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboFab
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.util.formatDateLong
import com.westly.neribovault.core.vault.VaultCatalog
import com.westly.neribovault.core.vault.VaultDefinition
import java.time.LocalTime
import kotlinx.coroutines.launch

/** Home: logo header, greeting, date, a search pill and a two-column grid of every vault. */
@Composable
fun HomeScreen(
    onOpenVault: (route: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenRecent: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val greeting = remember { greetingForHour(LocalTime.now().hour) }
    val today = remember { formatDateLong(System.currentTimeMillis()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    NeriboScaffold(
        snackbarHostState = snackbarHostState,
        floatingActionButton = {
            NeriboFab(
                onClick = {
                    scope.launch {
                        snackbarHostState.showSnackbar("Quick add arrives with each vault.")
                    }
                },
                contentDescription = "Quick add",
            )
        },
        bottomBar = {
            HomeBottomBar(
                onSearch = onOpenSearch,
                onRecent = onOpenRecent,
                onSettings = onOpenSettings,
            )
        },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.neribo_logo),
                    contentDescription = null,
                    modifier = Modifier.height(30.dp),
                    contentScale = ContentScale.Fit,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Neribo Vault",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onBackground,
                    modifier = Modifier.weight(1f),
                )
                // The icon glyph sits 12dp inside its 48dp button; shift so it lines up with the margin.
                NeriboIconButton(
                    icon = Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    onClick = onOpenSettings,
                    modifier = Modifier.offset(x = spacing.md),
                )
            }
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineLarge,
                color = colors.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = today,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(20.dp))
            SearchPill(onClick = onOpenSearch)
            Spacer(modifier = Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VaultCatalog.all.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        pair.forEach { vault ->
                            VaultCard(
                                vault = vault,
                                onClick = { onOpenVault(vault.route) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(96.dp))
        }
    }
}

@Composable
private fun SearchPill(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .border(1.dp, colors.outlineVariant, shape)
            .background(colors.surface)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Search" }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = colors.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "Search your vault…",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun VaultCard(
    vault: VaultDefinition,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    NeriboCard(
        modifier = modifier
            .heightIn(min = 112.dp)
            .semantics(mergeDescendants = true) { },
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = vault.icon,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = NeriboTheme.extraColors.vaultIcon,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = vault.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = vault.tagline,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = colors.outline,
                )
            }
        }
    }
}

@Composable
private fun HomeBottomBar(
    onSearch: () -> Unit,
    onRecent: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = colors.primary,
        selectedTextColor = colors.primary,
        unselectedIconColor = colors.onSurfaceVariant,
        unselectedTextColor = colors.onSurfaceVariant,
        indicatorColor = Color.Transparent,
    )
    Column {
        NeriboDivider()
        NavigationBar(
            containerColor = colors.background,
            tonalElevation = 0.dp,
        ) {
            NavigationBarItem(
                selected = true,
                onClick = { },
                icon = { Icon(imageVector = Icons.Filled.Home, contentDescription = null) },
                label = { Text("Home", style = MaterialTheme.typography.labelMedium) },
                alwaysShowLabel = true,
                colors = itemColors,
            )
            NavigationBarItem(
                selected = false,
                onClick = onSearch,
                icon = { Icon(imageVector = Icons.Outlined.Search, contentDescription = null) },
                label = { Text("Search", style = MaterialTheme.typography.labelMedium) },
                alwaysShowLabel = true,
                colors = itemColors,
            )
            NavigationBarItem(
                selected = false,
                onClick = onRecent,
                icon = { Icon(imageVector = Icons.Outlined.Schedule, contentDescription = null) },
                label = { Text("Recent", style = MaterialTheme.typography.labelMedium) },
                alwaysShowLabel = true,
                colors = itemColors,
            )
            NavigationBarItem(
                selected = false,
                onClick = onSettings,
                icon = { Icon(imageVector = Icons.Outlined.Settings, contentDescription = null) },
                label = { Text("Settings", style = MaterialTheme.typography.labelMedium) },
                alwaysShowLabel = true,
                colors = itemColors,
            )
        }
    }
}

/** "Good morning" before noon, "Good afternoon" until 5 PM, otherwise "Good evening". */
private fun greetingForHour(hour: Int): String = when {
    hour < 12 -> "Good morning"
    hour < 17 -> "Good afternoon"
    else -> "Good evening"
}
