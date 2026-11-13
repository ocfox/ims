package dev.ocfox.ims.model

import android.telephony.ims.ImsMmTelManager

enum class WfcMode(val mode: Int) {
    CELLULAR_PREFERRED(ImsMmTelManager.WIFI_MODE_CELLULAR_PREFERRED),
    WIFI_PREFERRED(ImsMmTelManager.WIFI_MODE_WIFI_PREFERRED);

    companion object {
        fun fromMode(mode: Int): WfcMode = when (mode) {
            ImsMmTelManager.WIFI_MODE_WIFI_PREFERRED -> WIFI_PREFERRED
            else -> CELLULAR_PREFERRED
        }
    }
}

enum class ImsRegState {
    REGISTERED,
    REGISTERING,
    NOT_REGISTERED,
    UNKNOWN,
}

enum class ImsTransport {
    CELLULAR,
    WLAN,
    UNKNOWN,
}

enum class ShizukuStatus {
    DISCONNECTED,
    NEED_PERMISSION,
    READY,
}

data class SimImsPolicy(
    val enableVoLte: Boolean = true,
    val enableVoNr: Boolean = true,
    val enableVoWiFi: Boolean = false,
    val wfcMode: WfcMode = WfcMode.CELLULAR_PREFERRED,
    val enableCrossSim: Boolean = true,
    val enableUt: Boolean = true,
    val enableVt: Boolean = false,
    val enable5gNr: Boolean = true,
    val enable5gThresholds: Boolean = true,
    val enable5gPlusIcon: Boolean = true,
) {
    companion object {
        fun smartDefaultFor(mcc: String?): SimImsPolicy {
            // Mainland China carriers (460) have no public ePDG; keeping VoWiFi off
            // prevents Android 17 QNS from locking IMS onto IWLAN under Wi-Fi.
            val isMainlandChina = mcc == "460"
            return SimImsPolicy(
                enableVoLte = true,
                enableVoNr = true,
                enableVoWiFi = !isMainlandChina,
                wfcMode = WfcMode.CELLULAR_PREFERRED,
                enableCrossSim = true,
                enableUt = true,
                enableVt = false,
                enable5gNr = true,
                enable5gThresholds = true,
                enable5gPlusIcon = true,
            )
        }
    }
}

data class SimStatus(
    val subId: Int,
    val slotIndex: Int,
    val displayName: String,
    val carrierName: String,
    val mcc: String,
    val mnc: String,
    val imsRegState: ImsRegState,
    val imsTransport: ImsTransport,
    val wfcSystemEnabled: Boolean?,
    val wfcSystemMode: Int?,
    val isOverrideActive: Boolean,
    val livePolicy: SimImsPolicy?,
) {
    val plmnLabel: String
        get() = listOf(mcc, mnc).filter { it.isNotBlank() }.joinToString("/")

    val hasChinaVoWiFiRisk: Boolean
        get() = mcc == "460" && (
            wfcSystemEnabled == true ||
                livePolicy?.enableVoWiFi == true ||
                wfcSystemMode == WfcMode.WIFI_PREFERRED.mode
            )

    val hasWifiPreferredRisk: Boolean
        get() = !hasChinaVoWiFiRisk &&
            wfcSystemEnabled == true &&
            wfcSystemMode == WfcMode.WIFI_PREFERRED.mode
}
