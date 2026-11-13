package dev.ocfox.ims.privileged

import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.system.Os
import dev.ocfox.ims.data.PolicyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.LSPass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.util.concurrent.atomic.AtomicBoolean

class AutoApplyShizukuProvider : ShizukuProvider() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listenerRegistered = AtomicBoolean(false)

    override fun onCreate(): Boolean {
        LSPass.setHiddenApiExemptions("")
        return super.onCreate()
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val result = super.call(method, arg, extras)
        val isSystemUser = Os.getuid() / 100_000 == 0
        if (METHOD_SEND_BINDER == method && isSystemUser) {
            val callingUid = Binder.getCallingUid()
            if (callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID) {
                ensureAutoApplyListener()
            }
        }
        return result
    }

    private fun ensureAutoApplyListener() {
        val appContext = context?.applicationContext ?: return
        if (!listenerRegistered.compareAndSet(false, true)) return
        Shizuku.addBinderReceivedListenerSticky {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return@addBinderReceivedListenerSticky
            val repo = PolicyRepository(appContext)
            if (!repo.isAutoApplyEnabled()) return@addBinderReceivedListenerSticky
            scope.launch {
                TelephonyBridge.applyMissingPoliciesForAllSims(appContext, repo)
            }
        }
    }
}
