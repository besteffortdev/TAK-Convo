package eu.siacs.conversations.utils;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * TAKCONVO: SDK 37 constants (the build is on SDK 36, the newest AGP 8 supports) and the hooks
 * the TAK Convo plugin sets inside ATAK. Unset outside ATAK, where every helper does what
 * upstream does.
 */
public final class TakConvoCompat {

    /** {@code Build.VERSION_CODES.CINNAMON_BUN} */
    public static final int CINNAMON_BUN = 37;

    /** {@code Manifest.permission.ACCESS_LOCAL_NETWORK} */
    public static final String ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK";

    /**
     * Running inside ATAK: there is no real Service or Application, so foreground service,
     * stopSelf, component toggling and telecom are skipped.
     */
    public static volatile boolean EMBEDDED = false;

    /** CAs the plugin adds, checked after the system's; hostname verification is unchanged. */
    public static volatile javax.net.ssl.X509TrustManager EXTRA_TRUST_MANAGER = null;

    /** Where "Discover channels" looks on XMPP servers; null for the account's server. */
    public static volatile eu.siacs.conversations.xmpp.Jid CHANNEL_DISCOVERY_SERVER = null;

    /** Makes the PendingIntents, whose Conversations targets ATAK's package doesn't have. */
    public interface PendingIntentFactory {
        PendingIntent getActivity(Context context, int requestCode, Intent intent, int flags);

        PendingIntent getService(Context context, int requestCode, Intent intent, int flags);

        PendingIntent getBroadcast(Context context, int requestCode, Intent intent, int flags);
    }

    public static volatile PendingIntentFactory PENDING_INTENTS = null;

    /** Adjusts a notification posted as ATAK's, e.g. its resource icons. */
    public interface NotificationFilter {
        /** The notification to post, or null to drop it (notify() then logs the failure). */
        Notification filter(Notification notification);
    }

    public static volatile NotificationFilter NOTIFICATIONS = null;

    /**
     * What XmppConnectionService tells its UI listeners, without being one: a listener makes
     * Conversations think it is on screen. Called on the service's threads.
     */
    public interface Observer {
        void onAccountsChanged();

        void onConversationsChanged();

        void onRosterChanged();

        /** The count upstream would show on the launcher icon. */
        void onUnreadCountChanged(int count);
    }

    public static volatile Observer OBSERVER = null;

    public static PendingIntent getActivity(
            final Context context, final int requestCode, final Intent intent, final int flags) {
        final PendingIntentFactory factory = PENDING_INTENTS;
        return EMBEDDED && factory != null
                ? factory.getActivity(context, requestCode, intent, flags)
                : PendingIntent.getActivity(context, requestCode, intent, flags);
    }

    public static PendingIntent getService(
            final Context context, final int requestCode, final Intent intent, final int flags) {
        final PendingIntentFactory factory = PENDING_INTENTS;
        return EMBEDDED && factory != null
                ? factory.getService(context, requestCode, intent, flags)
                : PendingIntent.getService(context, requestCode, intent, flags);
    }

    public static PendingIntent getBroadcast(
            final Context context, final int requestCode, final Intent intent, final int flags) {
        final PendingIntentFactory factory = PENDING_INTENTS;
        return EMBEDDED && factory != null
                ? factory.getBroadcast(context, requestCode, intent, flags)
                : PendingIntent.getBroadcast(context, requestCode, intent, flags);
    }

    public static Notification filter(final Notification notification) {
        final NotificationFilter filter = NOTIFICATIONS;
        return EMBEDDED && filter != null ? filter.filter(notification) : notification;
    }

    public static Observer observer() {
        return EMBEDDED ? OBSERVER : null;
    }

    private TakConvoCompat() {}
}
