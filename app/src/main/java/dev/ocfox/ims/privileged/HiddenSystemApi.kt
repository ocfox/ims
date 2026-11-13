package dev.ocfox.ims.privileged

import android.content.ComponentName
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.telephony.SubscriptionInfo
import rikka.shizuku.ShizukuBinderWrapper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Pure-Kotlin reflection bridge over Android's bootclasspath hidden APIs,
 * replacing the separate :stub Java/AIDL module.
 */
internal object HiddenSystemApi {
    private const val WATCHER_DESCRIPTOR = "android.app.IInstrumentationWatcher"
    private val activityManagerInterface by lazy { Class.forName("android.app.IActivityManager") }
    private val activityManagerStub by lazy { Class.forName("android.app.IActivityManager\$Stub") }
    private val watcherInterface by lazy { Class.forName(WATCHER_DESCRIPTOR) }
    private val uiAutomationConnectionInterface by lazy { Class.forName("android.app.IUiAutomationConnection") }
    private val uiAutomationConnectionClass by lazy { Class.forName("android.app.UiAutomationConnection") }
    private val subInterface by lazy { Class.forName("com.android.internal.telephony.ISub") }
    private val subStub by lazy { Class.forName("com.android.internal.telephony.ISub\$Stub") }
    private val telephonyInterface by lazy { Class.forName("com.android.internal.telephony.ITelephony") }
    private val telephonyStub by lazy { Class.forName("com.android.internal.telephony.ITelephony\$Stub") }

    fun getService(name: String): IBinder? {
        val sm = Class.forName("android.os.ServiceManager")
        return invoke(sm.getMethod("getService", String::class.java), null, name) as? IBinder
    }

    fun getActivityManager(): Any {
        val binder = getService(Context.ACTIVITY_SERVICE) ?: error("Activity service unavailable")
        return asInterface(activityManagerStub, ShizukuBinderWrapper(binder))
    }

    fun startDelegateShellPermissionIdentity(am: Any, uid: Int) {
        val method = activityManagerInterface.getMethod(
            "startDelegateShellPermissionIdentity",
            Int::class.javaPrimitiveType,
            Array<String>::class.java,
        )
        invoke(method, am, uid, null)
    }

    fun stopDelegateShellPermissionIdentity(am: Any, uid: Int) {
        stopShellPermissionDelegation(am, activityManagerInterface, uid)
    }

    fun newUiAutomationConnection(): Any {
        return try {
            uiAutomationConnectionClass.getDeclaredConstructor().newInstance()
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    fun createInstrumentationWatcher(onFinished: () -> Unit): Any {
        val stubClass = runCatching { Class.forName("$WATCHER_DESCRIPTOR\$Stub") }.getOrNull()
        val statusTx = readTxCode(stubClass, "TRANSACTION_instrumentationStatus", IBinder.FIRST_CALL_TRANSACTION)
        val finishTx = readTxCode(stubClass, "TRANSACTION_instrumentationFinished", IBinder.FIRST_CALL_TRANSACTION + 1)

        lateinit var proxy: Any
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                return when (code) {
                    INTERFACE_TRANSACTION -> {
                        reply?.writeString(WATCHER_DESCRIPTOR)
                        true
                    }
                    statusTx -> {
                        data.enforceInterface(WATCHER_DESCRIPTOR)
                        reply?.writeNoException()
                        true
                    }
                    finishTx -> {
                        data.enforceInterface(WATCHER_DESCRIPTOR)
                        onFinished()
                        reply?.writeNoException()
                        true
                    }
                    else -> super.onTransact(code, data, reply, flags)
                }
            }
        }
        proxy = Proxy.newProxyInstance(
            watcherInterface.classLoader,
            arrayOf(watcherInterface),
        ) { _, method, _ ->
            when (method.name) {
                "asBinder" -> binder
                else -> null
            }
        }
        binder.attachInterface(proxy as IInterface, WATCHER_DESCRIPTOR)
        return proxy
    }

