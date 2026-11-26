package space.k2p.easteregg

import android.graphics.Color
import java.security.SecureRandom
import java.util.UUID
import kotlin.math.abs

object CaseGenerator {
    private val random = SecureRandom()

    private val commonTitles = listOf("Графит", "Пустыня", "Сталь", "Пепел", "Кобальт", "Хаки", "Базальт")
    private val rareTitles = listOf("Пиксель", "Штрих", "Матрица", "Импульс", "Сетка", "Нео-точки", "Вектор")
    private val epicTitles = listOf("Закат", "Плазма", "Аметист", "Овердрайв", "Мираж", "Кислота", "Фантом")
    private val legendaryTitles = listOf("Гиперзверь", "Хрома", "Солярис", "Квантум", "Радуга", "Аврора", "Спектр")

    fun generateCase(caseNumber: Int): WeaponCase {
        val rarities = buildCaseRarities()
        val skins = rarities.map { rarity -> generateSkin(rarity) }
        return WeaponCase(
            id = UUID.randomUUID().toString(),
            title = "Кейс #$caseNumber",
            skins = skins
        )
    }

    /**
     * Generates exactly 11 rarities per case:
     * - LEGENDARY: exactly 1
     * - EPIC: max 2
     * - RARE: max 4
     * - COMMON: max 4
     * Remaining slots (if any cap was hit) are filled with available classes under their caps.
     */
    private fun buildCaseRarities(): List<SkinRarity> {
        val counts = mutableMapOf(
            SkinRarity.LEGENDARY to 1,
            SkinRarity.EPIC to 0,
            SkinRarity.RARE to 0,
            SkinRarity.COMMON to 0
        )
        val result = mutableListOf(SkinRarity.LEGENDARY)
        val candidatePool = listOf(SkinRarity.COMMON, SkinRarity.RARE, SkinRarity.EPIC)

        while (result.size < CaseMiniGameConstants.SKINS_PER_CASE) {
            val available = candidatePool.filter { rarity ->
                (counts[rarity] ?: 0) < rarity.maxPerCase
            }
            if (available.isEmpty()) break
            val chosen = available[random.nextInt(available.size)]
            counts[chosen] = (counts[chosen] ?: 0) + 1
            result.add(chosen)
        }

        // Fallback safety in case total slots ever needed topping up
        while (result.size < CaseMiniGameConstants.SKINS_PER_CASE) {
            result.add(SkinRarity.COMMON)
        }

        result.shuffle(random)
        return result
    }

    fun generateSkin(rarity: SkinRarity): WeaponSkinItem {
        val weapons = WeaponType.entries
        val weapon = weapons[random.nextInt(weapons.size)]
        val style = generateStyleForRarity(rarity)
        val suffix = when (rarity) {
            SkinRarity.COMMON -> commonTitles[random.nextInt(commonTitles.size)]
            SkinRarity.RARE -> rareTitles[random.nextInt(rareTitles.size)]
            SkinRarity.EPIC -> epicTitles[random.nextInt(epicTitles.size)]
            SkinRarity.LEGENDARY -> legendaryTitles[random.nextInt(legendaryTitles.size)]
        }

        return WeaponSkinItem(
            id = UUID.randomUUID().toString(),
            weaponType = weapon,
            rarity = rarity,
            style = style,
            sellPrice = rarity.fixedPrice,
            title = "${weapon.displayName} • $suffix"
        )
    }

    fun pickWinningIndex(skins: List<WeaponSkinItem>): Int {
        if (skins.isEmpty()) return 0
        val weights = skins.map { skin ->
            when (skin.rarity) {
                SkinRarity.COMMON -> 140
                SkinRarity.RARE -> 75
                SkinRarity.EPIC -> 45
                SkinRarity.LEGENDARY -> 35
            }
        }
        val total = weights.sum()
        var roll = random.nextInt(total)
        for (i in skins.indices) {
            roll -= weights[i]
            if (roll < 0) return i
        }
        return 0
    }

