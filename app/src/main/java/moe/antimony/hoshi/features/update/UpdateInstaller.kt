package moe.antimony.hoshi.features.update

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.withErrorDetails

internal fun openDownloadedUpdate(context: Context, file: File): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
        return runCatching {
            context.startActivity(settingsIntent)
            context.getString(R.string.update_install_permission_required)
        }.getOrElse { error ->
            context.getString(R.string.update_install_permission_settings_failed).withErrorDetails(error)
        }
    }

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, AndroidUpdateDownloadManager.ApkMimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newUri(context.contentResolver, "Hoshi Reader update", uri)
    return try {
        context.startActivity(intent)
        null
    } catch (error: ActivityNotFoundException) {
        context.getString(R.string.update_installer_unavailable).withErrorDetails(error)
    } catch (error: SecurityException) {
        context.getString(R.string.update_installer_blocked).withErrorDetails(error)
    }
}
