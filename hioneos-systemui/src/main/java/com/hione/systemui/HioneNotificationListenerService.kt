package com.hione.systemui

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One notification action. [remoteInputs] non-empty = it takes typed text (a reply). */
data class HioneNotifAction(
    val title: String,
    val intent: PendingIntent?,
    val remoteInputs: List<RemoteInput>
) {
    val isReply: Boolean get() = remoteInputs.any { it.allowFreeFormInput }
}

/** One line of a messaging-style notification (SMS, chat). */
data class HioneNotifMessage(val sender: String?, val text: String)

/**
 * One posted notification, flattened for the shade. Everything the Pixel-style row needs:
 * app identity, title/text (+ big text when expanded), actions, content intent, progress.
 */
data class HioneNotif(
    val key: String,
    val pkg: String,
    val appLabel: String,
    val appIcon: Drawable?,
    val title: String,
    val text: String,
    val bigText: String?,
    val subText: String?,
    val postTime: Long,
    val isOngoing: Boolean,
    val isClearable: Boolean,
    val autoCancel: Boolean,
    val contentIntent: PendingIntent?,
    val actions: List<HioneNotifAction>,
    val messages: List<HioneNotifMessage>,
    val conversationTitle: String?,
    val largeIcon: Bitmap?,
    val groupKey: String?,
    val isGroupSummary: Boolean,
    val accent: Int,
    val hasMediaSession: Boolean,
    val progress: Int?,
    val progressMax: Int?,
    val progressIndeterminate: Boolean
) {
    val replyAction: HioneNotifAction? get() = actions.firstOrNull { it.isReply }
}

/** Process-wide store the shade observes; fed by [HioneNotificationListenerService]. */
object HioneNotificationStore {
    private const val TAG = "HioneNotifs"
    private val _items = MutableStateFlow<List<HioneNotif>>(emptyList())
    val items: StateFlow<List<HioneNotif>> = _items
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    @Volatile internal var listener: HioneNotificationListenerService? = null
    @Volatile private var appCtx: Context? = null

    internal fun setConnected(ctx: Context, on: Boolean) {
        appCtx = ctx.applicationContext
        _connected.value = on
        if (!on) { _items.value = emptyList(); HioneNotificationTrayProvider.notifyChanged(ctx) }
    }

    internal fun refresh(ctx: Context, sbns: Array<StatusBarNotification>?) {
        val list = ArrayList<HioneNotif>()
        val pm = ctx.packageManager
        sbns?.forEach { sbn ->
            runCatching { flatten(ctx, pm, sbn) }
                .onFailure { Log.w(TAG, "flatten ${sbn.key}: $it") }
                .getOrNull()?.let { list.add(it) }
        }
        // Group handling: drop a summary when any child of the same group is present.
        val childGroups = list.filter { !it.isGroupSummary && it.groupKey != null }.map { it.groupKey }.toSet()
        val visible = list.filter { !(it.isGroupSummary && it.groupKey in childGroups) }
            .filter { it.pkg != ctx.packageName }
            .sortedWith(compareBy<HioneNotif> { it.isOngoing }.thenByDescending { it.postTime })
        _items.value = visible
        HioneNotificationTrayProvider.notifyChanged(ctx)
    }

