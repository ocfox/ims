package dev.ocfox.ims.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.ocfox.ims.model.SimImsPolicy
import dev.ocfox.ims.model.WfcMode

class PolicyRepository(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun isAutoApplyEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_APPLY, true)

    fun setAutoApplyEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_APPLY, enabled) }
    }

    fun hasSavedPolicy(subId: Int): Boolean = prefs.getBoolean(key(subId, "saved"), false)

    fun getOrCreatePolicy(subId: Int, mcc: String?): SimImsPolicy {
        if (!hasSavedPolicy(subId)) {
            return SimImsPolicy.smartDefaultFor(mcc)
        }
        val defaults = SimImsPolicy.smartDefaultFor(mcc)
        return SimImsPolicy(
            enableVoLte = prefs.getBoolean(key(subId, "volte"), defaults.enableVoLte),
            enableVoNr = prefs.getBoolean(key(subId, "vonr"), defaults.enableVoNr),
            enableVoWiFi = prefs.getBoolean(key(subId, "vowifi"), defaults.enableVoWiFi),
            wfcMode = WfcMode.fromMode(prefs.getInt(key(subId, "wfc_mode"), defaults.wfcMode.mode)),
            enableCrossSim = prefs.getBoolean(key(subId, "cross_sim"), defaults.enableCrossSim),
            enableUt = prefs.getBoolean(key(subId, "ut"), defaults.enableUt),
            enableVt = prefs.getBoolean(key(subId, "vt"), defaults.enableVt),
            enable5gNr = prefs.getBoolean(key(subId, "5g_nr"), defaults.enable5gNr),
            enable5gThresholds = prefs.getBoolean(key(subId, "5g_thresh"), defaults.enable5gThresholds),
            enable5gPlusIcon = prefs.getBoolean(key(subId, "5g_plus"), defaults.enable5gPlusIcon),
        )
    }

    fun savePolicy(subId: Int, policy: SimImsPolicy) {
        prefs.edit {
            putBoolean(key(subId, "saved"), true)
            putBoolean(key(subId, "volte"), policy.enableVoLte)
            putBoolean(key(subId, "vonr"), policy.enableVoNr)
            putBoolean(key(subId, "vowifi"), policy.enableVoWiFi)
            putInt(key(subId, "wfc_mode"), policy.wfcMode.mode)
            putBoolean(key(subId, "cross_sim"), policy.enableCrossSim)
            putBoolean(key(subId, "ut"), policy.enableUt)
            putBoolean(key(subId, "vt"), policy.enableVt)
            putBoolean(key(subId, "5g_nr"), policy.enable5gNr)
            putBoolean(key(subId, "5g_thresh"), policy.enable5gThresholds)
            putBoolean(key(subId, "5g_plus"), policy.enable5gPlusIcon)
        }
    }

    private fun key(subId: Int, name: String): String = "sub_${subId}_$name"

    private companion object {
        const val PREFS_NAME = "ims_policies"
        const val KEY_AUTO_APPLY = "auto_apply_on_shizuku"
    }
}
