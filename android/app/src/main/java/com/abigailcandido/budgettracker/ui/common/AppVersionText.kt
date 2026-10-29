package com.abigailcandido.budgettracker.ui.common

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import com.abigailcandido.budgettracker.BuildConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Which build of the app is on this phone, in small print at the foot of the
 * sign-in and PIN screens:
 *
 *     Version 0.3.9 (309) · debug · laptop server
 *     Installed 29 Oct 2026, 6:42 PM
 *
 * The version comes from build.gradle.kts and goes up every round. The time
 * is when this copy was installed on the phone, read from the phone itself:
 * a time written into the app while it is built would stay frozen, because
 * Gradle's configuration cache reuses the setup from the build before.
 */
@Composable
fun AppVersionText(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val installedAt = remember(context) { installedAt(context) }
    val server = if (BuildConfig.API_BASE_URL.contains("onrender.com")) {
        "live server"
    } else {
        "laptop server"
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
                    "${BuildConfig.BUILD_TYPE} · $server",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (installedAt != null) {
            Text(
                text = "Installed $installedAt",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private val INSTALLED_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.getDefault())

/** When this copy of the app was installed or last updated, or null if the phone will not say. */
private fun installedAt(context: Context): String? = try {
    val info: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0L),
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    Instant.ofEpochMilli(info.lastUpdateTime)
        .atZone(ZoneId.systemDefault())
        .format(INSTALLED_FORMAT)
} catch (e: PackageManager.NameNotFoundException) {
    null
}