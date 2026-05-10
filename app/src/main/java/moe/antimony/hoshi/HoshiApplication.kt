package moe.antimony.hoshi

import android.app.Application
import moe.antimony.hoshi.features.diagnostics.installCrashDiagnostics
import moe.antimony.hoshi.features.update.UpdateScheduler

class HoshiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashDiagnostics(this)
        UpdateScheduler.sync(this)
    }
}
