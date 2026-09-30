package eu.siacs.conversations.utils;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * TAKCONVO: what the fork needs to run inside ATAK: constants from Android SDK 37, and the hooks
 * the TAK Convo plugin sets when it embeds Conversations. Outside ATAK none of the hooks is set
 * and every helper does what upstream does.
 *
 * <p>The plugin compiles against SDK 36 because that is the newest one AGP 8.13 supports, and the
 * ATAK takdev plugin needs AGP 8.
 */
public final class TakConvoCompat {

    /** {@code Build.VERSION_CODES.CINNAMON_BUN} */
    public static final int CINNAMON_BUN = 37;

    /** {@code Manifest.permission.ACCESS_LOCAL_NETWORK} */
    public static final String ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK";

    /**
     * True when Conversations runs embedded inside the ATAK process (the TAK Convo plugin) rather
     * than as its own app. There is then no real Android Service or Application: calls that need
     * one (foreground service, stopSelf, component toggling, telecom integration) are skipped.
     */
    public static volatile boolean EMBEDDED = false;

    /**
     * Additional trust anchors supplied by the plugin (e.g. a CA delivered in a mission package),
     * consulted by MemorizingTrustManager after the system CAs and before failing. Hostname
     * verification is unaffected.
     */
    public static volatile javax.net.ssl.X509TrustManager EXTRA_TRUST_MANAGER = null;

    /**
     * Makes the PendingIntents Conversations hands to the system (notification taps and actions,
     * alarms). Embedded, their targets are Conversations components that don't exist in ATAK's
     * package, so the plugin redirects them to ones that do.
     */
    public interface PendingIntentFactory {
        PendingIntent getActivity(Context context, int requestCode, Intent intent, int flags);

        PendingIntent getService(Context context, int requestCode, Intent intent, int flags);

        PendingIntent getBroadcast(Context context, int requestCode, Intent intent, int flags);
    }

    public static volatile PendingIntentFactory PENDING_INTENTS = null;

    /**
     * Adjusts a notification before it is posted. Embedded, it is posted as ATAK's, so its
     * resource icons would be looked up in ATAK's package.
     */
    public interface NotificationFilter {
        /**
         * @return the notification to post, or null to not post it (NotificationService.notify
         *     then fails and logs, which it catches)
         */
        Notification filter(Notification notification);
    }

    public static volatile NotificationFilter NOTIFICATIONS = null;

    /**
     * Told what XmppConnectionService tells its UI listeners. Registering as one of those
     * listeners would make Conversations believe it is on screen (see
     * XmppConnectionService.checkListeners): chats would be marked active and notifications
     * silenced. Called on the service's threads.
     */
    public interface Observer {
        void onAccountsChanged();

        void onConversationsChanged();

        void onRosterChanged();

        /** the unread count upstream would show on the launcher icon */
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