    private fun flatten(ctx: Context, pm: android.content.pm.PackageManager, sbn: StatusBarNotification): HioneNotif? {
        val n = sbn.notification ?: return null
        val ex = n.extras
        val appInfo = runCatching { pm.getApplicationInfo(sbn.packageName, 0) }.getOrNull()
        val appLabel = appInfo?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() } ?: sbn.packageName
        val appIcon = appInfo?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() }
        val messages = messagesOf(ex)
        val convTitle = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?: convTitle ?: appLabel
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: messages.lastOrNull()?.text ?: ""
        val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n") { it.toString() }
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val large: Bitmap? = runCatching {
            val icon = n.getLargeIcon() ?: ex.getParcelable<Icon>(Notification.EXTRA_LARGE_ICON)
            icon?.loadDrawable(ctx)?.toBitmapSafe(96)
        }.getOrNull()
        val actions = n.actions?.map { a ->
            HioneNotifAction(
                title = a.title?.toString() ?: "",
                intent = a.actionIntent,
                remoteInputs = a.remoteInputs?.toList() ?: emptyList()
            )
        } ?: emptyList()
        val hasSession = ex.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            (n.category == Notification.CATEGORY_TRANSPORT)
        val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val prog = if (max > 0 || ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) ex.getInt(Notification.EXTRA_PROGRESS, 0) else null
        return HioneNotif(
            key = sbn.key, pkg = sbn.packageName, appLabel = appLabel, appIcon = appIcon,
            title = title, text = text, bigText = big?.takeIf { it.isNotBlank() && it != text }, subText = sub,
            postTime = sbn.postTime, isOngoing = sbn.isOngoing, isClearable = sbn.isClearable,
            autoCancel = (n.flags and Notification.FLAG_AUTO_CANCEL) != 0,
            contentIntent = n.contentIntent, actions = actions,
            messages = messages, conversationTitle = convTitle, largeIcon = large,
            groupKey = sbn.groupKey, isGroupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            accent = n.color, hasMediaSession = hasSession,
            progress = prog, progressMax = if (max > 0) max else null,
            progressIndeterminate = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        )
    }

    /** MessagingStyle history (SMS, chat): the last few lines with their senders. */
    private fun messagesOf(ex: Bundle): List<HioneNotifMessage> = runCatching {
        @Suppress("DEPRECATION")
        val arr: Array<Parcelable>? = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        arr?.mapNotNull { p ->
            val b = p as? Bundle ?: return@mapNotNull null
            val text = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
            @Suppress("DEPRECATION")
            val person = b.getParcelable<android.app.Person>("sender_person")
            val sender = person?.name?.toString() ?: b.getCharSequence("sender")?.toString()
            HioneNotifMessage(sender, text)
        }?.takeLast(6) ?: emptyList()
    }.getOrDefault(emptyList())

    fun find(key: String): HioneNotif? = _items.value.firstOrNull { it.key == key }

    fun dismiss(key: String) {
        runCatching { listener?.cancelNotification(key) }.onFailure { Log.w(TAG, "dismiss: $it") }
    }

    fun dismissAll() {
        runCatching { listener?.cancelAllNotifications() }.onFailure { Log.w(TAG, "dismissAll: $it") }
    }

    /**
     * Tap on a notification: fire its content intent, then cancel it if the app asked for that
     * (FLAG_AUTO_CANCEL). Stock SystemUI does the same through NotificationManager on a click.
     */
    fun open(ctx: Context, n: HioneNotif): Boolean {
        val ok = send(ctx, n.contentIntent)
        if (ok && n.autoCancel && n.isClearable) dismiss(n.key)
        return ok
    }

    /** Fire a notification's content/action intent; the shade closes itself afterwards. */
    fun send(ctx: Context, pi: PendingIntent?, fillIn: Intent? = null): Boolean {
        pi ?: return false
        return runCatching {
            val opts = android.app.ActivityOptions.makeBasic()
            runCatching {
                // Android 14: allow the notification's own activity to come to front from our shade.
                val m = opts.javaClass.getMethod("setPendingIntentBackgroundActivityStartMode", Int::class.javaPrimitiveType)
                m.invoke(opts, 1 /* MODE_BACKGROUND_ACTIVITY_START_ALLOWED */)
            }
            pi.send(ctx, 0, fillIn, null, null, null, opts.toBundle())
            true
        }.onFailure { Log.w(TAG, "send: $it") }.getOrDefault(false)
    }

    /** Send typed text through a reply action, the same way stock SystemUI's inline reply does. */
    fun reply(ctx: Context, action: HioneNotifAction, text: CharSequence): Boolean {
        val inputs = action.remoteInputs.toTypedArray()
        if (inputs.isEmpty()) return false
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        val results = Bundle()
        inputs.filter { it.allowFreeFormInput }.forEach { results.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        if (android.os.Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        return send(ctx, action.intent, intent)
    }

    /**
     * Make sure the system has granted our listener access. Writing Settings.Secure
     * enabled_notification_listeners is NOT enough on Android 14: NotificationManagerService keeps
     * its own approved list and ignores that setting after first boot, so the listener was never
     * bound and the shade showed no notifications at all. The hidden
     * NotificationManager.setNotificationListenerAccessGranted is the call stock Settings makes
     * (needs MANAGE_NOTIFICATION_LISTENERS, a signature permission we hold as a platform app).
     */
    fun ensureEnabled(ctx: Context) {
        val cn = ComponentName(ctx, HioneNotificationListenerService::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val granted = android.os.Build.VERSION.SDK_INT >= 27 &&
            runCatching { nm?.isNotificationListenerAccessGranted(cn) == true }.getOrDefault(false)
        if (!granted) {
            val ok = runCatching {
                runCatching {
                    nm!!.javaClass.getMethod("setNotificationListenerAccessGranted",
                        ComponentName::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                        .invoke(nm, cn, true, true)
                }.getOrElse {
                    nm!!.javaClass.getMethod("setNotificationListenerAccessGranted",
                        ComponentName::class.java, Boolean::class.javaPrimitiveType)
                        .invoke(nm, cn, true)
                }
                true
            }.onFailure { Log.w(TAG, "grant listener access: $it") }.getOrDefault(false)
            Log.i(TAG, "listener access was off; grant ${if (ok) "ok" else "failed"}")
        }
        // Keep the legacy setting in step too (older builds and Settings read it).
        try {
            val cr = ctx.contentResolver
            val me = cn.flattenToString()
            val cur = Settings.Secure.getString(cr, "enabled_notification_listeners") ?: ""
            if (!cur.split(':').contains(me)) {
                Settings.Secure.putString(cr, "enabled_notification_listeners", if (cur.isBlank()) me else "$cur:$me")
            }
        } catch (t: Throwable) { Log.w(TAG, "ensureEnabled setting: $t") }
        if (!_connected.value) runCatching { NotificationListenerService.requestRebind(cn) }
    }
}

internal fun Drawable.toBitmapSafe(sizePx: Int): Bitmap? = runCatching {
    val w = if (intrinsicWidth > 0) minOf(intrinsicWidth, sizePx) else sizePx
    val h = if (intrinsicHeight > 0) minOf(intrinsicHeight, sizePx) else sizePx
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    setBounds(0, 0, w, h); draw(c); bmp
}.getOrNull()

class HioneNotificationListenerService : NotificationListenerService() {
    private val tag = "HioneNotifs"

    override fun onListenerConnected() {
        super.onListenerConnected()
        HioneNotificationStore.listener = this
        HioneNotificationStore.setConnected(this, true)
        refresh()
        Log.i(tag, "listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        HioneNotificationStore.listener = null
        HioneNotificationStore.setConnected(this, false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) { refresh() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { refresh() }
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) { refresh() }

    private fun refresh() {
        val sbns = runCatching { activeNotifications }.getOrNull()
        HioneNotificationStore.refresh(this, sbns)
    }
}
