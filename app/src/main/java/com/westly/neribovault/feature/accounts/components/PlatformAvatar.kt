package com.westly.neribovault.feature.accounts.components

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.westly.neribovault.feature.accounts.AccountLogos
import com.westly.neribovault.feature.accounts.PlatformPreset
import com.westly.neribovault.feature.accounts.PlatformPresets

/**
 * A circle for a platform: its bundled logo, else the account's custom logo ("Other platform"
 * only, [customLogoPath] relative to the app's files folder), else the first letter in serif.
 * A logo that is missing or can't be read falls back to the letter. 40dp on lists, 56dp on the
 * detail header.
 */
@Composable
fun PlatformAvatar(
    platform: String,
    size: Dp,
    modifier: Modifier = Modifier,
    customLogoPath: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val preset = PlatformPresets.find(platform)
    val presetLogo = remember(preset?.id) { if (preset == null) 0 else logoResId(context, preset) }
    val isCustomPlatform = preset == null || preset.id == PlatformPresets.CUSTOM_ID
    val customFile = remember(customLogoPath, isCustomPlatform) {
        if (isCustomPlatform && !customLogoPath.isNullOrBlank()) {
            AccountLogos.fileFor(context.filesDir, customLogoPath)
        } else {
            null
        }
    }
    val model: Any? = when {
        presetLogo != 0 -> presetLogo
        customFile != null -> customFile
        else -> null
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            // A neutral light background, so transparent and dark logos read in both themes.
            .background(if (model == null) colors.surfaceVariant else Color.White)
            .border(1.dp, colors.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (model == null) {
            AvatarLetter(platform = platform, size = size)
        } else {
            SubcomposeAsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (model is Int) size * 0.18f else 0.dp),
                contentScale = if (model is Int) ContentScale.Fit else ContentScale.Crop,
                loading = {},
                error = { AvatarLetter(platform = platform, size = size, onLight = true) },
            )
        }
    }
}

@Composable
private fun AvatarLetter(platform: String, size: Dp, onLight: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val base = if (size >= 56.dp) typography.headlineSmall else typography.titleMedium
    Text(
        text = PlatformPresets.avatarLetter(platform),
        style = base.copy(fontFamily = FontFamily.Serif),
        color = if (onLight) Color.Black else colors.onSurface,
    )
}

/** The drawable id of the preset's bundled logo, or 0 when it has none or the file is missing. */
@SuppressLint("DiscouragedApi")
private fun logoResId(context: Context, preset: PlatformPreset): Int {
    val name = preset.logoName ?: return 0
    return try {
        context.resources.getIdentifier(name, "drawable", context.packageName)
    } catch (e: Exception) {
        0
    }
}
