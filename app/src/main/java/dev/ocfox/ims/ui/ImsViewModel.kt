package dev.ocfox.ims.ui

import android.app.Application
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ocfox.ims.R
import dev.ocfox.ims.data.DiagnosticLogger
import dev.ocfox.ims.data.PolicyRepository
import dev.ocfox.ims.model.ImsRegState
import dev.ocfox.ims.model.ImsTransport
import dev.ocfox.ims.model.ShizukuStatus
import dev.ocfox.ims.model.SimImsPolicy
import dev.ocfox.ims.model.SimStatus
import dev.ocfox.ims.model.WfcMode
import dev.ocfox.ims.privileged.ReregistrationOutcome
import dev.ocfox.ims.privileged.TelephonyBridge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

data class MainUiState(
    val shizukuStatus: ShizukuStatus = ShizukuStatus.DISCONNECTED,
    val sims: List<SimStatus> = emptyList(),
    val selectedSubId: Int? = null,
    val policies: Map<Int, SimImsPolicy> = emptyMap(),
    val autoApplyOnShizuku: Boolean = true,
    val isBusy: Boolean = false,
    val simLoadError: String? = null,
    val logDialogVisible: Boolean = false,
    val logDialogReason: String? = null,
) {
    val selectedSim: SimStatus?
        get() = sims.firstOrNull { it.subId == selectedSubId } ?: sims.firstOrNull()

    val selectedPolicy: SimImsPolicy?
        get() {
            val sim = selectedSim ?: return null
            return policies[sim.subId] ?: SimImsPolicy.smartDefaultFor(sim.mcc)
        }
}

sealed interface UiEvent {
    data class MessageRes(@param:StringRes val resId: Int) : UiEvent
    data class MessageText(val text: String) : UiEvent
}

class ImsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PolicyRepository(application)

    private val _uiState = MutableStateFlow(
        MainUiState(autoApplyOnShizuku = repository.isAutoApplyEnabled())
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val diagnosticLogs: StateFlow<List<String>> = DiagnosticLogger.logs

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        DiagnosticLogger.i(TAG, "Shizuku binder received")
        refreshShizukuAndSims()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        DiagnosticLogger.w(TAG, "Shizuku binder dead")
        _uiState.update { it.copy(shizukuStatus = ShizukuStatus.DISCONNECTED) }
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
            DiagnosticLogger.i(TAG, "Shizuku permission granted")
            refreshShizukuAndSims(applyMissing = true)
        } else {
            DiagnosticLogger.w(TAG, "Shizuku permission denied (grantResult=$grantResult)")
            refreshShizukuStatusOnly()
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refreshShizukuAndSims()
    }

    override fun onCleared() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        super.onCleared()
    }

    fun requestShizukuPermission() {
        if (!Shizuku.pingBinder()) {
            _events.tryEmit(UiEvent.MessageRes(R.string.msg_shizuku_needed))
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            refreshShizukuAndSims()
        } else {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        }
    }

    fun selectSim(subId: Int) {
        _uiState.update { it.copy(selectedSubId = subId) }
    }

    fun setAutoApply(enabled: Boolean) {
        repository.setAutoApplyEnabled(enabled)
        _uiState.update { it.copy(autoApplyOnShizuku = enabled) }
    }

    fun updateSelectedPolicy(transform: (SimImsPolicy) -> SimImsPolicy) {
        val sim = _uiState.value.selectedSim ?: return
        val current = _uiState.value.policies[sim.subId] ?: repository.getOrCreatePolicy(sim.subId, sim.mcc)
        val updated = transform(current)
        repository.savePolicy(sim.subId, updated)
        _uiState.update { state ->
            state.copy(policies = state.policies + (sim.subId to updated))
        }
    }

    fun refresh() {
        refreshShizukuAndSims(openDialogOnError = true)
    }

    fun openLogDialog() {
        _uiState.update { it.copy(logDialogVisible = true, logDialogReason = null) }
    }

    fun dismissLogDialog() {
        _uiState.update { it.copy(logDialogVisible = false, logDialogReason = null) }
    }

    fun clearLogs() {
        DiagnosticLogger.clear()
    }

    fun buildDiagnosticReport(): String {
        val state = _uiState.value
        return DiagnosticLogger.buildReport(
            shizukuStatus = state.shizukuStatus.name,
            sims = state.sims,
            extraSummary = state.logDialogReason,
        )
    }

    fun applySelected(restartIms: Boolean) {
        val sim = _uiState.value.selectedSim ?: return
        val policy = _uiState.value.policies[sim.subId] ?: repository.getOrCreatePolicy(sim.subId, sim.mcc)
        if (!isShizukuReady()) {
            _events.tryEmit(UiEvent.MessageRes(R.string.msg_shizuku_needed))
            return
        }
        repository.savePolicy(sim.subId, policy)
        runBusyTask {
            val result = TelephonyBridge.applyPolicy(
                context = getApplication(),
                subId = sim.subId,
                slotIndex = sim.slotIndex,
                policy = policy,
                restartIms = restartIms,
                onProgress = { state, transport ->
                    updateSimProgress(sim.subId, state, transport)
                },
            )
            result.fold(
                onSuccess = { outcome ->
                    if (restartIms) {
                        handleRestartOutcome(outcome, R.string.msg_applied_restarted)
                    } else {
                        reloadSims()
                        _events.tryEmit(UiEvent.MessageRes(R.string.msg_applied_only))
                    }
                },
                onFailure = ::openLogWithError,
            )
        }
    }

    fun fixVoWiFiAndApply() {
        updateSelectedPolicy { policy ->
            val sim = _uiState.value.selectedSim
            if (sim?.mcc == "460") {
                policy.copy(enableVoWiFi = false, wfcMode = WfcMode.CELLULAR_PREFERRED)
            } else {
                policy.copy(wfcMode = WfcMode.CELLULAR_PREFERRED)
            }
        }
        applySelected(restartIms = true)
    }

    fun restartSelectedIms() {
        val sim = _uiState.value.selectedSim ?: return
        if (!isShizukuReady()) {
            _events.tryEmit(UiEvent.MessageRes(R.string.msg_shizuku_needed))
            return
        }
        runBusyTask {
            val result = TelephonyBridge.restartIms(
                context = getApplication(),
                subId = sim.subId,
                slotIndex = sim.slotIndex,
                onProgress = { state, transport ->
                    updateSimProgress(sim.subId, state, transport)
                },
            )
            result.fold(
                onSuccess = { outcome ->
                    handleRestartOutcome(outcome, R.string.msg_restarted)
                },
                onFailure = ::openLogWithError,
            )
        }
    }

    fun resetSelected() {
        val sim = _uiState.value.selectedSim ?: return
        if (!isShizukuReady()) {
            _events.tryEmit(UiEvent.MessageRes(R.string.msg_shizuku_needed))
            return
        }
        runBusyTask {
            val result = TelephonyBridge.resetPolicy(
                context = getApplication(),
                subId = sim.subId,
                slotIndex = sim.slotIndex,
                onProgress = { state, transport ->
                    updateSimProgress(sim.subId, state, transport)
                },
            )
            result.fold(
                onSuccess = { outcome ->
                    val defaults = SimImsPolicy.smartDefaultFor(sim.mcc)
                    repository.savePolicy(sim.subId, defaults)
                    _uiState.update { state ->
                        state.copy(policies = state.policies + (sim.subId to defaults))
                    }
                    handleRestartOutcome(outcome, R.string.msg_reset_done)
                },
                onFailure = ::openLogWithError,
            )
        }
    }

    private fun refreshShizukuStatusOnly(): ShizukuStatus {
        val status = when {
            !Shizuku.pingBinder() -> ShizukuStatus.DISCONNECTED
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuStatus.READY
            else -> ShizukuStatus.NEED_PERMISSION
        }
        _uiState.update {
            it.copy(
                shizukuStatus = status,
                simLoadError = if (status == ShizukuStatus.READY) it.simLoadError else null,
            )
        }
        return status
    }

    private fun isShizukuReady(): Boolean = refreshShizukuStatusOnly() == ShizukuStatus.READY

    private fun refreshShizukuAndSims(
        applyMissing: Boolean = false,
        openDialogOnError: Boolean = false,
    ) {
        val status = refreshShizukuStatusOnly()
        if (status != ShizukuStatus.READY) return
        runBusyTask {
            if (applyMissing && repository.isAutoApplyEnabled()) {
                TelephonyBridge.applyMissingPoliciesForAllSims(getApplication(), repository)
            }
            reloadSims(openDialogOnError = openDialogOnError)
        }
    }

    private suspend fun reloadSims(openDialogOnError: Boolean = false) {
        val result = runCatching { TelephonyBridge.readSimStatuses(getApplication()) }
        result.onFailure { error ->
            DiagnosticLogger.e(TAG, "Failed to read SIM statuses", error)
        }
        val list = result.getOrDefault(emptyList())
        val loadError = result.exceptionOrNull()?.let { err ->
            err.message?.takeIf { it.isNotBlank() }?.let { "${err.javaClass.simpleName}: $it" }
                ?: err.javaClass.simpleName
        }
        _uiState.update { current ->
            val mergedPolicies = buildMap {
                putAll(current.policies)
                for (sim in list) {
                    val savedOrDefault = if (repository.hasSavedPolicy(sim.subId)) {
                        repository.getOrCreatePolicy(sim.subId, sim.mcc)
                    } else {
                        val initial = SimImsPolicy.smartDefaultFor(sim.mcc)
                        repository.savePolicy(sim.subId, initial)
                        initial
                    }
                    putIfAbsent(sim.subId, savedOrDefault)
                }
            }
            val nextSelected = current.selectedSubId?.takeIf { id -> list.any { it.subId == id } }
                ?: list.firstOrNull()?.subId
            current.copy(
                sims = list,
                selectedSubId = nextSelected,
                policies = mergedPolicies,
                simLoadError = loadError,
                logDialogVisible = current.logDialogVisible || (openDialogOnError && loadError != null),
                logDialogReason = if (openDialogOnError && loadError != null) loadError else current.logDialogReason,
            )
        }
    }

    private fun updateSimProgress(subId: Int, regState: ImsRegState, transport: ImsTransport) {
        _uiState.update { state ->
            state.copy(
                sims = state.sims.map { item ->
                    if (item.subId == subId) {
                        item.copy(imsRegState = regState, imsTransport = transport)
                    } else {
                        item
                    }
                }
            )
        }
    }

    private suspend fun handleRestartOutcome(
        outcome: ReregistrationOutcome,
        @StringRes successRes: Int,
    ) {
        reloadSims()
        if (outcome.timedOut) {
            val base = getApplication<Application>().getString(R.string.msg_reregister_timeout)
            val reasonText = if (!outcome.lastReason.isNullOrBlank()) {
                "$base (${outcome.lastReason})"
            } else {
                base
            }
            _events.tryEmit(UiEvent.MessageText(reasonText))
            _uiState.update { it.copy(logDialogVisible = true, logDialogReason = reasonText) }
        } else {
            _events.tryEmit(UiEvent.MessageRes(successRes))
        }
    }

    private fun openLogWithError(error: Throwable) {
        val msg = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        DiagnosticLogger.e(TAG, "Operation error: $msg", error)
        _events.tryEmit(UiEvent.MessageText(msg))
        _uiState.update { it.copy(logDialogVisible = true, logDialogReason = msg) }
    }

    private fun runBusyTask(block: suspend () -> Unit) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                block()
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    private companion object {
        const val TAG = "ImsViewModel"
        const val SHIZUKU_REQUEST_CODE = 1001
    }
}
