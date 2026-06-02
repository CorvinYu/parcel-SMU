package com.xxxx.parcel.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xxxx.parcel.util.AppBackgroundPreset
import com.xxxx.parcel.util.AppBackgroundScaleMode
import com.xxxx.parcel.util.getAppBackgroundSettings

val TextColor = Color(0xFF222222)
val TextColorAAA = Color(0xFFAAAAAA)
val TextColorWhite = Color(0xFFFFFFFF)

val Shapes = Shapes(
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Purple80,
    onPrimaryContainer = Color.Black,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    onSecondary = Color.Gray,
    onTertiary = Color.Black,
    background = Color.Transparent,
    onBackground = TextColorAAA,
    surface = Color.Transparent,
    onSurface = TextColorWhite
)

private val LightColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Purple80,
    onPrimaryContainer = Color.Black,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    onSecondary = Color.Gray,
    onTertiary = Color.White,
    background = Color.Transparent,
    onBackground = TextColor,
    surface = Color.Transparent,
    onSurface = TextColor
)

@Composable
fun ParcelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    isSeniorMode: Boolean = false,
    backgroundVersion: Int = 0,
    applyCustomBackground: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val settings = remember(backgroundVersion, applyCustomBackground) {
        getAppBackgroundSettings(context)
    }
    val backgroundBitmap = remember(settings.imagePath, backgroundVersion) {
        settings.imagePath?.let { path ->
            decodeBackgroundBitmap(path, 1440, 2600)
        }
    }
    val presetBrush = remember(settings.preset, darkTheme, applyCustomBackground) {
        getPresetBrush(
            preset = if (applyCustomBackground) settings.preset else AppBackgroundPreset.DEFAULT,
            darkTheme = darkTheme
        )
    }
    val contentScale = remember(settings.scaleMode) {
        settings.scaleMode.toContentScale()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = getTypography(isSeniorMode),
        shapes = Shapes,
        content = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(brush = presetBrush)
            ) {
                if (applyCustomBackground && backgroundBitmap != null) {
                    Image(
                        bitmap = backgroundBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(settings.blurRadius.dp),
                        contentScale = contentScale
                    )
                }
                if (applyCustomBackground) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = settings.overlayAlpha))
                    )
                }
                content()
            }
        }
    )
}

private fun AppBackgroundScaleMode.toContentScale(): ContentScale {
    return when (this) {
        AppBackgroundScaleMode.CROP -> ContentScale.Crop
        AppBackgroundScaleMode.FIT -> ContentScale.Fit
        AppBackgroundScaleMode.FILL -> ContentScale.FillBounds
    }
}

private fun getPresetBrush(preset: AppBackgroundPreset, darkTheme: Boolean): Brush {
    return when (preset) {
        AppBackgroundPreset.DEFAULT -> {
            if (darkTheme) {
                Brush.linearGradient(
                    colors = listOf(Color(0xFF2C105E), Color(0xFF020202), Color(0xFF590E26))
                )
            } else {
                Brush.linearGradient(
                    colors = listOf(Color(0xFFCEB6F6), Color(0xFFF6F3F4), Color(0xFFF6C8D8))
                )
            }
        }

        AppBackgroundPreset.SUNSET -> {
            if (darkTheme) {
                Brush.linearGradient(
                    colors = listOf(Color(0xFF2B122A), Color(0xFF5B1F1F), Color(0xFF181022))
                )
            } else {
                Brush.linearGradient(
                    colors = listOf(Color(0xFFFFD6A5), Color(0xFFFFADAD), Color(0xFFFDFFB6))
                )
            }
        }

        AppBackgroundPreset.MINT -> {
            if (darkTheme) {
                Brush.linearGradient(
                    colors = listOf(Color(0xFF062925), Color(0xFF133832), Color(0xFF0C1716))
                )
            } else {
                Brush.linearGradient(
                    colors = listOf(Color(0xFFD8F3DC), Color(0xFFF1FAEE), Color(0xFFB7E4C7))
                )
            }
        }

        AppBackgroundPreset.NIGHT -> {
            if (darkTheme) {
                Brush.linearGradient(
                    colors = listOf(Color(0xFF081120), Color(0xFF101B38), Color(0xFF2D1A4A))
                )
            } else {
                Brush.linearGradient(
                    colors = listOf(Color(0xFFCDE7FF), Color(0xFFE8D7FF), Color(0xFFF9F7FF))
                )
            }
        }
    }
}

private fun decodeBackgroundBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds, reqWidth, reqHeight)
    }
    return runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull()
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        var halfHeight = height / 2
        var halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
            halfHeight /= 2
            halfWidth /= 2
        }
    }

    return inSampleSize.coerceAtLeast(1)
}
