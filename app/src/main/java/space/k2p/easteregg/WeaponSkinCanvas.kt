package space.k2p.easteregg

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val LEGENDARY_AGSL_SHADER = """
uniform float2 uResolution;
uniform float uTime;
uniform float uSeed;
uniform half4 uColor1;
uniform half4 uColor2;
uniform half4 uColor3;
uniform half4 uColor4;

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / max(uResolution, float2(1.0, 1.0));
    float t = uTime * 1.4 + uSeed;
    float w1 = 0.5 + 0.5 * sin(uv.x * 5.2 + t + cos(uv.y * 4.0 - t * 0.7));
    float w2 = 0.5 + 0.5 * cos(uv.y * 5.5 - t * 0.8 + sin(uv.x * 3.7 + t * 0.5));
    float diag = fract(uv.x * 0.85 + uv.y * 0.55 + t * 0.12 + w1 * 0.18);
    half4 mixA = mix(uColor1, uColor2, half(w1));
    half4 mixB = mix(uColor3, uColor4, half(w2));
    half4 base = mix(mixA, mixB, half(smoothstep(0.15, 0.85, diag)));
    float shimmer = pow(max(0.0, sin((uv.x * 4.0 - uv.y * 3.0) + t * 1.8)), 6.0);
    return clamp(base + half4(shimmer * 0.32), half4(0.0), half4(1.0));
}
"""

