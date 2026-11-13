package dev.ocfox.ims.privileged

import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import dev.ocfox.ims.model.SimImsPolicy
import dev.ocfox.ims.model.WfcMode

object CarrierConfigFactory {
    const val KEY_CONFIG_VERSION = "ocfox_ims_config_version"

    private const val KEY_SHOW_WFC_ICON_IN_STATUS_BAR = "show_wifi_calling_icon_in_status_bar_bool"
    private const val KEY_WFC_SPN_FORMAT_IDX = "wfc_spn_format_idx_int"
    private const val KEY_HIDE_LTE_PLUS_DATA_ICON = "hide_lte_plus_data_icon_bool"
    private const val KEY_NR_ADVANCED_THRESHOLD_BANDWIDTH_KHZ = "nr_advanced_threshold_bandwidth_khz_int"
    private const val KEY_INCLUDE_LTE_FOR_NR_ADVANCED = "include_lte_for_nr_advanced_threshold_bandwidth_bool"
    private const val KEY_ADDITIONAL_NR_ADVANCED_BANDS = "additional_nr_advanced_bands_int_array"
    private const val KEY_5G_ICON_CONFIGURATION = "5g_icon_configuration_string"
    private const val KEY_NR_ADVANCED_CAPABLE_PCO_ID = "nr_advanced_capable_pco_id_int"

    private const val NR_ADVANCED_THRESHOLD_KHZ = 110_000
    private const val NR_ICON_CONFIGURATION_5GA =
        "connected_mmwave:5G_Plus,connected:5G,connected_rrc_idle:5G,not_restricted_rrc_idle:5G,not_restricted_rrc_con:5G"
    private val NR_ADVANCED_BANDS = intArrayOf(1, 3, 8, 28, 41, 78, 79)
    private val FIVE_G_THRESHOLDS = intArrayOf(-128, -118, -108, -98)

    val READ_KEYS: Array<String> = arrayOf(
        KEY_CONFIG_VERSION,
        CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL,
        CarrierConfigManager.KEY_VONR_ENABLED_BOOL,
        CarrierConfigManager.KEY_VONR_SETTING_VISIBILITY_BOOL,
        CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL,
        CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT,
        CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL,
        CarrierConfigManager.KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL,
        CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL,
        CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL,
        CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY,
        CarrierConfigManager.KEY_5G_NR_SSRSRP_THRESHOLDS_INT_ARRAY,
        KEY_NR_ADVANCED_THRESHOLD_BANDWIDTH_KHZ,
        KEY_5G_ICON_CONFIGURATION,
    )

    fun toPersistableBundle(policy: SimImsPolicy, configVersion: Int): PersistableBundle {
        return PersistableBundle().apply {
            putInt(KEY_CONFIG_VERSION, configVersion)
            putBoolean(CarrierConfigManager.KEY_SHOW_IMS_REGISTRATION_STATUS_BOOL, true)

            // VoLTE
            putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, policy.enableVoLte)
            putBoolean(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL, policy.enableVoLte)
            putBoolean(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL, !policy.enableVoLte)
            putBoolean(KEY_HIDE_LTE_PLUS_DATA_ICON, false)

            // VoNR
            putBoolean(CarrierConfigManager.KEY_VONR_ENABLED_BOOL, policy.enableVoNr)
            putBoolean(CarrierConfigManager.KEY_VONR_SETTING_VISIBILITY_BOOL, policy.enableVoNr)

            // VoWiFi + preferred mode guard
            putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, policy.enableVoWiFi)
            putBoolean(CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ENABLED_BOOL, policy.enableVoWiFi)
            val targetMode = if (policy.enableVoWiFi) policy.wfcMode.mode else WfcMode.CELLULAR_PREFERRED.mode
            putInt(CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT, targetMode)
            putInt(CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_MODE_INT, targetMode)
            putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL, policy.enableVoWiFi)
            putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL, policy.enableVoWiFi)
            if (policy.enableVoWiFi) {
                putBoolean(KEY_SHOW_WFC_ICON_IN_STATUS_BAR, true)
                putInt(KEY_WFC_SPN_FORMAT_IDX, 6)
            }

            // Cross-SIM, UT, VT
            putBoolean(CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL, policy.enableCrossSim)
            putBoolean(
                CarrierConfigManager.KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL,
                policy.enableCrossSim
            )
            putBoolean(CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL, policy.enableUt)
            putBoolean(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL, policy.enableVt)

            // 5G NR
            if (policy.enable5gNr) {
                putIntArray(
                    CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY,
                    intArrayOf(
                        CarrierConfigManager.CARRIER_NR_AVAILABILITY_NSA,
                        CarrierConfigManager.CARRIER_NR_AVAILABILITY_SA,
                    )
                )
            } else {
                putIntArray(CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY, intArrayOf())
            }

            if (policy.enable5gThresholds) {
                putIntArray(CarrierConfigManager.KEY_5G_NR_SSRSRP_THRESHOLDS_INT_ARRAY, FIVE_G_THRESHOLDS)
            }

            if (policy.enable5gPlusIcon) {
                putInt(KEY_NR_ADVANCED_THRESHOLD_BANDWIDTH_KHZ, NR_ADVANCED_THRESHOLD_KHZ)
                putBoolean(KEY_INCLUDE_LTE_FOR_NR_ADVANCED, false)
                putIntArray(KEY_ADDITIONAL_NR_ADVANCED_BANDS, NR_ADVANCED_BANDS)
                putString(KEY_5G_ICON_CONFIGURATION, NR_ICON_CONFIGURATION_5GA)
                putInt(KEY_NR_ADVANCED_CAPABLE_PCO_ID, 0)
            }
        }
    }

    fun readPolicyFromBundle(bundle: PersistableBundle): SimImsPolicy {
        val nrArr = bundle.getIntArray(CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY)
        val hasNr = nrArr?.any {
            it == CarrierConfigManager.CARRIER_NR_AVAILABILITY_NSA ||
                it == CarrierConfigManager.CARRIER_NR_AVAILABILITY_SA
        } == true

        val thresholds = bundle.getIntArray(CarrierConfigManager.KEY_5G_NR_SSRSRP_THRESHOLDS_INT_ARRAY)
        val wfcMode = WfcMode.fromMode(
            bundle.getInt(
                CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT,
                WfcMode.CELLULAR_PREFERRED.mode
            )
        )

        return SimImsPolicy(
            enableVoLte = bundle.getBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, false),
            enableVoNr = bundle.getBoolean(CarrierConfigManager.KEY_VONR_ENABLED_BOOL, false) &&
                bundle.getBoolean(CarrierConfigManager.KEY_VONR_SETTING_VISIBILITY_BOOL, false),
            enableVoWiFi = bundle.getBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, false),
            wfcMode = wfcMode,
            enableCrossSim = bundle.getBoolean(
                CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL,
                false
            ) && bundle.getBoolean(
                CarrierConfigManager.KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL,
                false
            ),
            enableUt = bundle.getBoolean(CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL, false),
            enableVt = bundle.getBoolean(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL, false),
            enable5gNr = hasNr,
            enable5gThresholds = thresholds?.contentEquals(FIVE_G_THRESHOLDS) == true,
            enable5gPlusIcon = bundle.getInt(KEY_NR_ADVANCED_THRESHOLD_BANDWIDTH_KHZ, 0) == NR_ADVANCED_THRESHOLD_KHZ &&
                !bundle.getString(KEY_5G_ICON_CONFIGURATION).isNullOrBlank(),
        )
    }
}
