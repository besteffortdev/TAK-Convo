package com.atakmap.android.takconvo.plugin.xmpp;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import androidx.core.content.ContextCompat;

import com.atakmap.android.takconvo.plugin.config.PrivateFiles;
import com.atakmap.android.util.ATAKConstants;
import com.atakmap.coremap.log.Log;

import eu.siacs.conversations.utils.TakConvoCompat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Conversations' PendingIntents, which aim at components ATAK's package doesn't have: an
 * activity becomes ATAK's activity with {@link #ACTION_OPEN} as its internalIntent; a service
 * or receiver becomes a broadcast that {@link #receiver} hands to the engine. Others are left
 * as they are. See docs/08.
 */
public final class EmbeddedPendingIntents implements TakConvoCompat.PendingIntentFactory {

    private static final String TAG = "TakConvo.PendingIntents";

    /** AtakBroadcast that opens a Conversations activity; see {@link #unwrapActivity}. */
    public static final String ACTION_OPEN = "com.atakmap.android.takconvo.OPEN";

    /** Replaces a service start or receiver broadcast. */
    private static final String ACTION_DELIVER = "com.atakmap.android.takconvo.DELIVER";
    private static final String SCHEME = "takconvo";
    private static final String EXTRA_ACTION = "takconvo.action";
    private static final String EXTRA_CLASS = "takconvo.class";
    private static final String EXTRA_TOKEN = "takconvo.token";
    private static final String CONVERSATIONS_PACKAGE = "eu.siacs.conversations.";
    /** {@link PrivateFiles} name of {@link #token}. */
    private static final String TOKEN_FILE = "open_token";

    private final Context atak;
    /**
     * Proves an {@link #ACTION_OPEN} comes from our notification: ATAK rebroadcasts any app's
     * internalIntent. Kept across restarts, for the notifications still in the shade.
     */
    private final String token;
    /** Where delivered intents run. */
    private final Context engine;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            deliver(intent);
        }
    };
    private boolean registered;

    EmbeddedPendingIntents(final Context atak, final Context engine) {
        this.atak = atak.getApplicationContext();
        this.engine = engine;
        final String stored = PrivateFiles.read(this.atak, TOKEN_FILE);
        if (stored != null && !stored.isEmpty()) {
            token = stored;
        } else {
            final byte[] random = new byte[16];
            new SecureRandom().nextBytes(random);
            token = Base64.encodeToString(random, Base64.NO_WRAP | Base64.URL_SAFE);
            // if it can't be stored, the taps work until ATAK restarts
            PrivateFiles.write(this.atak, TOKEN_FILE, token);
        }
    }

    void register() {
        if (registered) {
            return;
        }
        final IntentFilter filter = new IntentFilter(ACTION_DELIVER);
        filter.addDataScheme(SCHEME);
        // sent on ATAK's behalf, so not exported; before Android 13 a plain registerReceiver
        // would be, and ContextCompat requires ATAK's signature permission instead
        ContextCompat.registerReceiver(atak, receiver, filter, null, mainHandler,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        registered = true;
    }

    void unregister() {
        if (registered) {
            atak.unregisterReceiver(receiver);
            registered = false;
        }
    }

    @Override
    public PendingIntent getActivity(final Context context, final int requestCode,
            final Intent intent, final int flags) {
        final String name = conversationsClass(intent);
        if (name == null) {
            return PendingIntent.getActivity(context, requestCode, intent, flags);
        }
        final Intent open = new Intent(ACTION_OPEN);
        if (intent.getExtras() != null) {
            open.putExtras(intent.getExtras());
        }
        open.putExtra(EXTRA_CLASS, name);
        open.putExtra(EXTRA_ACTION, intent.getAction());
        open.putExtra(EXTRA_TOKEN, token);

        final Intent front = new Intent();
        front.setComponent(ATAKConstants.getComponentName());
        // unread by ATAK: keeps the PendingIntents apart, as distinct targets did
        front.setAction(ACTION_OPEN + ":" + name + ":" + intent.getAction());
        front.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        front.putExtra("internalIntent", open);
        return PendingIntent.getActivity(atak, requestCode, front, flags);
    }

    @Override
    public PendingIntent getService(final Context context, final int requestCode,
            final Intent intent, final int flags) {
        final String name = conversationsClass(intent);
        return name == null
                ? PendingIntent.getService(context, requestCode, intent, flags)
                : PendingIntent.getBroadcast(atak, requestCode, wrap(intent, name), flags);
    }

    @Override
    public PendingIntent getBroadcast(final Context context, final int requestCode,
            final Intent intent, final int flags) {
        final String name = conversationsClass(intent);
        return name == null
                ? PendingIntent.getBroadcast(context, requestCode, intent, flags)
                : PendingIntent.getBroadcast(atak, requestCode, wrap(intent, name), flags);
    }

    /**
     * The broadcast for {@link #receiver}; the target goes into its data, which keeps the
     * PendingIntents apart and lets one filter match them all.
     */
    private Intent wrap(final Intent intent, final String name) {
        final Intent wrapped = new Intent(intent);
        wrapped.setComponent(null);
        wrapped.setAction(ACTION_DELIVER);
        wrapped.setData(new Uri.Builder().scheme(SCHEME).authority("deliver")
                .appendPath(name)
                .appendPath(intent.getAction() == null ? "" : intent.getAction())
                .build());
        wrapped.setPackage(atak.getPackageName());
        wrapped.putExtra(EXTRA_CLASS, name);
        wrapped.putExtra(EXTRA_ACTION, intent.getAction());
        return wrapped;
    }

    /** Hands a wrapped intent to its original target. */
    private void deliver(final Intent wrapped) {
        final String name = wrapped.getStringExtra(EXTRA_CLASS);
        if (name == null || !name.startsWith(CONVERSATIONS_PACKAGE)) {
            return;
        }
        // a copy keeps what the system added, e.g. a direct reply's text
        final Intent intent = new Intent(wrapped);
        intent.setAction(wrapped.getStringExtra(EXTRA_ACTION));
        intent.setData(null);
        intent.setPackage(null);
        intent.removeExtra(EXTRA_CLASS);
        intent.removeExtra(EXTRA_ACTION);
        intent.setComponent(new ComponentName(atak.getPackageName(), name));
        intent.setExtrasClassLoader(engine.getClassLoader());
        Log.d(TAG, "delivering " + intent.getAction() + " to " + name);
        try {
            final Class<?> cls = engine.getClassLoader().loadClass(name);
            if (Service.class.isAssignableFrom(cls)) {
                // EmbeddedContext routes it to the engine
                engine.startService(intent);
            } else if (BroadcastReceiver.class.isAssignableFrom(cls)) {
                ((BroadcastReceiver) cls.getDeclaredConstructor().newInstance())
                        .onReceive(engine, intent);
            } else {
                Log.w(TAG, "can't deliver to " + name);
            }
        } catch (final ReflectiveOperationException | RuntimeException e) {
            Log.e(TAG, "unable to deliver " + intent.getAction() + " to " + name, e);
        }
    }

    /** The activity intent of an {@link #ACTION_OPEN} broadcast, or null if it isn't ours. */
    Intent unwrapActivity(final Intent open, final ClassLoader classLoader) {
        final String name = open.getStringExtra(EXTRA_CLASS);
        if (!ACTION_OPEN.equals(open.getAction()) || name == null
                || !name.startsWith(CONVERSATIONS_PACKAGE)) {
            return null;
        }
        final String received = open.getStringExtra(EXTRA_TOKEN);
        if (received == null || !MessageDigest.isEqual(received.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            Log.w(TAG, "ignoring an open request that didn't come from a notification");
            return null;
        }
        final Intent intent = new Intent(open.getStringExtra(EXTRA_ACTION));
        final Bundle extras = open.getExtras();
        if (extras != null) {
            extras.remove(EXTRA_CLASS);
            extras.remove(EXTRA_ACTION);
            extras.remove(EXTRA_TOKEN);
            intent.putExtras(extras);
        }
        intent.setComponent(new ComponentName(ATAKConstants.getPackageName(), name));
        intent.setExtrasClassLoader(classLoader);
        return intent;
    }

    /** The Conversations class an intent aims at, or null. */
    private static String conversationsClass(final Intent intent) {
        final ComponentName component = intent == null ? null : intent.getComponent();
        final String name = component == null ? null : component.getClassName();
        return name != null && name.startsWith(CONVERSATIONS_PACKAGE) ? name : null;
    }
}
