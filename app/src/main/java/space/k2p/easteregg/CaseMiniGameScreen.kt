package space.k2p.easteregg

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import space.k2p.ui.components.BrandBackground
import kotlin.math.roundToInt

@Composable
fun CaseMiniGameScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appContext = LocalContext.current.applicationContext
    val viewModel: CaseMiniGameViewModel = viewModel {
        CaseMiniGameViewModel(appContext)
    }
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    BackHandler(enabled = uiState.inspectedSkin != null || uiState.activeRoulette != null) {
        when {
            uiState.inspectedSkin != null -> viewModel.dismissInspect()
            uiState.activeRoulette?.spinComplete == true -> viewModel.claimWonSkinToInventory()
        }
    }

    BrandBackground(modifier = modifier.fillMaxSize(), enableAnimation = false) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Top Bar: Back + Title + Tugriks Balance
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Назад в VPN"
                                )
                            }
                            Text(
                                text = "Кейсы К2П",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MonetizationOn,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${uiState.balance} Т",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (uiState.isLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            // Shop Card
                            item(key = "shop_card") {
                                ShopCaseCard(
                                    balance = uiState.balance,
                                    casesOpened = uiState.casesOpened,
                                    onBuyCase = { viewModel.buyCase() }
                                )
                            }

                            // Filter Switcher
                            item(key = "filter_bar") {
                                FilterModeBar(
                                    selected = uiState.filterMode,
                                    casesCount = uiState.cases.size,
                                    skinsCount = uiState.inventory.size,
                                    onSelect = { viewModel.setFilterMode(it) }
                                )
                            }

                            if (uiState.filterMode == InventoryFilterMode.CASES_FIRST) {
                                if (uiState.cases.isNotEmpty()) {
                                    item(key = "header_cases") {
                                        SectionHeader(title = "Кейсы (${uiState.cases.size})")
                                    }
                                    items(uiState.cases, key = { "case_${it.id}" }) { weaponCase ->
                                        CaseCard(
                                            weaponCase = weaponCase,
                                            onOpen = { viewModel.startOpeningCase(weaponCase.id) }
                                        )
                                    }
                                }
                                if (uiState.inventory.isNotEmpty()) {
                                    item(key = "header_skins") {
                                        SectionHeader(title = "Инвентарь (${uiState.inventory.size})")
                                    }
                                    items(uiState.sortedInventory, key = { "skin_${it.id}" }) { skin ->
                                        InventorySkinCard(
                                            skin = skin,
                                            onClick = { viewModel.inspectSkin(skin) }
                                        )
                                    }
                                }
                            } else {
                                if (uiState.inventory.isNotEmpty()) {
                                    item(key = "header_skins") {
                                        SectionHeader(title = "Инвентарь по редкости (${uiState.inventory.size})")
                                    }
                                    items(uiState.sortedInventory, key = { "skin_${it.id}" }) { skin ->
                                        InventorySkinCard(
                                            skin = skin,
                                            onClick = { viewModel.inspectSkin(skin) }
                                        )
                                    }
                                }
                                if (uiState.cases.isNotEmpty()) {
                                    item(key = "header_cases") {
                                        SectionHeader(title = "Кейсы (${uiState.cases.size})")
                                    }
                                    items(uiState.cases, key = { "case_${it.id}" }) { weaponCase ->
                                        CaseCard(
                                            weaponCase = weaponCase,
                                            onOpen = { viewModel.startOpeningCase(weaponCase.id) }
                                        )
                                    }
                                }
                            }

                            if (uiState.cases.isEmpty() && uiState.inventory.isEmpty()) {
                                item(key = "empty_state") {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        shape = RoundedCornerShape(18.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "У вас пока нет кейсов и скинов. Купите новый кейс в магазине выше!",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Roulette Opening Overlay
                AnimatedVisibility(
                    visible = uiState.activeRoulette != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    uiState.activeRoulette?.let { session ->
                        RouletteOverlay(
                            session = session,
                            onSpinFinished = { viewModel.markRouletteFinished() },
                            onClaim = { viewModel.claimWonSkinToInventory() },
                            onSell = { viewModel.sellWonSkinImmediately() }
                        )
                    }
                }

                // Full-screen Weapon Inspect Overlay
                AnimatedVisibility(
                    visible = uiState.inspectedSkin != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    uiState.inspectedSkin?.let { skin ->
                        WeaponInspectOverlay(
                            skin = skin,
                            onClose = { viewModel.dismissInspect() },
                            onSell = { viewModel.sellInventorySkin(skin.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShopCaseCard(
    balance: Int,
    casesOpened: Int,
    onBuyCase: () -> Unit
) {
    val canAfford = balance >= CaseMiniGameConstants.CASE_PRICE_TUGRIKS
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ShoppingBag,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Магазин кейсов",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "11 скинов внутри • Открыто: $casesOpened",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = onBuyCase,
                enabled = canAfford,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "Купить ${CaseMiniGameConstants.CASE_PRICE_TUGRIKS} Т",
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun FilterModeBar(
    selected: InventoryFilterMode,
    casesCount: Int,
    skinsCount: Int,
    onSelect: (InventoryFilterMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChipButton(
            label = "Сначала кейсы ($casesCount)",
            isSelected = selected == InventoryFilterMode.CASES_FIRST,
            onClick = { onSelect(InventoryFilterMode.CASES_FIRST) },
            modifier = Modifier.weight(1f)
        )
        FilterChipButton(
            label = "Сначала скины ($skinsCount)",
            isSelected = selected == InventoryFilterMode.SKINS_FIRST,
            onClick = { onSelect(InventoryFilterMode.SKINS_FIRST) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun FilterChipButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val active = MaterialTheme.colorScheme.primary
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (isSelected) active.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
            contentColor = if (isSelected) active else MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) active else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun CaseCard(
    weaponCase: WeaponCase,
    onOpen: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .clickable { onOpen() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color(SkinRarity.LEGENDARY.accentArgb).copy(alpha = 0.28f),
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                )
                            )
                        )
                        .border(1.dp, Color(SkinRarity.LEGENDARY.accentArgb).copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Inventory2,
                        contentDescription = null,
                        tint = Color(SkinRarity.LEGENDARY.accentArgb),
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = weaponCase.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${weaponCase.skins.size} скинов • Нажмите, чтобы открыть",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        weaponCase.skins.forEach { skin ->
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(skin.rarity.accentArgb))
                            )
                        }
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = "Открыть",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun InventorySkinCard(
    skin: WeaponSkinItem,
    onClick: () -> Unit
) {
    val rarityColor = Color(skin.rarity.accentArgb)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.2.dp, rarityColor.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(108.dp)
                    .height(62.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(rarityColor.copy(alpha = 0.12f))
                    .border(1.dp, rarityColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            ) {
                WeaponSkinCanvas(
                    weaponType = skin.weaponType,
                    rarity = skin.rarity,
                    skinStyle = skin.style,
                    animateShader = false,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Surface(
                    color = rarityColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = skin.rarity.displayName,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = rarityColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = skin.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Цена продажи: ${skin.sellPrice} Т",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RouletteOverlay(
    session: ActiveRouletteSession,
    onSpinFinished: () -> Unit,
    onClaim: () -> Unit,
    onSell: () -> Unit
) {
    val density = LocalDensity.current
    val tileWidthDp = 148.dp
    val tileSpacingDp = 10.dp
    val tileWidthPx = with(density) { tileWidthDp.toPx() }
    val tileSpacingPx = with(density) { tileSpacingDp.toPx() }
    val stridePx = tileWidthPx + tileSpacingPx

    val scrollOffset = remember(session.openingCase.id) { Animatable(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.84f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {}
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (session.spinComplete) "Выпавший скин!" else "Открытие: ${session.openingCase.title}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Horizontal Roulette Tape with center vertical pointer
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(142.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .clipToBounds()
                ) {
                    val containerWidthPx = with(density) { maxWidth.toPx() }

                    LaunchedEffect(session.openingCase.id, containerWidthPx) {
                        if (containerWidthPx > 0f) {
                            val centerOfTargetTile = session.winningIndexInTape * stridePx + (tileWidthPx / 2f)
                            val subOffset = session.subTileOffsetFraction * (tileWidthPx * 0.35f)
                            val targetScrollPx = (centerOfTargetTile + subOffset) - (containerWidthPx / 2f)

                            if (session.spinComplete) {
                                scrollOffset.snapTo(targetScrollPx)
                            } else {
                                scrollOffset.snapTo(0f)
                                scrollOffset.animateTo(
                                    targetValue = targetScrollPx,
                                    animationSpec = tween(
                                        durationMillis = 4500,
                                        easing = CubicBezierEasing(0.10f, 0.86f, 0.18f, 1.0f)
                                    )
                                )
                                onSpinFinished()
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .offset { IntOffset(-scrollOffset.value.roundToInt(), 0) }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(tileSpacingDp)
                    ) {
                        session.tapeItems.forEach { item ->
                            val itemRarityColor = Color(item.rarity.accentArgb)
                            Box(
                                modifier = Modifier
                                    .width(tileWidthDp)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .border(1.5.dp, itemRarityColor.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                                    .padding(8.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.SpaceBetween,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                    ) {
                                        WeaponSkinCanvas(
                                            weaponType = item.weaponType,
                                            rarity = item.rarity,
                                            skinStyle = item.style,
                                            animateShader = false,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = item.weaponType.displayName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = itemRarityColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // Center vertical pointer line
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                if (session.spinComplete) {
                    val win = session.winningSkin
                    val winColor = Color(win.rarity.accentArgb)

                    Card(
                        colors = CardDefaults.cardColors(containerColor = winColor.copy(alpha = 0.12f)),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.5.dp, winColor.copy(alpha = 0.65f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(130.dp)
                            ) {
                                WeaponSkinCanvas(
                                    weaponType = win.weaponType,
                                    rarity = win.rarity,
                                    skinStyle = win.style,
                                    animateShader = true,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = win.rarity.displayName.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = winColor
                            )
                            Text(
                                text = win.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onClaim,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            text = "Забрать в инвентарь",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onSell,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    ) {
                        Text(
                            text = "Продать за ${win.sellPrice} Т",
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Text(
                        text = "Прокрутка барабана (11 скинов кейса)...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun WeaponInspectOverlay(
    skin: WeaponSkinItem,
    onClose: () -> Unit,
    onSell: () -> Unit
) {
    val rarityColor = Color(skin.rarity.accentArgb)

    BrandBackground(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {},
        enableAnimation = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Осмотр оружия",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Закрыть"
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                rarityColor.copy(alpha = 0.25f),
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                            )
                        )
                    )
                    .border(1.5.dp, rarityColor.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                    .padding(18.dp),
                contentAlignment = Alignment.Center
            ) {
                WeaponSkinCanvas(
                    weaponType = skin.weaponType,
                    rarity = skin.rarity,
                    skinStyle = skin.style,
                    animateShader = true,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Surface(
                color = rarityColor.copy(alpha = 0.16f),
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, rarityColor.copy(alpha = 0.45f))
            ) {
                Text(
                    text = skin.rarity.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = rarityColor,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = skin.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            val textureDesc = when (skin.rarity) {
                SkinRarity.COMMON -> "Однотонная окраска корпуса"
                SkinRarity.RARE -> if (skin.style.patternType == PatternType.DOTS) "Процедурный точечный узор" else "Процедурный линейный узор"
                SkinRarity.EPIC -> if (skin.style.gradientType == GradientType.LINEAR) "Двухцветный линейный градиент" else "Двухцветный радиальный градиент"
                SkinRarity.LEGENDARY -> "Многоцветный переливающийся шейдер"
            }
            Text(
                text = textureDesc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onSell,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MonetizationOn,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Продать за ${skin.sellPrice} Т",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
