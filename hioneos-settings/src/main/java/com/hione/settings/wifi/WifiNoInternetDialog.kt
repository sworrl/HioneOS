package com.hione.settings.wifi

import android.app.Activity
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import com.hione.settings.HioneMuted
import com.hione.settings.HioneTealBright
import com.hione.settings.dialogs.HioneDialogActivity
import com.hione.settings.pages.WifiOps
import com.hione.settings.sys.Hidden

/**
 * "This network has no internet access" prompt.
 *
 * The class name is not a style choice. ConnectivityService builds this intent as
 * <settings package> + ".wifi.WifiNoInternetDialog", where the settings package is whatever
 * resolves android.settings.SETTINGS (ConnectivityService.getSettingsPackageName in this device's
 * service-connectivity.jar). Once HioneSettings owns that action the system looks for exactly
 * com.hione.settings.wifi.WifiNoInternetDialog, so it has to live here under this name.
 * Guarded by NETWORK_STACK like the stock activity.
 */
class WifiNoInternetDialog : HioneDialogActivity() {
    private val network: Network? by lazy {
        @Suppress("DEPRECATION") intent?.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK)
    }
    private val lost by lazy { intent?.action == "android.net.action.PROMPT_LOST_VALIDATION" }
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (network == null) done(Activity.RESULT_CANCELED)
    }

    @Composable override fun Content() {
        val net = network ?: return
        @Suppress("DEPRECATION")
        val ssid = remember { WifiOps.unquote(WifiOps.wm(this)?.connectionInfo?.ssid).takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: "This Wi-Fi network" }
        var always by remember { mutableStateOf(false) }
        val extra: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(always, { always = it }, colors = CheckboxDefaults.colors(checkedColor = HioneTealBright))
                Text(if (lost) "Always switch when Wi-Fi has no internet" else "Don't ask again for this network", color = HioneMuted, fontSize = 12.sp)
            }
        }
        if (!lost) {
            // ConnectivityManager.setAcceptUnvalidated is @hide (NETWORK_STACK / NETWORK_SETTINGS).
            Ask("$ssid has no internet access", "Stay connected anyway?", allow = "Yes", deny = "No", extra = extra,
                onAllow = { Hidden.call(cm, "setAcceptUnvalidated", net, true, always); done(Activity.RESULT_OK) },
                onDeny = { Hidden.call(cm, "setAcceptUnvalidated", net, false, always); done(Activity.RESULT_CANCELED) })
        } else {
            Ask("$ssid lost internet access", "Switch to mobile data? Data charges may apply.", allow = "Switch", deny = "Stay on Wi-Fi", extra = extra,
                onAllow = {
                    Hidden.call(cm, "setAvoidUnvalidated", net)
                    if (always) runCatching { Settings.Global.putString(contentResolver, "network_avoid_bad_wifi", "1") }
                    done(Activity.RESULT_OK)
                },
                onDeny = { done(Activity.RESULT_CANCELED) })
        }
    }
}
