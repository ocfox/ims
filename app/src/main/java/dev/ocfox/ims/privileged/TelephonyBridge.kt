package dev.ocfox.ims.privileged

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.PersistableBundle
import android.system.Os
import android.telephony.AccessNetworkConstants
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.ims.ImsManager
import android.telephony.ims.ImsMmTelManager
import android.telephony.ims.ImsReasonInfo
import android.telephony.ims.ImsRegistrationAttributes
import android.telephony.ims.ProvisioningManager
import android.telephony.ims.RegistrationManager
import dev.ocfox.ims.BuildConfig
import dev.ocfox.ims.data.DiagnosticLogger
import dev.ocfox.ims.data.PolicyRepository
import dev.ocfox.ims.model.ImsRegState
import dev.ocfox.ims.model.ImsTransport
import dev.ocfox.ims.model.SimImsPolicy
import dev.ocfox.ims.model.SimStatus
import dev.ocfox.ims.model.WfcMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executor
import kotlin.coroutines.resume

data class ReregistrationOutcome(
    val attemptedRestart: Boolean,
    val registered: Boolean,
    val timedOut: Boolean,
    val lastState: ImsRegState = ImsRegState.UNKNOWN,
    val lastTransport: ImsTransport = ImsTransport.UNKNOWN,
    val lastReason: String? = null,
)

object TelephonyBridge {
    private const val TAG = "TelephonyBridge"
    private const val CALLBACK_TIMEOUT_MS = 1200L
    private const val REREGISTER_TIMEOUT_MS = 10_000L
    private const val INSTRUMENTATION_TIMEOUT_MS = 22_000L
    private const val FINISH_WAIT_MS = 2_000L
    private const val STICKY_CALLBACK_SETTLE_MS = 120L
    private const val POLL_INTERVAL_MS = 500L
    private const val INSTR_FLAG_NO_RESTART = 8
    private val mutex = Mutex()
    private val directExecutor = Executor { it.run() }

    private fun ensureShizukuReady(): Boolean = Shizuku.pingBinder()

    internal suspend inline fun <T> executeDelegated(block: suspend () -> T): T {
        val am = HiddenSystemApi.getActivityManager()
        HiddenSystemApi.startDelegateShellPermissionIdentity(am, Os.getuid())
        return try {
            block()
        } finally {
            runCatching {
                HiddenSystemApi.stopDelegateShellPermissionIdentity(am, Os.getuid())
            }.onFailure {
                DiagnosticLogger.w(TAG, "stopDelegateShellPermissionIdentity failed", it)
            }
        }
    }

