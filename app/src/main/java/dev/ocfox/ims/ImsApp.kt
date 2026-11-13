package dev.ocfox.ims

import android.app.Application
import org.lsposed.hiddenapibypass.LSPass

class ImsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LSPass.setHiddenApiExemptions("")
    }
}
