package space.k2p.easteregg

import kotlinx.serialization.Serializable

enum class WeaponType(val displayName: String) {
    AK47("AK-47"),
    AWP("AWP"),
    M4A1S("M4A1-S"),
    DESERT_EAGLE("Desert Eagle"),
    USP_S("USP-S")
}

enum class SkinRarity(
    val displayName: String,
    val sortPriority: Int,
    val accentArgb: Long,
    val fixedPrice: Int,
    val maxPerCase: Int
) {
    COMMON(
        displayName = "Обычный",
        sortPriority = 0,
        accentArgb = 0xFF9E9E9EL,
        fixedPrice = 50,
        maxPerCase = 4
    ),
    RARE(
        displayName = "Редкий",
        sortPriority = 1,
        accentArgb = 0xFF3B82F6L,
        fixedPrice = 100,
        maxPerCase = 4
    ),
    EPIC(
        displayName = "Эпический",
        sortPriority = 2,
        accentArgb = 0xFFA855F7L,
        fixedPrice = 400,
        maxPerCase = 2
    ),
    LEGENDARY(
        displayName = "Легендарный",
        sortPriority = 3,
        accentArgb = 0xFFF59E0BL,
        fixedPrice = 1200,
        maxPerCase = 1
    )
}

enum class PatternType {
    DOTS,
    LINES
}

enum class GradientType {
    LINEAR,
    RADIAL
}

@Serializable
data class SkinStyle(
    val primaryColorArgb: Int,
    val secondaryColorArgb: Int = 0,
    val tertiaryColorArgb: Int = 0,
    val quaternaryColorArgb: Int = 0,
    val patternType: PatternType = PatternType.DOTS,
    val patternAngleDeg: Float = 35f,
    val gradientType: GradientType = GradientType.LINEAR,
    val shaderSeed: Float = 0f
)

@Serializable
data class WeaponSkinItem(
    val id: String,
    val weaponType: WeaponType,
    val rarity: SkinRarity,
    val style: SkinStyle,
    val sellPrice: Int,
    val title: String
)

@Serializable
data class WeaponCase(
    val id: String,
    val title: String,
    val skins: List<WeaponSkinItem>
)

@Serializable
data class MiniGameState(
    val initialized: Boolean = false,
    val balance: Int = 0,
    val casesOpened: Int = 0,
    val nextCaseNumber: Int = 1,
    val cases: List<WeaponCase> = emptyList(),
    val inventory: List<WeaponSkinItem> = emptyList()
)

enum class InventoryFilterMode(val label: String) {
    CASES_FIRST("Сначала кейсы"),
    SKINS_FIRST("Сначала скины")
}

object CaseMiniGameConstants {
    const val CASE_PRICE_TUGRIKS = 200
    const val INITIAL_CASE_COUNT = 10
    const val SKINS_PER_CASE = 11
    const val MAX_INVENTORY_SIZE = 200
    const val MAX_CASES_SIZE = 100
    const val MAX_BALANCE = 1_000_000_000
}