    private suspend fun <T> runWithInstrumentation(
        context: Context,
        block: suspend (Context) -> T,
    ): T {
        val am = HiddenSystemApi.getActivityManager()
        val finishedDeferred = CompletableDeferred<Unit>()
        val watcher = HiddenSystemApi.createInstrumentationWatcher {
            finishedDeferred.complete(Unit)
        }
        val uiConnection = HiddenSystemApi.newUiAutomationConnection()
        val (requestId, task) = ImsInstrumentation.enqueue { ctx -> block(ctx) }
        val args = Bundle().apply { putLong(ImsInstrumentation.EXTRA_REQUEST_ID, requestId) }
        var started = false
        try {
            started = HiddenSystemApi.startInstrumentation(
                am = am,
                component = ComponentName(context, ImsInstrumentation::class.java),
                flags = INSTR_FLAG_NO_RESTART,
                args = args,
                watcher = watcher,
                connection = uiConnection,
            )
            check(started) { "startInstrumentation returned false" }
            val result = withTimeout(INSTRUMENTATION_TIMEOUT_MS) {
                task.resultDeferred.await()
            }
            @Suppress("UNCHECKED_CAST")
            return result.getOrThrow() as T
        } finally {
            ImsInstrumentation.cancel(requestId)
            if (started) {
                withContext(NonCancellable) {
                    withTimeoutOrNull(FINISH_WAIT_MS) {
                        finishedDeferred.await()
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun readSimStatuses(context: Context): List<SimStatus> = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!ensureShizukuReady()) return@withContext emptyList()
            runWithInstrumentation(context) { privContext ->
                val cm = privContext.getSystemService(CarrierConfigManager::class.java)
                val imsManager = privContext.getSystemService(ImsManager::class.java)
                val telephony = HiddenSystemApi.getTelephonyService()
                val subList = readActiveSubscriptions(privContext)

                val sims = subList.sortedBy { it.simSlotIndex }.map { info ->
                    val subId = info.subscriptionId
                    val mcc = info.mccString ?: ""
                    val mnc = info.mncString ?: ""
                    val carrierConfig = runCatching {
                        cm.getConfigForSubId(subId, *CarrierConfigFactory.READ_KEYS)
                    }.getOrNull()
                    val isOverrideActive = carrierConfig?.getInt(CarrierConfigFactory.KEY_CONFIG_VERSION, 0) ==
                        BuildConfig.VERSION_CODE

                    val mmTel = runCatching { imsManager?.getImsMmTelManager(subId) }.getOrNull()
                    val (regState, transport) = readImsStateAndTransport(mmTel, telephony, subId)
                    val wfcEnabled = runCatching { mmTel?.isVoWiFiSettingEnabled }.getOrNull()
                    val wfcMode = runCatching { mmTel?.getVoWiFiModeSetting() }.getOrNull()

                    SimStatus(
                        subId = subId,
                        slotIndex = info.simSlotIndex,
                        displayName = info.displayName?.toString().orEmpty(),
                        carrierName = info.carrierName?.toString().orEmpty(),
                        mcc = mcc,
                        mnc = mnc,
                        imsRegState = regState,
                        imsTransport = transport,
                        wfcSystemEnabled = wfcEnabled,
                        wfcSystemMode = wfcMode,
                        isOverrideActive = isOverrideActive,
                        livePolicy = carrierConfig?.let { CarrierConfigFactory.readPolicyFromBundle(it) },
                    )
                }
                if (sims.isEmpty()) {
                    DiagnosticLogger.w(TAG, "Loaded 0 active SIMs")
                } else {
                    val summary = sims.joinToString(", ") {
                        "slot${it.slotIndex}(sub=${it.subId},${it.plmnLabel})=${it.imsRegState}/${it.imsTransport}"
                    }
                    DiagnosticLogger.i(TAG, "Loaded ${sims.size} SIM(s): $summary")
                }
                sims
            }
        }
    }

    suspend fun applyPolicy(
        context: Context,
        subId: Int,
        slotIndex: Int,
        policy: SimImsPolicy,
        restartIms: Boolean,
        onProgress: ((ImsRegState, ImsTransport) -> Unit)? = null,
    ): Result<ReregistrationOutcome> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                check(ensureShizukuReady()) { "Shizuku binder not ready" }
                DiagnosticLogger.i(
                    TAG,
                    "Apply policy subId=$subId slot=$slotIndex restart=$restartIms " +
                        "(5G=${policy.enable5gNr}, NRVoice=${policy.enableVoNr}, VoLTE=${policy.enableVoLte}, " +
                        "VoWiFi=${policy.enableVoWiFi}/${policy.wfcMode}, CrossSIM=${policy.enableCrossSim})"
                )
                runWithInstrumentation(context) { privContext ->
                    applyPolicyInternal(privContext, subId, slotIndex, policy, restartIms, onProgress)
                }
            }.onFailure { error ->
                DiagnosticLogger.e(TAG, "Apply policy failed for subId=$subId", error)
            }
        }
    }

    suspend fun restartIms(
        context: Context,
        subId: Int,
        slotIndex: Int,
        onProgress: ((ImsRegState, ImsTransport) -> Unit)? = null,
    ): Result<ReregistrationOutcome> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                check(ensureShizukuReady()) { "Shizuku binder not ready" }
                DiagnosticLogger.i(TAG, "Restart IMS requested for subId=$subId slot=$slotIndex")
                runWithInstrumentation(context) { privContext ->
                    val imsManager = privContext.getSystemService(ImsManager::class.java)
                    val mmTel = runCatching { imsManager?.getImsMmTelManager(subId) }.getOrNull()
                    val telephony = HiddenSystemApi.getTelephonyService() ?: error("ITelephony unavailable")
                    val resolvedSlot = resolveSlotIndex(subId, slotIndex)
                    awaitReregistration(mmTel, telephony, subId, resolvedSlot, onProgress) {
                        HiddenSystemApi.resetIms(telephony, resolvedSlot)
                    }
                }
            }.onFailure { error ->
                DiagnosticLogger.e(TAG, "Restart IMS failed for subId=$subId", error)
            }
        }
    }

    suspend fun resetPolicy(
        context: Context,
        subId: Int,
        slotIndex: Int,
        onProgress: ((ImsRegState, ImsTransport) -> Unit)? = null,
    ): Result<ReregistrationOutcome> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                check(ensureShizukuReady()) { "Shizuku binder not ready" }
                DiagnosticLogger.i(TAG, "Reset CarrierConfig override requested for subId=$subId slot=$slotIndex")
                runWithInstrumentation(context) { privContext ->
                    val cm = privContext.getSystemService(CarrierConfigManager::class.java)
                    invokeOverrideConfig(cm, subId, null, preferPersistent(privContext))
                    val imsManager = privContext.getSystemService(ImsManager::class.java)
                    val mmTel = runCatching { imsManager?.getImsMmTelManager(subId) }.getOrNull()
                    val telephony = HiddenSystemApi.getTelephonyService() ?: error("ITelephony unavailable")
                    val resolvedSlot = resolveSlotIndex(subId, slotIndex)
                    awaitReregistration(mmTel, telephony, subId, resolvedSlot, onProgress) {
                        HiddenSystemApi.resetIms(telephony, resolvedSlot)
                    }
                }
            }.onFailure { error ->
                DiagnosticLogger.e(TAG, "Reset policy failed for subId=$subId", error)
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun applyMissingPoliciesForAllSims(
        context: Context,
        repository: PolicyRepository,
    ) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!ensureShizukuReady()) return@withContext
            runCatching {
                runWithInstrumentation(context) { privContext ->
                    val cm = privContext.getSystemService(CarrierConfigManager::class.java)
                    val subs = readActiveSubscriptions(privContext)
                    for (info in subs) {
                        val subId = info.subscriptionId
                        val current = runCatching {
                            cm.getConfigForSubId(subId, CarrierConfigFactory.KEY_CONFIG_VERSION)
                        }.getOrNull()
                        val alreadyApplied = current?.getInt(CarrierConfigFactory.KEY_CONFIG_VERSION, 0) ==
                            BuildConfig.VERSION_CODE
                        if (!alreadyApplied) {
                            val policy = repository.getOrCreatePolicy(subId, info.mccString)
                            repository.savePolicy(subId, policy)
                            applyPolicyInternal(privContext, subId, info.simSlotIndex, policy, restartIms = false)
                            DiagnosticLogger.i(TAG, "Auto-applied IMS policy for subId=$subId mcc=${info.mccString}")
                        }
                    }
                }
            }.onFailure {
                DiagnosticLogger.w(TAG, "Auto-apply on Shizuku failed", it)
            }
        }
    }

    private suspend fun applyPolicyInternal(
        context: Context,
        subId: Int,
        slotIndex: Int,
        policy: SimImsPolicy,
        restartIms: Boolean,
        onProgress: ((ImsRegState, ImsTransport) -> Unit)? = null,
    ): ReregistrationOutcome {
        val cm = context.getSystemService(CarrierConfigManager::class.java)
        val imsManager = context.getSystemService(ImsManager::class.java)
        val mmTel = runCatching { imsManager?.getImsMmTelManager(subId) }.getOrNull()
        val telephony = HiddenSystemApi.getTelephonyService()

        // When disabling VoWiFi, clear user-side WFC toggle/mode first while config still permits modification.
        if (!policy.enableVoWiFi && mmTel != null) {
            syncMmTelSettings(mmTel, policy)
        }

        val bundle = CarrierConfigFactory.toPersistableBundle(policy, BuildConfig.VERSION_CODE)
        invokeOverrideConfig(cm, subId, bundle, preferPersistent(context))

        // Sync VoIMS opt-in status across all active SIMs (not only default voice SIM).
        if (telephony != null) {
            runCatching {
                val voImsKey = ProvisioningManager::class.java
                    .getField("KEY_VOIMS_OPT_IN_STATUS")
                    .getInt(null)
                val enabledVal = ProvisioningManager::class.java
                    .getField("PROVISIONING_VALUE_ENABLED")
                    .getInt(null)
                val disabledVal = ProvisioningManager::class.java
                    .getField("PROVISIONING_VALUE_DISABLED")
                    .getInt(null)
                val targetOptIn = if (policy.enableVoLte) enabledVal else disabledVal
                val currentOptIn = HiddenSystemApi.getImsProvisioningInt(telephony, subId, voImsKey)
                if (currentOptIn != targetOptIn) {
                    HiddenSystemApi.setImsProvisioningInt(telephony, subId, voImsKey, targetOptIn)
                    DiagnosticLogger.i(TAG, "Updated VoIMS opt-in subId=$subId to $targetOptIn")
                }
            }.onFailure {
                DiagnosticLogger.w(TAG, "setImsProvisioningInt failed for subId=$subId", it)
            }
        } else {
            DiagnosticLogger.w(TAG, "ITelephony unavailable while syncing VoIMS opt-in for subId=$subId")
        }

        if (policy.enableVoWiFi && mmTel != null) {
            syncMmTelSettings(mmTel, policy)
        }

        if (!restartIms) {
            return ReregistrationOutcome(attemptedRestart = false, registered = false, timedOut = false)
        }

        checkNotNull(telephony) { "ITelephony unavailable for resetIms" }
        val resolvedSlot = resolveSlotIndex(subId, slotIndex)
        return awaitReregistration(mmTel, telephony, subId, resolvedSlot, onProgress) {
            HiddenSystemApi.resetIms(telephony, resolvedSlot)
        }
    }

    private suspend fun awaitReregistration(
        mmTel: ImsMmTelManager?,
        telephony: Any,
        subId: Int,
        slotIndex: Int,
        onProgress: ((ImsRegState, ImsTransport) -> Unit)?,
        triggerRestart: () -> Unit,
    ): ReregistrationOutcome {
        val (initialState, initialTransport) = readImsStateAndTransport(mmTel, telephony, subId)
        DiagnosticLogger.i(TAG, "Pre-reset IMS subId=$subId slot=$slotIndex: $initialState/$initialTransport")

        if (mmTel == null) {
            DiagnosticLogger.w(TAG, "ImsMmTelManager unavailable for subId=$subId; polling ITelephony")
            triggerRestart()
            return pollUntilRegistered(telephony, subId, onProgress)
        }

        var resetIssued = false
        var sawResetLifecycle = initialState != ImsRegState.REGISTERED
        var lastState = initialState
        var lastTransport = initialTransport
        var lastReason: String? = null
        val completion = CompletableDeferred<Unit>()

        val callback = object : RegistrationManager.RegistrationCallback() {
            override fun onRegistered(attributes: ImsRegistrationAttributes) {
                handleRegistered(mapTransport(attributes.transportType))
            }

            @Deprecated("Deprecated in Java")
            override fun onRegistered(imsTransportType: Int) {
                handleRegistered(mapTransport(imsTransportType))
            }

            override fun onRegistering(attributes: ImsRegistrationAttributes) {
                handleRegistering(mapTransport(attributes.transportType))
            }

            @Deprecated("Deprecated in Java")
            override fun onRegistering(imsTransportType: Int) {
                handleRegistering(mapTransport(imsTransportType))
            }

            override fun onTechnologyChangeFailed(imsTransportType: Int, info: ImsReasonInfo) {
                val reason = formatReasonInfo(info)
                lastReason = reason
                DiagnosticLogger.w(TAG, "IMS subId=$subId techChangeFailed(${mapTransport(imsTransportType)}): $reason")
            }

            override fun onUnregistered(info: ImsReasonInfo) {
                val reason = formatReasonInfo(info)
                lastReason = reason
                lastState = ImsRegState.NOT_REGISTERED
                lastTransport = ImsTransport.UNKNOWN
                if (resetIssued) {
                    sawResetLifecycle = true
                    onProgress?.invoke(ImsRegState.NOT_REGISTERED, ImsTransport.UNKNOWN)
                }
                DiagnosticLogger.i(TAG, "IMS subId=$subId unregistered (postReset=$resetIssued): $reason")
            }

            private fun handleRegistering(transport: ImsTransport) {
                lastState = ImsRegState.REGISTERING
                lastTransport = transport
                if (resetIssued) {
                    sawResetLifecycle = true
                    onProgress?.invoke(ImsRegState.REGISTERING, transport)
                }
                DiagnosticLogger.i(TAG, "IMS subId=$subId registering via $transport (postReset=$resetIssued)")
            }

            private fun handleRegistered(transport: ImsTransport) {
                lastState = ImsRegState.REGISTERED
                lastTransport = transport
                if (resetIssued && sawResetLifecycle) {
                    DiagnosticLogger.i(TAG, "IMS subId=$subId registered via $transport")
                    onProgress?.invoke(ImsRegState.REGISTERED, transport)
                    completion.complete(Unit)
                } else {
                    DiagnosticLogger.i(TAG, "IMS subId=$subId sticky pre-reset registered via $transport")
                }
            }
        }

        val callbackRegistered = runCatching {
            mmTel.registerImsRegistrationCallback(directExecutor, callback)
            true
        }.onFailure { error ->
            DiagnosticLogger.w(TAG, "registerImsRegistrationCallback failed for subId=$subId", error)
        }.getOrDefault(false)

        try {
            if (!callbackRegistered) {
                triggerRestart()
                return pollUntilRegistered(telephony, subId, onProgress)
            }

            // Let initial sticky RegistrationCallback delivery finish before arming post-reset tracking.
            delay(STICKY_CALLBACK_SETTLE_MS)
            resetIssued = true
            DiagnosticLogger.i(TAG, "Calling ITelephony.resetIms(slotIndex=$slotIndex) for subId=$subId")
            triggerRestart()

            val completedInTime = withTimeoutOrNull(REREGISTER_TIMEOUT_MS) {
                completion.await()
            } != null

            if (completedInTime) {
                var confirmedState = lastState
                var confirmedTransport = lastTransport
                repeat(4) {
                    val (polledState, polledTransport) = readImsStateAndTransport(mmTel, telephony, subId)
                    if (polledState == ImsRegState.REGISTERED) {
                        confirmedState = polledState
                        confirmedTransport = polledTransport
                        return@repeat
                    }
                    delay(200L)
                }
                return ReregistrationOutcome(
                    attemptedRestart = true,
                    registered = true,
                    timedOut = false,
                    lastState = confirmedState,
                    lastTransport = confirmedTransport,
                    lastReason = lastReason,
                )
            }

            val (finalState, finalTransport) = readImsStateAndTransport(mmTel, telephony, subId)
            val registeredAfterWait = finalState == ImsRegState.REGISTERED && sawResetLifecycle
            if (!registeredAfterWait) {
                DiagnosticLogger.w(
                    TAG,
                    "IMS reregistration timed out after ${REREGISTER_TIMEOUT_MS}ms " +
                        "for subId=$subId (state=$finalState/$finalTransport, sawLifecycle=$sawResetLifecycle, reason=$lastReason)"
                )
            }
            return ReregistrationOutcome(
                attemptedRestart = true,
                registered = registeredAfterWait,
                timedOut = !registeredAfterWait,
                lastState = finalState,
                lastTransport = finalTransport,
                lastReason = lastReason,
            )
        } finally {
            if (callbackRegistered) {
                runCatching { mmTel.unregisterImsRegistrationCallback(callback) }
            }
        }
    }

    private suspend fun pollUntilRegistered(
        telephony: Any,
        subId: Int,
        onProgress: ((ImsRegState, ImsTransport) -> Unit)?,
    ): ReregistrationOutcome {
        delay(POLL_INTERVAL_MS)
        val steps = (REREGISTER_TIMEOUT_MS / POLL_INTERVAL_MS).toInt()
        repeat(steps) {
            val isReg = runCatching { HiddenSystemApi.isImsRegistered(telephony, subId) }.getOrNull() == true
            if (isReg) {
                onProgress?.invoke(ImsRegState.REGISTERED, ImsTransport.UNKNOWN)
                return ReregistrationOutcome(
                    attemptedRestart = true,
                    registered = true,
                    timedOut = false,
                    lastState = ImsRegState.REGISTERED,
                )
            }
            delay(POLL_INTERVAL_MS)
        }
        DiagnosticLogger.w(TAG, "ITelephony.isImsRegistered polling timed out for subId=$subId")
        return ReregistrationOutcome(
            attemptedRestart = true,
            registered = false,
            timedOut = true,
            lastState = ImsRegState.NOT_REGISTERED,
        )
    }

    private fun syncMmTelSettings(mmTel: ImsMmTelManager, policy: SimImsPolicy) {
        val targetMode = if (policy.enableVoWiFi) policy.wfcMode.mode else WfcMode.CELLULAR_PREFERRED.mode
        invokeMmTelSetter(mmTel, "setAdvancedCallingSettingEnabled", Boolean::class.javaPrimitiveType, policy.enableVoLte)
        invokeMmTelSetter(mmTel, "setVoWiFiModeSetting", Int::class.javaPrimitiveType, targetMode)
        invokeMmTelSetter(mmTel, "setVoWiFiRoamingModeSetting", Int::class.javaPrimitiveType, targetMode)
        invokeMmTelSetter(mmTel, "setVoWiFiSettingEnabled", Boolean::class.javaPrimitiveType, policy.enableVoWiFi)
        invokeMmTelSetter(mmTel, "setCrossSimCallingEnabled", Boolean::class.javaPrimitiveType, policy.enableCrossSim)
    }

    private fun invokeMmTelSetter(
        mmTel: ImsMmTelManager,
        methodName: String,
        paramType: Class<*>?,
        arg: Any,
    ) {
        if (paramType == null) return
        runCatching {
            mmTel.javaClass.getMethod(methodName, paramType).invoke(mmTel, arg)
        }.onFailure { error ->
            val root = (error as? InvocationTargetException)?.targetException ?: error
            DiagnosticLogger.w(TAG, "$methodName skipped: ${root.javaClass.simpleName}: ${root.message}")
        }
    }

    private fun preferPersistent(context: Context): Boolean {
        val flags = context.applicationInfo.flags
        return (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }

    private fun invokeOverrideConfig(
        cm: CarrierConfigManager,
        subId: Int,
        values: PersistableBundle?,
        persistent: Boolean,
    ) {
        val method3 = runCatching {
            cm.javaClass.getMethod(
                "overrideConfig",
                Int::class.javaPrimitiveType,
                PersistableBundle::class.java,
                Boolean::class.javaPrimitiveType
            )
        }.getOrNull()
        if (method3 != null) {
            try {
                method3.invoke(cm, subId, values, persistent)
                return
            } catch (e: Exception) {
                if (!persistent) throw e
                DiagnosticLogger.w(TAG, "persistent=true rejected, fallback to persistent=false", e)
                method3.invoke(cm, subId, values, false)
                return
            }
        }
        cm.javaClass.getMethod(
            "overrideConfig",
            Int::class.javaPrimitiveType,
            PersistableBundle::class.java
        ).invoke(cm, subId, values)
    }

    @SuppressLint("MissingPermission")
    private fun readActiveSubscriptions(context: Context): List<SubscriptionInfo> {
        val byISub = runCatching {
            val isub = HiddenSystemApi.getSubService() ?: error("ISub binder unavailable")
            HiddenSystemApi.getActiveSubscriptionInfoList(isub)
        }.onFailure {
            DiagnosticLogger.w(TAG, "ISub.getActiveSubscriptionInfoList failed", it)
        }.getOrNull()
        if (!byISub.isNullOrEmpty()) return byISub

        val sm = context.getSystemService(SubscriptionManager::class.java)
        val fallback = sm?.activeSubscriptionInfoList.orEmpty()
        if (fallback.isEmpty()) {
            DiagnosticLogger.w(TAG, "readActiveSubscriptions returned empty list")
        }
        return fallback
    }

    private fun resolveSlotIndex(subId: Int, fallbackSlot: Int): Int {
        return runCatching {
            val isub = HiddenSystemApi.getSubService() ?: return fallbackSlot
            HiddenSystemApi.getSlotIndex(isub, subId)
        }.getOrDefault(fallbackSlot)
    }

    private suspend fun readImsStateAndTransport(
        mmTel: ImsMmTelManager?,
        telephony: Any?,
        subId: Int,
    ): Pair<ImsRegState, ImsTransport> {
        val stateInt = if (mmTel != null) {
            runCatching {
                withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Int> { cont ->
                        mmTel.getRegistrationState(directExecutor) { state ->
                            if (cont.isActive) cont.resume(state)
                        }
                    }
                }
            }.onFailure {
                DiagnosticLogger.w(TAG, "mmTel.getRegistrationState failed for subId=$subId", it)
            }.getOrNull()
        } else {
            null
        }

        val regState = when (stateInt) {
            RegistrationManager.REGISTRATION_STATE_REGISTERED -> ImsRegState.REGISTERED
            RegistrationManager.REGISTRATION_STATE_REGISTERING -> ImsRegState.REGISTERING
            RegistrationManager.REGISTRATION_STATE_NOT_REGISTERED -> ImsRegState.NOT_REGISTERED
            else -> {
                val registered = telephony?.let {
                    runCatching { HiddenSystemApi.isImsRegistered(it, subId) }.getOrNull()
                }
                when (registered) {
                    true -> ImsRegState.REGISTERED
                    false -> ImsRegState.NOT_REGISTERED
                    null -> ImsRegState.UNKNOWN
                }
            }
        }

        val transport = if (regState == ImsRegState.REGISTERED && mmTel != null) {
            val transportInt = runCatching {
                withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Int> { cont ->
                        mmTel.getRegistrationTransportType(directExecutor) { type ->
                            if (cont.isActive) cont.resume(type)
                        }
                    }
                }
            }.onFailure {
                DiagnosticLogger.w(TAG, "mmTel.getRegistrationTransportType failed for subId=$subId", it)
            }.getOrNull()
            mapTransport(transportInt)
        } else {
            ImsTransport.UNKNOWN
        }

        return regState to transport
    }

    private fun mapTransport(transportType: Int?): ImsTransport = when (transportType) {
        AccessNetworkConstants.TRANSPORT_TYPE_WWAN -> ImsTransport.CELLULAR
        AccessNetworkConstants.TRANSPORT_TYPE_WLAN -> ImsTransport.WLAN
        else -> ImsTransport.UNKNOWN
    }

    private fun formatReasonInfo(info: ImsReasonInfo): String {
        val extra = info.extraMessage?.takeIf { it.isNotBlank() }
        return if (extra != null) {
            "code=${info.code}, extra=${info.extraCode} ($extra)"
        } else {
            "code=${info.code}, extra=${info.extraCode}"
        }
    }
}
