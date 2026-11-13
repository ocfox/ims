package dev.ocfox.ims

import dev.ocfox.ims.data.DiagnosticLogger
import dev.ocfox.ims.model.ImsRegState
import dev.ocfox.ims.model.ImsTransport
import dev.ocfox.ims.model.SimImsPolicy
import dev.ocfox.ims.model.SimStatus
import dev.ocfox.ims.model.WfcMode
import dev.ocfox.ims.privileged.stopShellPermissionDelegation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolicyAndDelegationTest {
    @Test
    fun mainlandChinaSmartDefaultDisablesVoWiFi() {
        val policy = SimImsPolicy.smartDefaultFor("460")
        assertFalse(policy.enableVoWiFi)
        assertEquals(WfcMode.CELLULAR_PREFERRED, policy.wfcMode)
        assertTrue(policy.enableVoLte)
        assertTrue(policy.enableVoNr)
        assertTrue(policy.enable5gNr)
        assertTrue(policy.enableCrossSim)
        assertTrue(policy.enableUt)
    }

    @Test
    fun overseasSmartDefaultEnablesVoWiFiWithCellularPreferred() {
        val policy = SimImsPolicy.smartDefaultFor("454")
        assertTrue(policy.enableVoWiFi)
        assertEquals(WfcMode.CELLULAR_PREFERRED, policy.wfcMode)
    }

    @Test
    fun riskDetectionFlagsChinaVoWiFiAndWifiPreferred() {
        val cnSimWithSystemWfc = SimStatus(
            subId = 2,
            slotIndex = 0,
            displayName = "China Telecom",
            carrierName = "China Telecom",
            mcc = "460",
            mnc = "11",
            imsRegState = ImsRegState.NOT_REGISTERED,
            imsTransport = ImsTransport.UNKNOWN,
            wfcSystemEnabled = true,
            wfcSystemMode = WfcMode.WIFI_PREFERRED.mode,
            isOverrideActive = false,
            livePolicy = null,
        )
        assertTrue(cnSimWithSystemWfc.hasChinaVoWiFiRisk)
        assertFalse(cnSimWithSystemWfc.hasWifiPreferredRisk)

        val hkWifiPreferred = cnSimWithSystemWfc.copy(
            mcc = "454",
            mnc = "03",
            imsRegState = ImsRegState.REGISTERED,
            imsTransport = ImsTransport.WLAN,
        )
        assertFalse(hkWifiPreferred.hasChinaVoWiFiRisk)
        assertTrue(hkWifiPreferred.hasWifiPreferredRisk)
    }

    interface Android17Am {
        fun stopDelegateShellPermissionIdentity(uid: Int)
        fun stopDelegateShellPermissionIdentity()
    }

    interface LegacyAm {
        fun stopDelegateShellPermissionIdentity()
    }

    @Test
    fun shellDelegationPrefersUidOverloadOnAndroid17() {
        var calledUid: Int? = null
        var calledLegacy = false
        val am = object : Android17Am {
            override fun stopDelegateShellPermissionIdentity(uid: Int) {
                calledUid = uid
            }

            override fun stopDelegateShellPermissionIdentity() {
                calledLegacy = true
            }
        }
        stopShellPermissionDelegation(am, Android17Am::class.java, 10245)
        assertEquals(10245, calledUid)
        assertFalse(calledLegacy)
    }

    @Test
    fun shellDelegationFallsBackToLegacyZeroArgMethod() {
        var calledLegacy = false
        val am = object : LegacyAm {
            override fun stopDelegateShellPermissionIdentity() {
                calledLegacy = true
            }
        }
        stopShellPermissionDelegation(am, LegacyAm::class.java, 10245)
        assertTrue(calledLegacy)
    }

    @Test
    fun shellDelegationPropagatesTargetExceptionWithoutRetryingLegacy() {
        var calledLegacy = false
        val am = object : Android17Am {
            override fun stopDelegateShellPermissionIdentity(uid: Int) {
                throw SecurityException("boom")
            }

            override fun stopDelegateShellPermissionIdentity() {
                calledLegacy = true
            }
        }
        assertFailsWith<SecurityException> {
            stopShellPermissionDelegation(am, Android17Am::class.java, 10245)
        }
        assertFalse(calledLegacy)
    }

    @Test
    fun diagnosticLoggerRecordsBoundsClearAndBuildsReport() {
        DiagnosticLogger.clear()
        assertTrue(DiagnosticLogger.logs.value.isEmpty())

        DiagnosticLogger.i("TelephonyBridge", "Loaded 1 SIM(s)")
        DiagnosticLogger.w("TelephonyBridge", "ImsCallback warn", IllegalStateException("timeout"))
        DiagnosticLogger.e("ImsViewModel", "Reset failed", SecurityException("denied"))

        val snapshot = DiagnosticLogger.logs.value
        assertEquals(3, snapshot.size)
        assertTrue(snapshot[0].contains("[I/TelephonyBridge] Loaded 1 SIM(s)"))
        assertTrue(snapshot[1].contains("[W/TelephonyBridge] ImsCallback warn (IllegalStateException: timeout)"))
        assertTrue(snapshot[2].contains("[E/ImsViewModel] Reset failed"))

        val report = DiagnosticLogger.buildReport(
            shizukuStatus = "READY",
            sims = listOf(
                SimStatus(
                    subId = 2,
                    slotIndex = 0,
                    displayName = "China Telecom",
                    carrierName = "China Telecom",
                    mcc = "460",
                    mnc = "11",
                    imsRegState = ImsRegState.NOT_REGISTERED,
                    imsTransport = ImsTransport.UNKNOWN,
                    wfcSystemEnabled = false,
                    wfcSystemMode = WfcMode.CELLULAR_PREFERRED.mode,
                    isOverrideActive = true,
                    livePolicy = null,
                )
            ),
            extraSummary = "10s reregistration timeout",
        )
        assertTrue(report.contains("Shizuku: READY"))
        assertTrue(report.contains("Note: 10s reregistration timeout"))
        assertTrue(report.contains("slot=0 subId=2 carrier=China Telecom plmn=460/11"))
        assertTrue(report.contains("--- Timeline ---"))

        DiagnosticLogger.clear()
        assertTrue(DiagnosticLogger.logs.value.isEmpty())
    }
}
