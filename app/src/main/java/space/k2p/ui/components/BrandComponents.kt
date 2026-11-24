package space.k2p.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.k2p.core.config.AppConfig
import space.k2p.ui.theme.BrandCrimson
import space.k2p.ui.theme.BrandCrimsonDark
import space.k2p.ui.theme.BrandDarkBackground

@Composable
fun BrandBackground(
    modifier: Modifier = Modifier,
    enableAnimation: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "brandGradientTransition")
    val animOffset by if (enableAnimation) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 8000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "gradientOffset"
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0.5f) }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.background
    val isDark = backgroundColor.luminance() < 0.5f
    val gradientCenter = Offset(x = 500f + animOffset * 200f, y = 200f + animOffset * 300f)

    val backgroundBrush = if (isDark) {
        Brush.radialGradient(
            colors = listOf(
                primaryColor.copy(alpha = 0.22f),
                BrandCrimsonDark.copy(alpha = 0.12f),
                backgroundColor,
                backgroundColor
            ),
            center = gradientCenter,
            radius = 1200f
        )
    } else {
        Brush.radialGradient(
            colors = listOf(
                BrandCrimson.copy(alpha = 0.11f),
                BrandCrimson.copy(alpha = 0.05f),
                backgroundColor,
                backgroundColor
            ),
            center = gradientCenter,
            radius = 1200f
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .background(backgroundBrush)
    ) {
        content()
    }
}

@Composable
fun BrandLogoHeader(
    modifier: Modifier = Modifier,
    showSlogan: Boolean = true,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally
) {
    Column(
        modifier = modifier.padding(vertical = 12.dp),
        horizontalAlignment = horizontalAlignment
    ) {
        val titleText = buildAnnotatedString {
            withStyle(
                style = SpanStyle(
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )
            ) {
                append("КАК ")
            }
            withStyle(
                style = SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp
                )
            ) {
                append("2")
            }
            withStyle(
                style = SpanStyle(
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )
            ) {
                append(" ПАЛЬЦА")
            }
        }

        Text(
            text = titleText,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = 26.sp,
                lineHeight = 32.sp
            )
        )

        if (showSlogan) {
            Text(
                text = AppConfig.APP_SLOGAN,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    letterSpacing = 0.5.sp
                ),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun CompactBrandLogoHeader(
    modifier: Modifier = Modifier
) {
    val titleText = buildAnnotatedString {
        withStyle(
            style = SpanStyle(
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold
            )
        ) {
            append("✌️ К")
        }
        withStyle(
            style = SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        ) {
            append("2")
        }
        withStyle(
            style = SpanStyle(
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold
            )
        ) {
            append("П")
        }
    }

    Text(
        text = titleText,
        style = MaterialTheme.typography.titleMedium.copy(
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        ),
        modifier = modifier
    )
}
