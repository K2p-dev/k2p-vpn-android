package space.k2p.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import space.k2p.K2pApp
import space.k2p.data.api.model.EmergencyTransportServiceType
import space.k2p.data.api.model.EmergencyTransportStatus
import space.k2p.ui.components.BrandBackground
import kotlin.math.max

@Composable
fun EmergencyBypassSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val repo = remember { K2pApp.instance.emergencyTransportRepository }
    val emergencyState by repo.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var docUrlInput by remember { mutableStateOf(emergencyState.docUrl) }
    var selectedService by remember { mutableStateOf(emergencyState.transportType) }
    var lastAppliedSyncMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        repo.initFromStorage()
        repo.syncFromServer()
    }

    LaunchedEffect(emergencyState.lastSyncedAtMs, emergencyState.docUrl, emergencyState.transportType) {
        val syncMs = emergencyState.lastSyncedAtMs
        if (syncMs != null && syncMs != lastAppliedSyncMs) {
            docUrlInput = emergencyState.docUrl
            selectedService = emergencyState.transportType
            lastAppliedSyncMs = syncMs
        } else if (docUrlInput.isBlank() && emergencyState.docUrl.isNotBlank()) {
            docUrlInput = emergencyState.docUrl
            selectedService = emergencyState.transportType
        }
    }

    BrandBackground(modifier = modifier.fillMaxSize(), enableAnimation = false) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                    Text(
                        text = "Экстренный обход (OpenFlux)",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Current Status Card
                val (statusColor, statusIcon, statusTitle, statusSubtitle) = when (emergencyState.status) {
                    EmergencyTransportStatus.VALID -> Quadruple(
                        Color(0xFF4CAF50),
                        Icons.Default.CheckCircle,
                        "Настроен и готов к работе",
                        "Документ подтверждён и синхронизирован с вашим аккаунтом"
                    )
                    EmergencyTransportStatus.INVALID -> {
                        val remainingText = formatRemainingDeleteTime(emergencyState.deleteAtMs)
                        Quadruple(
                            Color(0xFFFF7043),
                            Icons.Default.ErrorOutline,
                            "Ссылка на документ не работает",
                            "Просьба обновить ссылку. Удалится с сервера через $remainingText"
                        )
                    }
                    EmergencyTransportStatus.NOT_CONFIGURED -> Quadruple(
                        Color(0xFFF59E0B),
                        Icons.Default.WarningAmber,
                        "Не настроен",
                        "Настройте привязку документа заранее при наличии стабильного интернета"
                    )
                }

                Surface(
                    color = statusColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = statusTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = statusSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 3-Step Instructions Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                            RoundedCornerShape(20.dp)
                        )
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Как настроить заранее",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        InstructionStepRow(
                            step = "1",
                            text = "Создайте пустой документ или файл в вашем аккаунте Яндекс.Документов или Облака Mail.ru."
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        InstructionStepRow(
                            step = "2",
                            text = "Включите для него доступ по ссылке (достаточно права чтения или редактирования по ссылке)."
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        InstructionStepRow(
                            step = "3",
                            text = "Вставьте ссылку ниже и нажмите «Проверить и привязать» — она сохранится в аккаунте и будет работать на всех ваших устройствах при включении белых списков."
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Service & Link Configuration Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                            RoundedCornerShape(20.dp)
                        )
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "Сервис документа",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ServiceChoiceButton(
                                label = EmergencyTransportServiceType.YANDEX_DOCS.displayName,
                                selected = selectedService == EmergencyTransportServiceType.YANDEX_DOCS,
                                onClick = { selectedService = EmergencyTransportServiceType.YANDEX_DOCS },
                                modifier = Modifier.weight(1f)
                            )
                            ServiceChoiceButton(
                                label = EmergencyTransportServiceType.MAIL_RU.displayName,
                                selected = selectedService == EmergencyTransportServiceType.MAIL_RU,
                                onClick = { selectedService = EmergencyTransportServiceType.MAIL_RU },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = docUrlInput,
                            onValueChange = { docUrlInput = it },
                            label = { Text("Ссылка на документ (HTTPS)") },
                            placeholder = {
                                Text(
                                    if (selectedService == EmergencyTransportServiceType.YANDEX_DOCS) {
                                        "https://docs.yandex.ru/..."
                                    } else {
                                        "https://cloud.mail.ru/public/..."
                                    }
                                )
                            },
                            leadingIcon = {
                                Icon(imageVector = Icons.Default.Link, contentDescription = null)
                            },
                            trailingIcon = {
                                if (docUrlInput.isNotEmpty()) {
                                    IconButton(onClick = { docUrlInput = "" }) {
                                        Icon(imageVector = Icons.Default.Close, contentDescription = "Очистить")
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )

                        if (!emergencyState.lastError.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = emergencyState.lastError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                val trimmed = docUrlInput.trim()
                                if (!trimmed.startsWith("https://", ignoreCase = true)) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Укажите корректную HTTPS-ссылку на документ")
                                    }
                                    return@Button
                                }
                                scope.launch {
                                    val result = repo.bindAndVerify(trimmed, selectedService)
                                    result.onSuccess { updated ->
                                        lastAppliedSyncMs = updated.lastSyncedAtMs
                                        if (updated.status == EmergencyTransportStatus.VALID) {
                                            snackbarHostState.showSnackbar("Документ проверен и привязан к аккаунту")
                                        } else {
                                            snackbarHostState.showSnackbar("Ссылка сохранена, но документ пока не прошёл проверку")
                                        }
                                    }.onFailure { err ->
                                        snackbarHostState.showSnackbar(err.message ?: "Не удалось проверить ссылку")
                                    }
                                }
                            },
                            enabled = !emergencyState.isSyncing && docUrlInput.isNotBlank(),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            if (emergencyState.isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Проверяем документ...")
                            } else {
                                Text(
                                    text = "Проверить и привязать",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (emergencyState.status != EmergencyTransportStatus.NOT_CONFIGURED ||
                            emergencyState.docUrl.isNotBlank()
                        ) {
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val res = repo.deleteBinding()
                                        res.onSuccess { cleared ->
                                            docUrlInput = ""
                                            lastAppliedSyncMs = cleared.lastSyncedAtMs
                                            snackbarHostState.showSnackbar("Привязка удалена из аккаунта")
                                        }.onFailure { err ->
                                            snackbarHostState.showSnackbar(err.message ?: "Ошибка удаления привязки")
                                        }
                                    }
                                },
                                enabled = !emergencyState.isSyncing,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Удалить привязку",
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun InstructionStepRow(
    step: String,
    text: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = step,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ServiceChoiceButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeColor = MaterialTheme.colorScheme.primary
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) activeColor.copy(alpha = 0.15f) else Color.Transparent,
            contentColor = if (selected) activeColor else MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) activeColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
    ) {
        Text(
            text = label,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

internal fun formatRemainingDeleteTime(deleteAtMs: Long?): String {
    if (deleteAtMs == null) return "3 ч. 0 мин."
    val remainingMs = max(0L, deleteAtMs - System.currentTimeMillis())
    val totalMinutes = (remainingMs / 60_000L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return "$hours ч. $minutes мин."
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
