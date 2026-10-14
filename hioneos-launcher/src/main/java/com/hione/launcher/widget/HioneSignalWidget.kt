package com.hione.launcher.widget

import com.hione.launcher.Mascot
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import android.widget.RemoteViews
import com.hione.launcher.R

/**
 * Wi-Fi + cellular signal as an individual system App Widget. Self-contained live read on render
 * (ConnectivityManager/WifiManager for Wi-Fi, TelephonyManager for the radio) so it needs no
 * running service. Matches the in-bar badge's fixed semantics: the red "!NO DATA" state only
 * appears when Wi-Fi is NOT connected — on Wi-Fi the cellular bars stay neutral.
 */
class HioneSignalWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    companion object {
        private val TEAL get() = Mascot.argb(0xFFFFAB2E.toInt())
        private val TEAL_LIGHT get() = Mascot.argb(0xFFFFE0B0.toInt())
        private const val RED = 0xFFFF5252.toInt()
        private const val DIM = 0x80FFFFFF.toInt()

        fun pushUpdate(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, HioneSignalWidget::class.java))
            ids.forEach { render(app, mgr, it) }
        }

        private fun bars(level: Int, max: Int) =
            buildString { repeat(max) { append(if (it < level) '▮' else '▯') } }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_hione_signal)

            // ---- Wi-Fi pod --------------------------------------------------------------
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val wifiConnected = try {
                cm?.getNetworkCapabilities(cm.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } catch (_: Throwable) { false }
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiEnabled = try { wm?.isWifiEnabled == true } catch (_: Throwable) { false }
            @Suppress("DEPRECATION")
            val rssi = try { if (wifiConnected) wm?.connectionInfo?.rssi ?: -100 else -100 } catch (_: Throwable) { -100 }
            @Suppress("DEPRECATION")
            val wifiLevel = if (wifiConnected) WifiManager.calculateSignalLevel(rssi, 5).coerceIn(0, 4) else 0

            when {
                wifiConnected -> {
                    v.setTextViewText(R.id.widget_signal_wifi, "WIFI ${bars(wifiLevel, 4)}")
                    v.setTextColor(R.id.widget_signal_wifi, TEAL)
                }
                wifiEnabled -> {
                    v.setTextViewText(R.id.widget_signal_wifi, "WIFI ${bars(0, 4)}")
                    v.setTextColor(R.id.widget_signal_wifi, DIM)
                }
                else -> {
                    v.setTextViewText(R.id.widget_signal_wifi, "WIFI OFF")
                    v.setTextColor(R.id.widget_signal_wifi, DIM)
                }
            }

            // ---- Cellular pod (READ_PHONE_STATE is granted to the launcher) --------------
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val simReady = try { tm?.simState == TelephonyManager.SIM_STATE_READY } catch (_: Throwable) { false }
            val cellLevel = try { tm?.signalStrength?.level?.coerceIn(0, 4) ?: 0 } catch (_: Throwable) { 0 }
            @Suppress("DEPRECATION")
            val dataConnected = try { tm?.dataState == TelephonyManager.DATA_CONNECTED } catch (_: Throwable) { false }
            val netType = try {
                when (if (android.os.Build.VERSION.SDK_INT >= 30) tm?.dataNetworkType else @Suppress("DEPRECATION") tm?.networkType) {
                    TelephonyManager.NETWORK_TYPE_NR -> "5G"; TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                    TelephonyManager.NETWORK_TYPE_HSPAP -> "H+"
                    TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                    TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"; else -> "CELL"
                }
            } catch (_: Throwable) { "CELL" }
            // Genuine no-data only: on Wi-Fi the cell pod stays neutral instead of crying wolf.
            val cellNoData = simReady && cellLevel > 0 && !dataConnected && !wifiConnected

            when {
                !simReady -> {
                    v.setTextViewText(R.id.widget_signal_cell, "NO SIM")
                    v.setTextColor(R.id.widget_signal_cell, DIM)
                }
                cellNoData -> {
                    v.setTextViewText(R.id.widget_signal_cell, "NO DATA ${bars(cellLevel, 4)}")
                    v.setTextColor(R.id.widget_signal_cell, RED)
                }
                else -> {
                    v.setTextViewText(R.id.widget_signal_cell, "$netType ${bars(cellLevel, 4)}")
                    v.setTextColor(R.id.widget_signal_cell, if (wifiConnected) TEAL_LIGHT else TEAL)
                }
            }

            v.setOnClickPendingIntent(R.id.widget_signal_root, HioneBatteryWidget.launchLauncher(ctx))
            mgr.updateAppWidget(id, v)
        }
    }
}