    fun startInstrumentation(
        am: Any,
        component: ComponentName,
        flags: Int,
        args: Bundle,
        watcher: Any?,
        connection: Any?,
    ): Boolean {
        val method = activityManagerInterface.getMethod(
            "startInstrumentation",
            ComponentName::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Bundle::class.java,
            watcherInterface,
            uiAutomationConnectionInterface,
            Int::class.javaPrimitiveType,
            String::class.java,
        )
        return invoke(method, am, component, null, flags, args, watcher, connection, 0, null) as Boolean
    }

    fun getSubService(): Any? {
        val binder = resolveTelephonyBinder("getSubscriptionServiceRegisterer", "isub") ?: return null
        return asInterface(subStub, ShizukuBinderWrapper(binder))
    }

    @Suppress("UNCHECKED_CAST")
    fun getActiveSubscriptionInfoList(isub: Any): List<SubscriptionInfo> {
        val method = subInterface.getMethod(
            "getActiveSubscriptionInfoList",
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        )
        return invoke(method, isub, null, null, true) as? List<SubscriptionInfo> ?: emptyList()
    }

    fun getSlotIndex(isub: Any, subId: Int): Int {
        val method = subInterface.getMethod("getSlotIndex", Int::class.javaPrimitiveType)
        return invoke(method, isub, subId) as Int
    }

    fun getTelephonyService(): Any? {
        val binder = resolveTelephonyBinder("getTelephonyServiceRegisterer", Context.TELEPHONY_SERVICE) ?: return null
        return asInterface(telephonyStub, ShizukuBinderWrapper(binder))
    }

    fun getImsProvisioningInt(telephony: Any, subId: Int, key: Int): Int {
        val method = telephonyInterface.getMethod(
            "getImsProvisioningInt",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        return invoke(method, telephony, subId, key) as Int
    }

    fun setImsProvisioningInt(telephony: Any, subId: Int, key: Int, value: Int): Int {
        val method = telephonyInterface.getMethod(
            "setImsProvisioningInt",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        return invoke(method, telephony, subId, key, value) as Int
    }

    fun resetIms(telephony: Any, slotIndex: Int) {
        val method = telephonyInterface.getMethod("resetIms", Int::class.javaPrimitiveType)
        invoke(method, telephony, slotIndex)
    }

    fun isImsRegistered(telephony: Any, subId: Int): Boolean {
        val method = telephonyInterface.getMethod("isImsRegistered", Int::class.javaPrimitiveType)
        return invoke(method, telephony, subId) as Boolean
    }

    private fun resolveTelephonyBinder(registererMethod: String, fallbackService: String): IBinder? {
        val byInitializer = runCatching {
            val initClass = Class.forName("android.telephony.TelephonyFrameworkInitializer")
            val manager = invoke(initClass.getMethod("getTelephonyServiceManager"), null)
                ?: return@runCatching null
            val registerer = invoke(manager.javaClass.getMethod(registererMethod), manager)
                ?: return@runCatching null
            invoke(registerer.javaClass.getMethod("get"), registerer) as? IBinder
        }.getOrNull()
        return byInitializer ?: getService(fallbackService)
    }

    private fun asInterface(stubClass: Class<*>, binder: IBinder): Any {
        return invoke(stubClass.getMethod("asInterface", IBinder::class.java), null, binder)
            ?: error("asInterface returned null for ${stubClass.name}")
    }

    private fun readTxCode(stubClass: Class<*>?, fieldName: String, fallback: Int): Int {
        if (stubClass == null) return fallback
        return runCatching {
            stubClass.getDeclaredField(fieldName).apply { isAccessible = true }.getInt(null)
        }.getOrDefault(fallback)
    }

    private fun invoke(method: java.lang.reflect.Method, target: Any?, vararg args: Any?): Any? {
        return try {
            method.invoke(target, *args)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }
}
