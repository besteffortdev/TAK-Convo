package com.atakmap.android.takconvo.plugin.xmpp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.RemoteInput;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.SparseArray;

import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.android.takconvo.plugin.config.XmppSettings;
import com.atakmap.coremap.log.Log;

import eu.siacs.conversations.R;
import eu.siacs.conversations.utils.TakConvoCompat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Fixes Conversations' notifications for ATAK: resource icons become bitmaps (as ATAK's they
 * would resolve in ATAK's package, or crash it), and message notifications move to a channel
 * with the sound and vibration of the plugin's settings. See docs/08.
 */
final class EmbeddedNotifications implements TakConvoCompat.NotificationFilter {

    private static final String TAG = "TakConvo.Notifications";
    private static final int ICON_DP = 24;
    /** Conversations' alerting message channel. */
    private static final String MESSAGES_CHANNEL = "messages";
    /** The groups of Conversations' channels, ours included. */
    private static final Set<String> CONVERSATIONS_CHANNEL_GROUPS =
            new HashSet<>(Arrays.asList("status", "chats", "calls"));
    /** Conversations' notification groups, e.g. its messages'. */
    private static final String CONVERSATIONS_GROUP_PREFIX = "eu.siacs.conversations.";

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
        // for the icons' tints
        theme.applyStyle(R.style.Theme_Conversations3, true);
    }

    /** Cancels what Conversations posted: messages show their text. Any thread. */
    static void cancelAll(final Context atak) {
        final NotificationManager manager = atak.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        for (final StatusBarNotification posted : manager.getActiveNotifications()) {
            if (isConversations(manager, posted.getNotification())) {
                manager.cancel(posted.getTag(), posted.getId());
            }
        }
    }

    private static boolean isConversations(final NotificationManager manager,
            final Notification notification) {
        final String group = notification.getGroup();
        if (group != null && group.startsWith(CONVERSATIONS_GROUP_PREFIX)) {
            return true;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || notification.getChannelId() == null) {
            return false;
        }
        final NotificationChannel channel =
                manager.getNotificationChannel(notification.getChannelId());
        return channel != null && CONVERSATIONS_CHANNEL_GROUPS.contains(channel.getGroup());
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
     * The channel for the sound and vibration settings, or null to keep Conversations'. Only
     * the user can change a channel once it exists, hence one channel per combination.
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
            return null; // as the user may have set it up
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
                    // still heads-up, only quieter
                    NotificationManager.IMPORTANCE_HIGH);
            created.setGroup("chats"); // Conversations' channel group
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

    /** A resource icon that isn't the system's. */
    private static boolean needsIcon(final Icon icon) {
        return icon != null && icon.getType() == Icon.TYPE_RESOURCE
                && !"android".equals(icon.getResPackage());
    }

    private synchronized Icon bitmapIcon(final Icon resourceIcon) {
        final int id = resourceIcon.getResId();
        Icon icon = icons.get(id);
        if (icon == null) {
            final Drawable drawable = resources.getDrawable(id, theme).mutate();
            // only the alpha is used
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
