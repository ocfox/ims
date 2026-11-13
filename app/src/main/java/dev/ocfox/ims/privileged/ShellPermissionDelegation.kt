package dev.ocfox.ims.privileged

import java.lang.reflect.InvocationTargetException

internal fun stopShellPermissionDelegation(
    activityManager: Any,
    activityManagerClass: Class<*>,
    uid: Int,
) {
    // Android 17 changed stopDelegateShellPermissionIdentity() to accept the target UID.
    val stopMethod = try {
        activityManagerClass.getMethod("stopDelegateShellPermissionIdentity", Int::class.javaPrimitiveType)
    } catch (_: NoSuchMethodException) {
        activityManagerClass.getMethod("stopDelegateShellPermissionIdentity")
    }
    try {
        if (stopMethod.parameterCount == 0) {
            stopMethod.invoke(activityManager)
        } else {
            stopMethod.invoke(activityManager, uid)
        }
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
}
