package eu.siacs.conversations.utils;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.URLSpan;
import de.gultsch.common.MiniUri;
import eu.siacs.conversations.xmpp.Jid;
import java.io.File;
import java.util.List;

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

    /** Encrypts the credentials an account stores in the database: password, FAST token. */
    public interface CredentialCipher {
        String encrypt(String value);

        /** Also reads a value stored before encryption. */
        String decrypt(String stored);
    }

    public static volatile CredentialCipher CREDENTIALS = null;

    /**
     * The largest OMEMO key element processed. protobuf-java 2.5.0, which libsignal parses
     * them with, takes quadratic time on crafted input (CVE-2021-22569): 256 KiB took seconds.
     * Real ones are about 200 bytes.
     */
    public static final int MAX_OMEMO_KEY_BYTES = 2048;

    public static String encryptCredential(final String value) {
        final CredentialCipher cipher = CREDENTIALS;
        return EMBEDDED && cipher != null ? cipher.encrypt(value) : value;
    }

    public static String decryptCredential(final String stored) {
        final CredentialCipher cipher = CREDENTIALS;
        return EMBEDDED && cipher != null ? cipher.decrypt(stored) : stored;
    }

    /** A position written in a message, e.g. an MGRS grid reference: text from start to end. */
    public record Coordinates(int start, int end, double latitude, double longitude) {}

    /** What the chat screens use of ATAK: its map, its imports. Main thread. */
    public interface Atak {
        /** Texts for the quick message buttons above the message field; empty for none. */
        List<String> quickMessages();

        /** Whether a TAK user advertising this address is on ATAK's map. */
        boolean isOnMap(Jid address);

        /** Centers ATAK's map on that user. */
        void showOnMap(Jid address);

        /** Positions written in a text, linked to ATAK's map. */
        List<Coordinates> findCoordinates(String text);

        /**
         * Offers to import a map file into ATAK. False: open it as usual. {@code openElsewhere}
         * opens it as usual, if the user prefers that.
         */
        boolean openFile(Context context, File file, Runnable openElsewhere);
    }

    public static volatile Atak ATAK = null;

    public static Atak atak() {
        return EMBEDDED ? ATAK : null;
    }

    /** Links the positions written in a message to ATAK's map, where no other link is. */
    public static void linkCoordinates(final Spannable body) {
        final Atak atak = atak();
        if (atak == null) {
            return;
        }
        for (final Coordinates c : atak.findCoordinates(body.toString())) {
            if (body.getSpans(c.start(), c.end(), URLSpan.class).length > 0) {
                continue;
            }
            final String label = body.subSequence(c.start(), c.end()).toString();
            final String uri =
                    new MiniUri.Geo(c.latitude(), c.longitude()).asUniversalUri(label).toString();
            body.setSpan(new URLSpan(uri), c.start(), c.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

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
