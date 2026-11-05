package moe.antimony.hoshi.features.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme

/** Hosts the real language setting without loading or changing library/profile data. */
class AppLanguageFixtureActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AdvancedSettingsView(onClose = ::finish, onOpenSync = {})
            }
        }
    }
}
