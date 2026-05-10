package moe.antimony.hoshi

import android.app.Application
import moe.antimony.hoshi.features.diagnostics.installCrashDiagnostics

class HoshiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashDiagnostics(this)
    }
}
