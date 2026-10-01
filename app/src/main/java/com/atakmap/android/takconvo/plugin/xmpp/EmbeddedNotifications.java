package com.atakmap.android.takconvo.plugin.xmpp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.RemoteInput;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.SparseArray;

import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.android.takconvo.plugin.config.XmppSettings;
import com.atakmap.coremap.log.Log;

import eu.siacs.conversations.R;
import eu.siacs.conversations.utils.TakConvoCompat;

/**
 * Gives Conversations' notifications icons that work when they are posted as ATAK's, and the
 * sound and vibration the plugin's settings ask for.
 *
 * <p>Conversations sets its icons as resource ids, which a notification resolves in the package
 * that posts it: ATAK's. The system UI then draws whichever ATAK drawable has that id, or fails
 * to, which crashes the posting app ("Bad notification posted"). The small icon and the action
 * icons are replaced by bitmaps drawn from the plugin's resources.
 *
 * <p>A message notification's sound and vibration are its channel's (Android 8 and later), and
 * only the user can change a channel's once it exists. Conversations' {@code messages} channel
 * has both; with either turned off in the settings, messages go to a channel of the plugin's
 * created without it.
 */
final class EmbeddedNotifications implements TakConvoCompat.NotificationFilter {

    private static final String TAG = "TakConvo.Notifications";
    /** status bar icons are 24dp */
    private static final int ICON_DP = 24;
    /** Conversations' channel of message notifications that alert */
    private static final String MESSAGES_CHANNEL = "messages";

    private final Context atak;
    private final Context plugin;
    private final Resources resources;
    private final Resources.Theme theme;
    private final SparseArray<Icon> icons = new SparseArray<>();

    EmbeddedNotifications(final Context atak, final Context plugin) {
        this.atak = atak.getApplicationContext();
        this.plugin = plugin;
        this.resources = plugin.getResources();
        this.theme = resources.newTheme();
        // the icons' tints refer to theme attributes
        theme.applyStyle(R.style.Theme_Conversations3, true);
    }

    @Override
    public Notification filter(final Notification notification) {
        final String channel = alertChannel(notification);
        if (channel == null && !needsIcon(notification.getSmallIcon())
                && !actionsNeedIcons(notification)) {
            return notification;
        }
        try {
            final Notification.Builder builder =
                    Notification.Builder.recoverBuilder(atak, notification);
            if (channel != null) {
                builder.setChannelId(channel);
            }
            if (needsIcon(notification.getSmallIcon())) {
                builder.setSmallIcon(bitmapIcon(notification.getSmallIcon()));
            }
            if (notification.actions != null) {
                final Notification.Action[] actions =
                        new Notification.Action[notification.actions.length];
                for (int i = 0; i < actions.length; i++) {
                    actions[i] = withBitmapIcon(notification.actions[i]);
                }
                builder.setActions(actions);
            }
            return builder.build();
        } catch (final RuntimeException e) {
            // posting it as it is could crash ATAK
            Log.e(TAG, "unable to fix the icons of a notification, dropping it", e);
            return null;
        }
    }

    /**
     * The channel a message notification goes to instead of Conversations' own, for the sound
     * and vibration set in the plugin's settings; null to leave it.
     */
    private String alertChannel(final Notification notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || !MESSAGES_CHANNEL.equals(notification.getChannelId())) {
            return null;
        }
        final SharedPreferences prefs = AtakPreferences.getInstance(atak).getSharedPrefs();
        final boolean sound = XmppSettings.notificationSound(prefs);
        final boolean vibrate = XmppSettings.notificationVibrate(prefs);
        if (sound && vibrate) {
            return null; // Conversations' channel, as the user may have set it up
        }
        final String id = "takconvo_messages" + (sound ? "_sound" : "")
                + (vibrate ? "_vibrate" : "");
        final NotificationManager manager = atak.getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(id) == null) {
            final NotificationChannel created = new NotificationChannel(id,
                    plugin.getString(sound ? com.atakmap.android.takconvo.plugin.R.string
                            .takconvo_channel_messages_no_vibration
                            : vibrate ? com.atakmap.android.takconvo.plugin.R.string
                                    .takconvo_channel_messages_no_sound
                                    : com.atakmap.android.takconvo.plugin.R.string
                                            .takconvo_channel_messages_silent),
                    // high: still a heads-up notification, only quieter
                    NotificationManager.IMPORTANCE_HIGH);
            created.setGroup("chats"); // Conversations' group of message channels
            created.setShowBadge(true);
            created.enableLights(true);
            created.setSound(sound ? RingtoneManager.getDefaultUri(
                    RingtoneManager.TYPE_NOTIFICATION) : null,
                    sound ? new AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .build() : null);
            created.enableVibration(vibrate);
            if (vibrate) {
                // Conversations' pattern
                created.setVibrationPattern(new long[] {0, 210, 70, 70});
            }
            manager.createNotificationChannel(created);
            Log.d(TAG, "created notification channel " + id);
        }
        return id;
    }

    private Notification.Action withBitmapIcon(final Notification.Action action) {
        if (!needsIcon(action.getIcon())) {
            return action;
        }
        final Notification.Action.Builder builder = new Notification.Action.Builder(
                bitmapIcon(action.getIcon()), action.title, action.actionIntent);
        builder.addExtras(action.getExtras());
        if (action.getRemoteInputs() != null) {
            for (final RemoteInput input : action.getRemoteInputs()) {
                builder.addRemoteInput(input);
            }
        }
        builder.setAllowGeneratedReplies(action.getAllowGeneratedReplies());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setSemanticAction(action.getSemanticAction());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setContextual(action.isContextual());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAuthenticationRequired(action.isAuthenticationRequired());
        }
        return builder.build();
    }

    private boolean actionsNeedIcons(final Notification notification) {
        if (notification.actions != null) {
            for (final Notification.Action action : notification.actions) {
                if (needsIcon(action.getIcon())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A resource icon of Conversations: in the posting package's, or no, package. */
    private static boolean needsIcon(final Icon icon) {
        return icon != null && icon.getType() == Icon.TYPE_RESOURCE
                && !"android".equals(icon.getResPackage());
    }

    private synchronized Icon bitmapIcon(final Icon resourceIcon) {
        final int id = resourceIcon.getResId();
        Icon icon = icons.get(id);
        if (icon == null) {
            final Drawable drawable = resources.getDrawable(id, theme).mutate();
            // the system only uses the icon's alpha
            drawable.setTint(Color.WHITE);
            final int size = Math.round(ICON_DP * resources.getDisplayMetrics().density);
            final Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(new Canvas(bitmap));
            icon = Icon.createWithBitmap(bitmap);
            icons.put(id, icon);
        }
        return icon;
    }
}
