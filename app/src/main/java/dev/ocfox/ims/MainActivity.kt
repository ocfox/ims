package dev.ocfox.ims

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ocfox.ims.ui.ImsViewModel
import dev.ocfox.ims.ui.MainScreen
import dev.ocfox.ims.ui.theme.OcfoxImsTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ImsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OcfoxImsTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val logs by viewModel.diagnosticLogs.collectAsStateWithLifecycle()
                MainScreen(
                    state = state,
                    diagnosticLogs = logs,
                    events = viewModel.events,
                    onRequestShizuku = viewModel::requestShizukuPermission,
                    onRefresh = viewModel::refresh,
                    onOpenLogs = viewModel::openLogDialog,
                    onDismissLogs = viewModel::dismissLogDialog,
                    onClearLogs = viewModel::clearLogs,
                    onBuildDiagnosticReport = viewModel::buildDiagnosticReport,
                    onSelectSim = viewModel::selectSim,
                    onUpdatePolicy = viewModel::updateSelectedPolicy,
                    onApply = viewModel::applySelected,
                    onRestartIms = viewModel::restartSelectedIms,
                    onReset = viewModel::resetSelected,
                    onFixVoWiFi = viewModel::fixVoWiFiAndApply,
                    onToggleAutoApply = viewModel::setAutoApply,
                )
            }
        }
    }
}
