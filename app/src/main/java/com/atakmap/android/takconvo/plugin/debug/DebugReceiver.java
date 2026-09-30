package com.atakmap.android.takconvo.plugin.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.android.takconvo.plugin.ui.TakConvoPreferenceFragment;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.comms.CotService;
import com.atakmap.android.takconvo.plugin.xmpp.XmppEngine;
import com.atakmap.app.SettingsActivity;
import com.atakmap.app.preferences.PreferenceControl;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Debug builds only: drive the engine from adb.
 *
 * <pre>
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_SET_PREF --es key K --es value V
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_PROVISION
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_SEND --es to J --es body B
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_ADD_TAK_SERVER \
 *     --es connect host:8089:ssl --es user U --es pass P
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_SHOW_ACCOUNT
 * adb shell am broadcast -a com.atakmap.android.takconvo.DEBUG_OPEN_SETTINGS
 * </pre>
 */
public final class DebugReceiver extends BroadcastReceiver {

    private static final String TAG = "TakConvo.Debug";
    private static final String PREFIX = "com.atakmap.android.takconvo.";
    public static final String ACTION_SET_PREF = PREFIX + "DEBUG_SET_PREF";
    public static final String ACTION_PROVISION = PREFIX + "DEBUG_PROVISION";
    public static final String ACTION_SEND = PREFIX + "DEBUG_SEND";
    public static final String ACTION_ADD_TAK_SERVER = PREFIX + "DEBUG_ADD_TAK_SERVER";
    public static final String ACTION_DUMP_SELF_SA = PREFIX + "DEBUG_DUMP_SELF_SA";
    public static final String ACTION_IMPORT_PREF = PREFIX + "DEBUG_IMPORT_PREF";
    public static final String ACTION_SHOW_ACCOUNT = PREFIX + "DEBUG_SHOW_ACCOUNT";
    public static final String ACTION_OPEN_SETTINGS = PREFIX + "DEBUG_OPEN_SETTINGS";

    private final Context context;

    private DebugReceiver(final Context context) {
        this.context = context;
    }

    public static DebugReceiver register(final Context context) {
        final DebugReceiver receiver = new DebugReceiver(context);
        final IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SET_PREF);
        filter.addAction(ACTION_PROVISION);
        filter.addAction(ACTION_SEND);
        filter.addAction(ACTION_ADD_TAK_SERVER);
        filter.addAction(ACTION_DUMP_SELF_SA);
        filter.addAction(ACTION_IMPORT_PREF);
        filter.addAction(ACTION_SHOW_ACCOUNT);
        filter.addAction(ACTION_OPEN_SETTINGS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        Log.d(TAG, "debug receiver registered");
        return receiver;
    }

    public void unregister() {
        context.unregisterReceiver(this);
    }

    @Override
    public void onReceive(final Context c, final Intent intent) {
        final String action = intent.getAction();
        final XmppEngine engine = XmppEngine.get();
        Log.d(TAG, "received " + action);
        if (ACTION_SET_PREF.equals(action)) {
            final String key = intent.getStringExtra("key");
            final String value = intent.getStringExtra("value");
            if (key != null) {
                AtakPreferences.getInstance(context).set(key, value);
                Log.d(TAG, "set " + key);
            }
        } else if (ACTION_PROVISION.equals(action) && engine != null) {
            engine.provision();
        } else if (ACTION_ADD_TAK_SERVER.equals(action)) {
            // same calls as ATAK's "add TAK server" dialog; credentials go to ATAK's auth DB
            final String connect = intent.getStringExtra("connect");
            final Bundle data = new Bundle();
            data.putString("description", "TAK Convo test server");
            data.putBoolean("enabled", true);
            data.putBoolean("useAuth", true);
            final CotService cot = CommsMapComponent.getInstance().getCotService();
            cot.addStreaming(connect, data);
            cot.setCredentialsForStream(connect, intent.getStringExtra("user"),
                    intent.getStringExtra("pass"));
            Log.d(TAG, "added TAK server " + connect);
        } else if (ACTION_IMPORT_PREF.equals(action)) {
            // ATAK's own .pref import (what the import manager / mission packages use)
            final String path = intent.getStringExtra("path");
            final List<String> loaded = PreferenceControl.getInstance(context)
                    .loadSettings(new File(path));
            Log.d(TAG, "imported " + path + ": " + loaded);
        } else if (ACTION_DUMP_SELF_SA.equals(action)) {
            // the SA this device sends, built by ATAK's private CotMapComponent.getSelfEvent(int)
            try {
                final Method m = CotMapComponent.class.getDeclaredMethod("getSelfEvent",
                        int.class);
                m.setAccessible(true);
                Log.d(TAG, "self SA: " + m.invoke(CotMapComponent.getInstance(), 0));
            } catch (final Exception e) {
                Log.e(TAG, "unable to build self SA", e);
            }
        } else if (ACTION_SHOW_ACCOUNT.equals(action)) {
            AtakBroadcast.getInstance().sendBroadcast(
                    new Intent(TakConvoPreferenceFragment.ACTION_SHOW_ACCOUNT));
        } else if (ACTION_OPEN_SETTINGS.equals(action)) {
            SettingsActivity.start(TakConvoPreferenceFragment.TOOL_KEY, null);
        } else if (ACTION_SEND.equals(action) && engine != null) {
            final boolean ok = engine.sendMessage(intent.getStringExtra("to"),
                    intent.getStringExtra("body"));
            Log.d(TAG, "send " + (ok ? "queued" : "rejected"));
        }
    }
}
