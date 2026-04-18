@file:Suppress("DEPRECATION")

package com.inspiredandroid.kai.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.ui.tooling.preview.Preview

val darkPurple = Color(0xFF6200EE)
val lightPurple = Color(0xff8063C5)
val gradientBrush = androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(darkPurple, lightPurple))

// Animated border gradient colors
val gradientPurple = Color(0xFF9C27B0)
val gradientViolet = Color(0xFF7C4DFF)
val gradientMagenta = Color(0xFFE040FB)

fun Modifier.handCursor() = pointerHoverIcon(PointerIcon.Hand, overrideDescendants = true)

val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFBB86FC),
    onPrimary = Color(0xFF000000),
    surface = Color(0xFF1E1E1E),
    background = Color(0xFF121212),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
)

val LightColorScheme = lightColorScheme(
    primary = darkPurple,
    onPrimary = Color(0xFFFFFFFF),
    surface = Color(0xFFF2F2F2),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    onSurface = Color(0xFF000000),
)

/** Midnight Ocean — deep navy + cyan accent */
val MidnightOceanColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF001F2A),
    secondary = Color(0xFF006E7F),
    tertiary = Color(0xFF0097A7),
    surface = Color(0xFF0D1B2A),
    surfaceVariant = Color(0xFF112233),
    background = Color(0xFF060D14),
    onBackground = Color(0xFFE0F7FA),
    onSurface = Color(0xFFB2EBF2),
    onSurfaceVariant = Color(0xFF80DEEA),
)

/** Ember — charcoal + warm amber */
val EmberColorScheme = darkColorScheme(
    primary = Color(0xFFFFB300),
    onPrimary = Color(0xFF1A0D00),
    secondary = Color(0xFFE65100),
    tertiary = Color(0xFFBF360C),
    surface = Color(0xFF1E1610),
    surfaceVariant = Color(0xFF252015),
    background = Color(0xFF120E08),
    onBackground = Color(0xFFFFF8F0),
    onSurface = Color(0xFFFFE0B2),
    onSurfaceVariant = Color(0xFFFFCC80),
)

/** Forest — dark green + lime accent */
val ForestColorScheme = darkColorScheme(
    primary = Color(0xFF69F0AE),
    onPrimary = Color(0xFF00210F),
    secondary = Color(0xFF00C853),
    tertiary = Color(0xFF1B5E20),
    surface = Color(0xFF0F1F14),
    surfaceVariant = Color(0xFF142819),
    background = Color(0xFF08120B),
    onBackground = Color(0xFFE8F5E9),
    onSurface = Color(0xFFC8E6C9),
    onSurfaceVariant = Color(0xFFA5D6A7),
)

/** Rose Gold — near-black + blush pink */
val RoseGoldColorScheme = darkColorScheme(
    primary = Color(0xFFFF80AB),
    onPrimary = Color(0xFF1A0011),
    secondary = Color(0xFFF06292),
    tertiary = Color(0xFFAD1457),
    surface = Color(0xFF1E1218),
    surfaceVariant = Color(0xFF26171E),
    background = Color(0xFF120A0F),
    onBackground = Color(0xFFFCE4EC),
    onSurface = Color(0xFFF8BBD0),
    onSurfaceVariant = Color(0xFFF48FB1),
)

/** Slate — cool blue-grey + indigo */
val SlateColorScheme = darkColorScheme(
    primary = Color(0xFF7986CB),
    onPrimary = Color(0xFF0D0F1E),
    secondary = Color(0xFF5C6BC0),
    tertiary = Color(0xFF3949AB),
    surface = Color(0xFF1A1C2E),
    surfaceVariant = Color(0xFF20223A),
    background = Color(0xFF0F101C),
    onBackground = Color(0xFFE8EAF6),
    onSurface = Color(0xFFC5CAE9),
    onSurfaceVariant = Color(0xFF9FA8DA),
)

enum class AppTheme(val displayName: String) {
    DEFAULT("Default"),
    MIDNIGHT_OCEAN("Midnight Ocean"),
    EMBER("Ember"),
    FOREST("Forest"),
    ROSE_GOLD("Rose Gold"),
    SLATE("Slate"),
    LIGHT("Light"),
}

fun AppTheme.toColorScheme(isSystemDark: Boolean): ColorScheme = when (this) {
    AppTheme.DEFAULT -> if (isSystemDark) DarkColorScheme else LightColorScheme
    AppTheme.MIDNIGHT_OCEAN -> MidnightOceanColorScheme
    AppTheme.EMBER -> EmberColorScheme
    AppTheme.FOREST -> ForestColorScheme
    AppTheme.ROSE_GOLD -> RoseGoldColorScheme
    AppTheme.SLATE -> SlateColorScheme
    AppTheme.LIGHT -> LightColorScheme
}

@Composable
fun outlineTextFieldColors() = OutlinedTextFieldDefaults.colors()

@Composable
fun KaiOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        shape = RoundedCornerShape(12.dp),
        colors = outlineTextFieldColors(),
    )
}

@Composable
fun KaiClearableTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    KaiOutlinedTextField(
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = singleLine,
        trailingIcon = {
            IconButton(
                onClick = { onValueChange("") },
                modifier = Modifier.handCursor()
                    .alpha(if (focused && value.isNotEmpty()) 1f else 0f),
                enabled = value.isNotEmpty(),
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
@Preview
fun Theme(
    colorScheme: ColorScheme,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = colorScheme,
    ) {
        content()
    }
}