@Composable
fun WeaponSkinCanvas(
    weaponType: WeaponType,
    rarity: SkinRarity,
    skinStyle: SkinStyle,
    modifier: Modifier = Modifier,
    animateShader: Boolean = false
) {
    val shaderTime = if (rarity == SkinRarity.LEGENDARY && animateShader) {
        val transition = rememberInfiniteTransition(label = "legendary_shader")
        val time by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "shader_time"
        )
        time
    } else {
        0.9f
    }

    // Cache API 33+ RuntimeShader + ShaderBrush per skinStyle (no per-frame allocation, API <33 safe)
    val api33ShaderHolder: Any? = remember(skinStyle) {
        if (rarity == SkinRarity.LEGENDARY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { Api33LegendaryShaderHolder(LEGENDARY_AGSL_SHADER) }.getOrNull()
        } else {
            null
        }
    }

    val cachedGeometry = remember(weaponType) { buildWeaponReferenceGeometry(weaponType) }

    Canvas(modifier = modifier) {
        if (size.width <= 1f || size.height <= 1f) return@Canvas

        val refW = cachedGeometry.refWidth
        val refH = cachedGeometry.refHeight
        val pad = min(size.width, size.height) * 0.08f
        val availW = (size.width - pad * 2f).coerceAtLeast(1f)
        val availH = (size.height - pad * 2f).coerceAtLeast(1f)
        val scale = min(availW / refW, availH / refH)
        val offsetX = (size.width - refW * scale) / 2f
        val offsetY = (size.height - refH * scale) / 2f

        val transformMatrix = Matrix().apply {
            translate(offsetX, offsetY)
            scale(scale, scale)
        }

        val bodyPath = Path().apply {
            addPath(cachedGeometry.skinBodyPath)
            transform(transformMatrix)
        }
        val hardwarePath = Path().apply {
            addPath(cachedGeometry.hardwarePath)
            transform(transformMatrix)
        }
        val detailLinesPath = Path().apply {
            addPath(cachedGeometry.detailLinesPath)
            transform(transformMatrix)
        }
        val fullOutlinePath = Path().apply {
            addPath(bodyPath)
            addPath(hardwarePath)
        }

        // 1. Draw generated skin strictly clipped to weapon receiver/body silhouette
        clipPath(bodyPath) {
            drawSkinTexture(
                rarity = rarity,
                style = skinStyle,
                shaderTime = shaderTime,
                api33ShaderHolder = api33ShaderHolder
            )

            // Subtle upper bevel light on painted body
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.16f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.24f)
                    )
                )
            )
        }

        // 2. Draw dark metallic hardware (barrel, scope, grip, magazine, suppressor)
        drawPath(
            path = hardwarePath,
            color = Color(0xFF1E232B)
        )
        drawPath(
            path = hardwarePath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.12f),
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.35f)
                )
            )
        )

        // 3. Draw dark metallic contours and mechanical details on top
        val strokePx = (1.6f * scale).coerceIn(1.2f, 3.6f)
        drawPath(
            path = fullOutlinePath,
            color = Color(0xFF0E1116),
            style = Stroke(
                width = strokePx,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
        drawPath(
            path = detailLinesPath,
            color = Color(0xFF0E1116).copy(alpha = 0.85f),
            style = Stroke(
                width = (strokePx * 0.8f).coerceAtLeast(1f),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

private fun DrawScope.drawSkinTexture(
    rarity: SkinRarity,
    style: SkinStyle,
    shaderTime: Float,
    api33ShaderHolder: Any?
) {
    val c1 = Color(style.primaryColorArgb)
    val c2 = Color(if (style.secondaryColorArgb != 0) style.secondaryColorArgb else style.primaryColorArgb)

    when (rarity) {
        SkinRarity.COMMON -> {
            drawRect(color = c1)
        }

        SkinRarity.RARE -> {
            drawRect(color = c1)
            val patternColor = c2.copy(alpha = 0.88f)
            val step = (min(size.width, size.height) * 0.11f).coerceIn(7f, 20f)
            rotate(degrees = style.patternAngleDeg, pivot = center) {
                val diag = size.width + size.height
                if (style.patternType == PatternType.DOTS) {
                    val radius = (step * 0.24f).coerceAtLeast(1.8f)
                    var row = 0
                    var y = -diag * 0.4f
                    while (y <= diag * 1.4f) {
                        val rowOffset = if (row % 2 == 0) 0f else step * 0.5f
                        var x = -diag * 0.4f + rowOffset
                        while (x <= diag * 1.4f) {
                            drawCircle(
                                color = patternColor,
                                radius = radius,
                                center = Offset(x, y)
                            )
                            x += step
                        }
                        y += step * 0.9f
                        row++
                    }
                } else {
                    val strokeW = (step * 0.28f).coerceAtLeast(2f)
                    var x = -diag * 0.5f
                    while (x <= diag * 1.5f) {
                        drawLine(
                            color = patternColor,
                            start = Offset(x, -diag * 0.5f),
                            end = Offset(x, diag * 1.5f),
                            strokeWidth = strokeW
                        )
                        x += step * 1.15f
                    }
                }
            }
        }

        SkinRarity.EPIC -> {
            if (style.gradientType == GradientType.RADIAL) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(c1, c2),
                        center = Offset(size.width * 0.48f, size.height * 0.45f),
                        radius = size.width.coerceAtLeast(size.height) * 0.65f
                    )
                )
            } else {
                val rad = (style.patternAngleDeg * PI / 180.0).toFloat()
                val dx = cos(rad) * size.width * 0.5f
                val dy = sin(rad) * size.height * 0.5f
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(c1, c2),
                        start = Offset(center.x - dx, center.y - dy),
                        end = Offset(center.x + dx, center.y + dy)
                    )
                )
            }
        }

        SkinRarity.LEGENDARY -> {
            val c3 = Color(if (style.tertiaryColorArgb != 0) style.tertiaryColorArgb else style.primaryColorArgb)
            val c4 = Color(if (style.quaternaryColorArgb != 0) style.quaternaryColorArgb else style.secondaryColorArgb)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                api33ShaderHolder is Api33LegendaryShaderHolder
            ) {
                api33ShaderHolder.updateAndDraw(
                    scope = this,
                    shaderTime = shaderTime,
                    seed = style.shaderSeed,
                    c1 = c1,
                    c2 = c2,
                    c3 = c3,
                    c4 = c4
                )
            } else {
                // Fallback for API < 33: multi-color sweep + animated mesh highlight
                val shiftX = center.x + cos(shaderTime) * size.width * 0.12f
                val shiftY = center.y + sin(shaderTime) * size.height * 0.12f
                drawRect(
                    brush = Brush.sweepGradient(
                        colors = listOf(c1, c2, c3, c4, c1),
                        center = Offset(shiftX, shiftY)
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(c3.copy(alpha = 0.55f), Color.Transparent),
                        center = Offset(size.width * (0.35f + 0.15f * sin(shaderTime)), size.height * 0.4f),
                        radius = size.width * 0.55f
                    )
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class Api33LegendaryShaderHolder(agslSource: String) {
    private val shader = RuntimeShader(agslSource)
    private val brush = ShaderBrush(shader)

    fun updateAndDraw(
        scope: DrawScope,
        shaderTime: Float,
        seed: Float,
        c1: Color,
        c2: Color,
        c3: Color,
        c4: Color
    ) {
        shader.setFloatUniform("uResolution", scope.size.width, scope.size.height)
        shader.setFloatUniform("uTime", shaderTime)
        shader.setFloatUniform("uSeed", seed)
        shader.setFloatUniform("uColor1", c1.red, c1.green, c1.blue, 1f)
        shader.setFloatUniform("uColor2", c2.red, c2.green, c2.blue, 1f)
        shader.setFloatUniform("uColor3", c3.red, c3.green, c3.blue, 1f)
        shader.setFloatUniform("uColor4", c4.red, c4.green, c4.blue, 1f)
        scope.drawRect(brush = brush)
    }
}

private data class WeaponReferenceGeometry(
    val refWidth: Float,
    val refHeight: Float,
    val skinBodyPath: Path,
    val hardwarePath: Path,
    val detailLinesPath: Path
)

private fun buildWeaponReferenceGeometry(type: WeaponType): WeaponReferenceGeometry {
    return when (type) {
        WeaponType.AK47 -> buildAk47Geometry()
        WeaponType.AWP -> buildAwpGeometry()
        WeaponType.M4A1S -> buildM4A1SGeometry()
        WeaponType.DESERT_EAGLE -> buildDesertEagleGeometry()
        WeaponType.USP_S -> buildUspSGeometry()
    }
}

private fun buildAk47Geometry(): WeaponReferenceGeometry {
    val body = Path().apply {
        // Stock + Receiver + Handguard (painted areas)
        moveTo(12f, 38f)
        lineTo(58f, 34f)
        lineTo(66f, 28f)
        lineTo(132f, 28f)
        lineTo(176f, 28f)
        lineTo(178f, 44f)
        lineTo(128f, 48f)
        lineTo(118f, 52f)
        lineTo(74f, 52f)
        lineTo(64f, 48f)
        lineTo(10f, 66f)
        close()
    }
    val hardware = Path().apply {
        // Pistol grip
        moveTo(76f, 52f)
        lineTo(90f, 52f)
        lineTo(82f, 82f)
        lineTo(68f, 80f)
        close()
        // Curved AK banana magazine
        moveTo(112f, 50f)
        lineTo(128f, 50f)
        cubicTo(132f, 68f, 138f, 80f, 146f, 92f)
        lineTo(128f, 96f)
        cubicTo(120f, 82f, 114f, 68f, 112f, 50f)
        close()
        // Gas tube + Barrel + Front sight + Slant muzzle
        moveTo(176f, 27f)
        lineTo(205f, 29f)
        lineTo(205f, 35f)
        lineTo(232f, 35f)
        lineTo(236f, 32f)
        lineTo(236f, 40f)
        lineTo(178f, 40f)
        close()
        // Front sight post
        moveTo(212f, 20f)
        lineTo(218f, 20f)
        lineTo(220f, 35f)
        lineTo(210f, 35f)
        close()
        // Trigger guard
        moveTo(90f, 52f)
        lineTo(108f, 52f)
        lineTo(106f, 62f)
        lineTo(88f, 62f)
        close()
    }
    val details = Path().apply {
        // Dust cover rib & selector
        moveTo(70f, 35f)
        lineTo(126f, 35f)
        moveTo(96f, 42f)
        lineTo(116f, 42f)
        // Buttplate line
        moveTo(16f, 40f)
        lineTo(14f, 63f)
    }
    return WeaponReferenceGeometry(246f, 104f, body, hardware, details)
}

private fun buildAwpGeometry(): WeaponReferenceGeometry {
    val body = Path().apply {
        // Iconic AWP thumbhole sniper chassis
        moveTo(8f, 38f)
        lineTo(54f, 36f)
        lineTo(74f, 34f)
        lineTo(148f, 34f)
        lineTo(154f, 46f)
        lineTo(114f, 52f)
        lineTo(96f, 52f)
        lineTo(88f, 76f)
        lineTo(74f, 76f)
        lineTo(78f, 54f)
        lineTo(54f, 58f)
        lineTo(44f, 66f)
        lineTo(10f, 66f)
        close()
    }
    val hardware = Path().apply {
        // Heavy long sniper barrel & muzzle brake
        moveTo(148f, 35f)
        lineTo(226f, 35f)
        lineTo(226f, 32f)
        lineTo(240f, 32f)
        lineTo(240f, 43f)
        lineTo(226f, 43f)
        lineTo(226f, 41f)
        lineTo(148f, 41f)
        close()
        // Scope rings
        moveTo(86f, 24f)
        lineTo(94f, 24f)
        lineTo(94f, 34f)
        lineTo(86f, 34f)
        close()
        moveTo(122f, 24f)
        lineTo(130f, 24f)
        lineTo(130f, 34f)
        lineTo(122f, 34f)
        close()
        // Large telescopic scope
        moveTo(64f, 12f)
        lineTo(82f, 15f)
        lineTo(134f, 15f)
        lineTo(152f, 10f)
        lineTo(152f, 27f)
        lineTo(134f, 24f)
        lineTo(82f, 24f)
        lineTo(64f, 26f)
        close()
        // Magazine
        moveTo(102f, 52f)
        lineTo(124f, 52f)
        lineTo(122f, 66f)
        lineTo(102f, 66f)
        close()
    }
    val details = Path().apply {
        // Thumbhole cutout outline & bolt handle
        moveTo(32f, 46f)
        lineTo(60f, 44f)
        lineTo(56f, 53f)
        lineTo(30f, 54f)
        close()
        moveTo(86f, 38f)
        lineTo(96f, 44f)
    }
    return WeaponReferenceGeometry(248f, 92f, body, hardware, details)
}

private fun buildM4A1SGeometry(): WeaponReferenceGeometry {
    val body = Path().apply {
        // Upper & lower receiver + handguard
        moveTo(58f, 30f)
        lineTo(126f, 30f)
        lineTo(128f, 32f)
        lineTo(170f, 32f)
        lineTo(170f, 45f)
        lineTo(126f, 47f)
        lineTo(116f, 52f)
        lineTo(74f, 52f)
        lineTo(60f, 46f)
        close()
    }
    val hardware = Path().apply {
        // Buffer tube + tactical collapsible stock
        moveTo(10f, 30f)
        lineTo(32f, 30f)
        lineTo(58f, 34f)
        lineTo(58f, 44f)
        lineTo(30f, 48f)
        lineTo(18f, 62f)
        lineTo(10f, 62f)
        close()
        // Pistol grip
        moveTo(74f, 52f)
        lineTo(88f, 52f)
        lineTo(80f, 82f)
        lineTo(66f, 80f)
        close()
        // STANAG Magazine
        moveTo(106f, 50f)
        lineTo(124f, 50f)
        lineTo(128f, 84f)
        lineTo(110f, 86f)
        close()
        // Carry handle / rail sight + front triangle sight
        moveTo(76f, 22f)
        lineTo(118f, 22f)
        lineTo(120f, 30f)
        lineTo(74f, 30f)
        close()
        moveTo(164f, 21f)
        lineTo(172f, 21f)
        lineTo(176f, 34f)
        lineTo(162f, 34f)
        close()
        // Barrel + distinctive M4A1-S Suppressor
        moveTo(170f, 35f)
        lineTo(192f, 35f)
        lineTo(192f, 32f)
        lineTo(240f, 32f)
        lineTo(240f, 44f)
        lineTo(192f, 44f)
        lineTo(192f, 41f)
        lineTo(170f, 41f)
        close()
    }
    val details = Path().apply {
        moveTo(82f, 38f)
        lineTo(118f, 38f)
        moveTo(132f, 37f)
        lineTo(166f, 37f)
        moveTo(198f, 32f)
        lineTo(198f, 44f)
    }
    return WeaponReferenceGeometry(248f, 96f, body, hardware, details)
}

private fun buildDesertEagleGeometry(): WeaponReferenceGeometry {
    val body = Path().apply {
        // Heavy triangular barrel shroud + slide
        moveTo(32f, 24f)
        lineTo(156f, 24f)
        lineTo(162f, 34f)
        lineTo(154f, 46f)
        lineTo(68f, 46f)
        lineTo(48f, 54f)
        lineTo(28f, 44f)
        close()
    }
    val hardware = Path().apply {
        // Chunky grip
        moveTo(36f, 46f)
        lineTo(68f, 46f)
        lineTo(58f, 98f)
        lineTo(20f, 94f)
        close()
        // Trigger guard + trigger
        moveTo(68f, 46f)
        lineTo(94f, 46f)
        lineTo(90f, 64f)
        lineTo(64f, 64f)
        close()
        // Sights & hammer
        moveTo(26f, 26f)
        lineTo(34f, 22f)
        lineTo(34f, 34f)
        close()
        moveTo(146f, 19f)
        lineTo(154f, 19f)
        lineTo(156f, 24f)
        lineTo(144f, 24f)
        close()
    }
    val details = Path().apply {
        // Slide serrations & ejection port
        moveTo(80f, 24f)
        lineTo(80f, 34f)
        lineTo(110f, 34f)
        lineTo(110f, 24f)
        moveTo(42f, 28f)
        lineTo(42f, 40f)
        moveTo(48f, 28f)
        lineTo(48f, 40f)
        moveTo(54f, 28f)
        lineTo(54f, 40f)
    }
    return WeaponReferenceGeometry(176f, 110f, body, hardware, details)
}

private fun buildUspSGeometry(): WeaponReferenceGeometry {
    val body = Path().apply {
        // Sleek rectangular USP slide
        moveTo(28f, 26f)
        lineTo(124f, 26f)
        lineTo(124f, 44f)
        lineTo(32f, 44f)
        close()
    }
    val hardware = Path().apply {
        // Frame + underbarrel rail + grip
        moveTo(32f, 44f)
        lineTo(122f, 44f)
        lineTo(120f, 52f)
        lineTo(86f, 52f)
        lineTo(82f, 65f)
        lineTo(60f, 65f)
        lineTo(50f, 96f)
        lineTo(18f, 92f)
        lineTo(28f, 50f)
        close()
        // Distinctive cylindrical USP-S Suppressor
        moveTo(124f, 28f)
        lineTo(186f, 28f)
        lineTo(186f, 44f)
        lineTo(124f, 44f)
        close()
        // Sights
        moveTo(32f, 21f)
        lineTo(40f, 21f)
        lineTo(40f, 26f)
        lineTo(32f, 26f)
        close()
        moveTo(114f, 21f)
        lineTo(120f, 21f)
        lineTo(120f, 26f)
        lineTo(114f, 26f)
        close()
    }
    val details = Path().apply {
        moveTo(64f, 26f)
        lineTo(64f, 35f)
        lineTo(90f, 35f)
        lineTo(90f, 26f)
        moveTo(132f, 28f)
        lineTo(132f, 44f)
        moveTo(36f, 30f)
        lineTo(36f, 40f)
        moveTo(42f, 30f)
        lineTo(42f, 40f)
    }
    return WeaponReferenceGeometry(196f, 108f, body, hardware, details)
}
