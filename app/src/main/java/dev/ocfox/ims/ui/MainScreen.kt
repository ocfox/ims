package dev.ocfox.ims.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WifiCalling3
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ocfox.ims.R
import dev.ocfox.ims.model.ImsRegState
import dev.ocfox.ims.model.ImsTransport
import dev.ocfox.ims.model.ShizukuStatus
import dev.ocfox.ims.model.SimImsPolicy
import dev.ocfox.ims.model.SimStatus
import dev.ocfox.ims.model.WfcMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow

@Composable
fun MainScreen(
    state: MainUiState,
    diagnosticLogs: List<String>,
    events: SharedFlow<UiEvent>,
    onRequestShizuku: () -> Unit,
    onRefresh: () -> Unit,
    onOpenLogs: () -> Unit,
    onDismissLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onBuildDiagnosticReport: () -> String,
    onSelectSim: (Int) -> Unit,
    onUpdatePolicy: ((SimImsPolicy) -> SimImsPolicy) -> Unit,
    onApply: (restartIms: Boolean) -> Unit,
    onRestartIms: () -> Unit,
    onReset: () -> Unit,
    onFixVoWiFi: () -> Unit,
    onToggleAutoApply: (Boolean) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(events) {
        events.collect { event ->
            val msg = when (event) {
                is UiEvent.MessageRes -> context.getString(event.resId)
                is UiEvent.MessageText -> event.text
            }
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    ShizukuStatusChip(
                        status = state.shizukuStatus,
                        onClick = onRequestShizuku,
                    )
                    IconButton(onClick = onOpenLogs) {
                        Icon(
                            imageVector = Icons.Rounded.Terminal,
                            contentDescription = stringResource(R.string.action_logs),
                        )
                    }
                    IconButton(
                        onClick = onRefresh,
                        enabled = !state.isBusy,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (state.selectedSim != null && state.shizukuStatus == ShizukuStatus.READY) {
                BottomActionBar(
                    isBusy = state.isBusy,
                    onApplyAndRestart = { onApply(true) },
                    onApplyOnly = { onApply(false) },
                    onRestartOnly = onRestartIms,
                    onReset = onReset,
                )
            }
        },
    ) { innerPadding ->
        val listState = rememberLazyListState()
        LaunchedEffect(state.sims.isEmpty()) {
            if (state.sims.isNotEmpty()) {
                listState.scrollToItem(0)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.sims.size > 1) {
                item(key = "sim_switcher") {
                    SimSwitcher(
                        sims = state.sims,
                        selectedSubId = state.selectedSim?.subId,
                        onSelect = onSelectSim,
                    )
                }
            }

            val sim = state.selectedSim
            val policy = state.selectedPolicy
            if (sim == null || policy == null) {
                item(key = "empty") {
                    EmptyStateCard(
                        status = state.shizukuStatus,
                        loadError = state.simLoadError,
                        isBusy = state.isBusy,
                        onAction = {
                            if (state.shizukuStatus == ShizukuStatus.READY) onRefresh()
                            else onRequestShizuku()
                        },
                    )
                }
            } else {
                item(key = "hero_${sim.subId}") {
                    SimHeroCard(
                        sim = sim,
                        isBusy = state.isBusy,
                        onFixVoWiFi = onFixVoWiFi,
                    )
                }

                item(key = "section_ims") {
                    SectionLabel(stringResource(R.string.section_ims))
                    Spacer(Modifier.height(8.dp))
                    GroupedCards(
                        items = listOf(
                            {
                                SwitchSettingRow(
                                    icon = Icons.Rounded.Call,
                                    title = stringResource(R.string.feat_volte),
                                    subtitle = stringResource(R.string.feat_volte_desc),
                                    checked = policy.enableVoLte,
                                    onCheckedChange = { checked ->
                                        onUpdatePolicy { it.copy(enableVoLte = checked) }
                                    },
                                )
                            },
                            {
                                SwitchSettingRow(
                                    icon = Icons.Rounded.NetworkCheck,
                                    title = stringResource(R.string.feat_vonr),
                                    subtitle = stringResource(R.string.feat_vonr_desc),
                                    checked = policy.enableVoNr,
                                    onCheckedChange = { checked ->
                                        onUpdatePolicy { it.copy(enableVoNr = checked) }
                                    },
                                )
                            },
                            {
                                Column {
                                    SwitchSettingRow(
                                        icon = Icons.Rounded.WifiCalling3,
                                        title = stringResource(R.string.feat_vowifi),
                                        subtitle = stringResource(R.string.feat_vowifi_desc),
                                        checked = policy.enableVoWiFi,
                                        onCheckedChange = { checked ->
                                            onUpdatePolicy { it.copy(enableVoWiFi = checked) }
                                        },
                                    )
                                    AnimatedVisibility(
                                        visible = policy.enableVoWiFi,
                                        enter = expandVertically() + fadeIn(),
                                        exit = shrinkVertically() + fadeOut(),
                                    ) {
                                        WfcModeSelector(
                                            selectedMode = policy.wfcMode,
                                            onSelect = { mode ->
                                                onUpdatePolicy { it.copy(wfcMode = mode) }
                                            },
                                        )
                                    }
                                }
                            },
                            {
                                SwitchSettingRow(
                                    icon = Icons.Rounded.SimCard,
                                    title = stringResource(R.string.feat_cross_sim),
                                    subtitle = stringResource(R.string.feat_cross_sim_desc),
                                    checked = policy.enableCrossSim,
                                    onCheckedChange = { checked ->
                                        onUpdatePolicy { it.copy(enableCrossSim = checked) }
                                    },
                                )
                            },
                            {
                                SwitchSettingRow(
                                    icon = Icons.Rounded.Call,
                                    title = stringResource(R.string.feat_ut),
                                    subtitle = stringResource(R.string.feat_ut_desc),
                                    checked = policy.enableUt,
                                    onCheckedChange = { checked ->
                                        onUpdatePolicy { it.copy(enableUt = checked) }
                                    },
                                )
                            },
                            {
                                SwitchSettingRow(
                                    icon = Icons.Rounded.Call,
                                    title = stringResource(R.string.feat_vt),
                                    subtitle = stringResource(R.string.feat_vt_desc),
                                    checked = policy.enableVt,
                                    onCheckedChange = { checked ->
                                        onUpdatePolicy { it.copy(enableVt = checked) }
                                    },
                                )
                            },
                        ),
                    )
                }

                item(key = "section_5g") {
                    SectionLabel(stringResource(R.string.section_5g))
                    Spacer(Modifier.height(8.dp))
                    GroupedCards(
                        items = listOf(
                            {
                                SwitchSettingRow(
                                    icon = Icons.Rounded.SignalCellularAlt,
                                    title = stringResource(R.string.feat_5g_nr),
                                    subtitle = stringResource(R.string.feat_5g_nr_desc),
                                    checked = policy.enable5gNr,
                                    onCheckedChange = { checked ->
                                        onUpdatePolicy { it.copy(enable5gNr = checked) }
                                    },
                                )
                            },
                            {
                                SwitchSettingRow(
                                    icon = Icons.Rounded.SignalCellularAlt,
                                    title = stringResource(R.string.feat_5g_thresholds),
                                    subtitle = stringResource(R.string.feat_5g_thresholds_desc),
                                    checked = policy.enable5gThresholds,
                                    enabled = policy.enable5gNr,
                                    onCheckedChange = { checked ->
                                        onUpdatePolicy { it.copy(enable5gThresholds = checked) }
                                    },
                                )
                            },
                            {
                                SwitchSettingRow(
                                    icon = Icons.Rounded.AutoAwesome,
                                    title = stringResource(R.string.feat_5g_plus),
                                    subtitle = stringResource(R.string.feat_5g_plus_desc),
                                    checked = policy.enable5gPlusIcon,
                                    enabled = policy.enable5gNr,
                                    onCheckedChange = { checked ->
                                        onUpdatePolicy { it.copy(enable5gPlusIcon = checked) }
                                    },
                                )
                            },
                        ),
                    )
                }

                item(key = "auto_apply") {
                    GroupedCards(
                        items = listOf {
                            SwitchSettingRow(
                                icon = Icons.Rounded.RestartAlt,
                                title = stringResource(R.string.auto_apply_label),
                                subtitle = stringResource(R.string.auto_apply_desc),
                                checked = state.autoApplyOnShizuku,
                                onCheckedChange = onToggleAutoApply,
                            )
                        },
                    )
                }
            }
        }
    }

    if (state.logDialogVisible) {
        DiagnosticLogDialog(
            logs = diagnosticLogs,
            reason = state.logDialogReason,
            onBuildReport = onBuildDiagnosticReport,
            onClear = onClearLogs,
            onDismiss = onDismissLogs,
        )
    }
}

@Composable
private fun ShizukuStatusChip(
    status: ShizukuStatus,
    onClick: () -> Unit,
) {
    val label = when (status) {
        ShizukuStatus.READY -> stringResource(R.string.shizuku_ready)
        ShizukuStatus.NEED_PERMISSION -> stringResource(R.string.shizuku_need_permission)
        ShizukuStatus.DISCONNECTED -> stringResource(R.string.shizuku_disconnected)
    }
    val containerColor = when (status) {
        ShizukuStatus.READY -> MaterialTheme.colorScheme.secondaryContainer
        ShizukuStatus.NEED_PERMISSION -> MaterialTheme.colorScheme.tertiaryContainer
        ShizukuStatus.DISCONNECTED -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (status) {
        ShizukuStatus.READY -> MaterialTheme.colorScheme.onSecondaryContainer
        ShizukuStatus.NEED_PERMISSION -> MaterialTheme.colorScheme.onTertiaryContainer
        ShizukuStatus.DISCONNECTED -> MaterialTheme.colorScheme.onErrorContainer
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = containerColor,
            labelColor = contentColor,
        ),
        border = null,
    )
}

@Composable
private fun SimSwitcher(
    sims: List<SimStatus>,
    selectedSubId: Int?,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        sims.forEachIndexed { index, sim ->
            val shapes = when (index) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                sims.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            val label = sim.displayName.ifBlank { sim.carrierName }.ifBlank {
                stringResource(R.string.sim_slot_label, sim.slotIndex + 1)
            }
            val isRegistered = sim.imsRegState == ImsRegState.REGISTERED
            ToggleButton(
                checked = sim.subId == selectedSubId,
                onCheckedChange = { onSelect(sim.subId) },
                shapes = shapes,
                colors = ToggleButtonDefaults.colors(),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isRegistered) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "SIM ${sim.slotIndex + 1} · $label",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SimHeroCard(
    sim: SimStatus,
    isBusy: Boolean,
    onFixVoWiFi: () -> Unit,
) {
    val isRegistered = sim.imsRegState == ImsRegState.REGISTERED
    val containerColor by animateColorAsState(
        targetValue = when (sim.imsRegState) {
            ImsRegState.REGISTERED -> MaterialTheme.colorScheme.primaryContainer
            ImsRegState.REGISTERING -> MaterialTheme.colorScheme.secondaryContainer
            ImsRegState.NOT_REGISTERED -> MaterialTheme.colorScheme.errorContainer
            ImsRegState.UNKNOWN -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        label = "heroColor",
    )
    val contentColor = when (sim.imsRegState) {
        ImsRegState.REGISTERED -> MaterialTheme.colorScheme.onPrimaryContainer
        ImsRegState.REGISTERING -> MaterialTheme.colorScheme.onSecondaryContainer
        ImsRegState.NOT_REGISTERED -> MaterialTheme.colorScheme.onErrorContainer
        ImsRegState.UNKNOWN -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isBusy) {
                    LoadingIndicator(modifier = Modifier.size(28.dp), color = contentColor)
                } else {
                    Icon(
                        imageVector = if (isRegistered) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (sim.imsRegState) {
                            ImsRegState.REGISTERED -> stringResource(R.string.status_registered)
                            ImsRegState.REGISTERING -> stringResource(R.string.status_registering)
                            ImsRegState.NOT_REGISTERED -> stringResource(R.string.status_not_registered)
                            ImsRegState.UNKNOWN -> stringResource(R.string.status_unknown)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    val carrierLine = buildString {
                        append(sim.carrierName.ifBlank { sim.displayName })
                        if (sim.plmnLabel.isNotBlank()) {
                            append(" · ")
                            append(sim.plmnLabel)
                        }
                        append(" · subId ")
                        append(sim.subId)
                    }
                    Text(
                        text = carrierLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor.copy(alpha = 0.85f),
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val transportLabel = when (sim.imsTransport) {
                    ImsTransport.CELLULAR -> stringResource(R.string.transport_cellular)
                    ImsTransport.WLAN -> stringResource(R.string.transport_wlan)
                    ImsTransport.UNKNOWN -> stringResource(R.string.transport_unknown)
                }
                StatusBadge(text = transportLabel)
                StatusBadge(
                    text = if (sim.isOverrideActive) {
                        stringResource(R.string.override_applied)
                    } else {
                        stringResource(R.string.override_default)
                    }
                )
            }

            if (sim.hasChinaVoWiFiRisk || sim.hasWifiPreferredRisk) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = stringResource(
                                    if (sim.hasChinaVoWiFiRisk) R.string.warning_cn_vowifi_title
                                    else R.string.warning_wifi_pref_title
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            text = if (sim.hasChinaVoWiFiRisk) {
                                stringResource(R.string.warning_cn_vowifi_body, sim.plmnLabel)
                            } else {
                                stringResource(R.string.warning_wifi_pref_body)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilledTonalButton(
                            onClick = onFixVoWiFi,
                            enabled = !isBusy,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(stringResource(R.string.action_fix_now))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun GroupedCards(items: List<@Composable () -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { index, itemContent ->
            Surface(
                shape = groupedShape(index, items.size),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemContent()
            }
        }
    }
}

private fun groupedShape(index: Int, total: Int): Shape {
    if (total == 1) return RoundedCornerShape(24.dp)
    return when (index) {
        0 -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 6.dp, bottomEnd = 6.dp)
        total - 1 -> RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
        else -> RoundedCornerShape(6.dp)
    }
}

@Composable
private fun SwitchSettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun WfcModeSelector(
    selectedMode: WfcMode,
    onSelect: (WfcMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 52.dp, end = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.wfc_mode_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            ToggleButton(
                checked = selectedMode == WfcMode.CELLULAR_PREFERRED,
                onCheckedChange = { onSelect(WfcMode.CELLULAR_PREFERRED) },
                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.wfc_mode_cellular), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ToggleButton(
                checked = selectedMode == WfcMode.WIFI_PREFERRED,
                onCheckedChange = { onSelect(WfcMode.WIFI_PREFERRED) },
                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.wfc_mode_wifi), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EmptyStateCard(
    status: ShizukuStatus,
    loadError: String?,
    isBusy: Boolean,
    onAction: () -> Unit,
) {
    val isError = status == ShizukuStatus.READY && !loadError.isNullOrBlank()
    val title = when {
        isError -> stringResource(R.string.sim_load_error_title)
        status != ShizukuStatus.READY -> stringResource(R.string.sim_waiting_shizuku_title)
        else -> stringResource(R.string.sim_empty_title)
    }
    val subtitle = when {
        isError -> loadError.orEmpty()
        status != ShizukuStatus.READY -> stringResource(R.string.sim_waiting_shizuku_subtitle)
        else -> stringResource(R.string.sim_empty_subtitle)
    }
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onAction,
                enabled = !isBusy,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(
                    text = when (status) {
                        ShizukuStatus.READY -> stringResource(R.string.action_refresh)
                        else -> stringResource(R.string.shizuku_need_permission)
                    }
                )
            }
        }
    }
}

@Composable
private fun BottomActionBar(
    isBusy: Boolean,
    onApplyAndRestart: () -> Unit,
    onApplyOnly: () -> Unit,
    onRestartOnly: () -> Unit,
    onReset: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 3.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            SplitButtonLayout(
                leadingButton = {
                    SplitButtonDefaults.LeadingButton(
                        onClick = onApplyAndRestart,
                        enabled = !isBusy,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize),
                        )
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.action_apply_restart))
                    }
                },
                trailingButton = {
                    Box {
                        SplitButtonDefaults.TrailingButton(
                            checked = menuExpanded,
                            onCheckedChange = { menuExpanded = it },
                            enabled = !isBusy,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ExpandMore,
                                contentDescription = null,
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_apply_only)) },
                                leadingIcon = { Icon(Icons.Rounded.CheckCircle, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onApplyOnly()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_restart_ims)) },
                                leadingIcon = { Icon(Icons.Rounded.RestartAlt, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onRestartOnly()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_reset_default)) },
                                leadingIcon = { Icon(Icons.Rounded.SettingsBackupRestore, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onReset()
                                },
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun DiagnosticLogDialog(
    logs: List<String>,
    reason: String?,
    onBuildReport: () -> String,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var copiedFeedback by remember { mutableStateOf(false) }

    LaunchedEffect(copiedFeedback) {
        if (copiedFeedback) {
            delay(1800)
            copiedFeedback = false
        }
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(
                        if (reason != null) R.string.log_dialog_title_issue else R.string.log_dialog_title
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    color = AlertDialogDefaults.titleContentColor,
                )

                if (!reason.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.log_timeline_header, logs.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (logs.isNotEmpty()) {
                        TextButton(
                            onClick = onClear,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(stringResource(R.string.action_clear))
                        }
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 300.dp),
                ) {
                    val contentText = if (logs.isEmpty()) {
                        stringResource(R.string.log_empty_hint)
                    } else {
                        logs.joinToString("\n")
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(12.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                text = contentText,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = if (logs.isEmpty()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(stringResource(R.string.action_close), maxLines = 1)
                    }

                    Spacer(Modifier.weight(1f))

                    FilledTonalButton(
                        onClick = {
                            val report = onBuildReport()
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.log_dialog_title))
                                putExtra(Intent.EXTRA_TEXT, report)
                            }
                            context.startActivity(
                                Intent.createChooser(sendIntent, context.getString(R.string.action_share))
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_share), maxLines = 1)
                    }

                    Spacer(Modifier.width(8.dp))

                    Button(
                        onClick = {
                            val report = onBuildReport()
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Pixel IMS Diagnostics", report))
                            copiedFeedback = true
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            imageVector = if (copiedFeedback) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(
                                if (copiedFeedback) R.string.action_copied else R.string.action_copy
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