    private fun generateStyleForRarity(rarity: SkinRarity): SkinStyle {
        return when (rarity) {
            SkinRarity.COMMON -> {
                val base = randomRgbColor(minChannel = 45, maxChannel = 225)
                SkinStyle(primaryColorArgb = base)
            }
            SkinRarity.RARE -> {
                val bg = randomHsvColor(minSat = 0.35f, maxSat = 0.85f, minVal = 0.25f, maxVal = 0.78f)
                val accent = pickContrastingColor(bg)
                val pattern = if (random.nextBoolean()) PatternType.DOTS else PatternType.LINES
                val angle = 15f + random.nextInt(65)
                SkinStyle(
                    primaryColorArgb = bg,
                    secondaryColorArgb = accent,
                    patternType = pattern,
                    patternAngleDeg = angle
                )
            }
            SkinRarity.EPIC -> {
                val hue1 = random.nextFloat() * 360f
                val hueShift = 90f + random.nextFloat() * 140f
                val hue2 = (hue1 + hueShift) % 360f
                val c1 = Color.HSVToColor(floatArrayOf(hue1, 0.75f + random.nextFloat() * 0.25f, 0.72f + random.nextFloat() * 0.25f))
                val c2 = Color.HSVToColor(floatArrayOf(hue2, 0.75f + random.nextFloat() * 0.25f, 0.55f + random.nextFloat() * 0.35f))
                val grad = if (random.nextBoolean()) GradientType.LINEAR else GradientType.RADIAL
                SkinStyle(
                    primaryColorArgb = c1,
                    secondaryColorArgb = c2,
                    gradientType = grad,
                    patternAngleDeg = random.nextInt(180).toFloat()
                )
            }
            SkinRarity.LEGENDARY -> {
                val baseHue = random.nextFloat() * 360f
                val c1 = Color.HSVToColor(floatArrayOf(baseHue, 0.92f, 0.96f))
                val c2 = Color.HSVToColor(floatArrayOf((baseHue + 85f) % 360f, 0.95f, 0.94f))
                val c3 = Color.HSVToColor(floatArrayOf((baseHue + 175f) % 360f, 0.90f, 0.98f))
                val c4 = Color.HSVToColor(floatArrayOf((baseHue + 265f) % 360f, 0.92f, 0.92f))
                SkinStyle(
                    primaryColorArgb = c1,
                    secondaryColorArgb = c2,
                    tertiaryColorArgb = c3,
                    quaternaryColorArgb = c4,
                    shaderSeed = random.nextFloat() * 100f
                )
            }
        }
    }

    private fun randomRgbColor(minChannel: Int, maxChannel: Int): Int {
        val span = (maxChannel - minChannel).coerceAtLeast(1)
        val r = minChannel + random.nextInt(span)
        val g = minChannel + random.nextInt(span)
        val b = minChannel + random.nextInt(span)
        return Color.argb(255, r, g, b)
    }

    private fun randomHsvColor(minSat: Float, maxSat: Float, minVal: Float, maxVal: Float): Int {
        val h = random.nextFloat() * 360f
        val s = minSat + random.nextFloat() * (maxSat - minSat)
        val v = minVal + random.nextFloat() * (maxVal - minVal)
        return Color.HSVToColor(floatArrayOf(h, s, v))
    }

    private fun pickContrastingColor(baseArgb: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(baseArgb, hsv)
        val oppositeHue = (hsv[0] + 130f + random.nextFloat() * 100f) % 360f
        val targetVal = if (hsv[2] > 0.55f) 0.22f + random.nextFloat() * 0.2f else 0.85f + random.nextFloat() * 0.15f
        val targetSat = if (abs(hsv[1]) < 0.3f) 0.85f else 0.65f + random.nextFloat() * 0.35f
        return Color.HSVToColor(floatArrayOf(oppositeHue, targetSat, targetVal))
    }
}
