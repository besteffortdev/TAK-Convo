package com.atakmap.android.takconvo.plugin.config;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.RestrictionsManager;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.atakmap.android.takconvo.plugin.BuildConfig;

import java.util.Map;

/**
 * Hands the managed configuration an MDM set for the plugin's package to ATAK. Android lets
 * only the package itself read it, and the plugin's code runs as ATAK, so this runs in the
 * plugin's own process ({@code :appconfig}). Only ATAK may call it. See docs/03.
 *
 * <p>ATAK's classes don't exist in this process: android.util.Log, not ATAK's.
 */
public final class AppConfigProvider extends ContentProvider {

    private static final String TAG = "TakConvo.AppConfig";
    /** Returns the managed configuration as a Bundle. */
    static final String METHOD_GET = "get";
    /** Debug builds: replaces the values {@link #METHOD_GET} adds to the MDM's. */
    static final String METHOD_DEBUG_SET = "debug_set";
    private static final String DEBUG_PREFS = "takconvo_debug_app_config";

    /** The provider's address, in the package of this build's flavor. */
    static Uri uri() {
        return Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".appconfig");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(final String method, final String arg, final Bundle extras) {
        // getCallingPackage() is checked against the caller's uid by the system
        if (!BuildConfig.ATAK_PACKAGE_NAME.equals(getCallingPackage())) {
            Log.w(TAG, "refused for " + getCallingPackage());
            throw new SecurityException("only ATAK reads TAK Convo's managed configuration");
        }
        final Context context = getContext();
        if (METHOD_GET.equals(method)) {
            final RestrictionsManager restrictions =
                    (RestrictionsManager) context.getSystemService(Context.RESTRICTIONS_SERVICE);
            final Bundle config = restrictions == null ? new Bundle()
                    : restrictions.getApplicationRestrictions();
            if (BuildConfig.DEBUG) {
                addDebugValues(context, config);
            }
            return config;
        }
        if (BuildConfig.DEBUG && METHOD_DEBUG_SET.equals(method)) {
            setDebugValues(context, extras == null ? new Bundle() : extras);
            return new Bundle();
        }
        throw new IllegalArgumentException("unknown method " + method);
    }

    /** What DEBUG_APP_CONFIG set, over the MDM's values: devices without an MDM can test. */
    private static void addDebugValues(final Context context, final Bundle config) {
        final Map<String, ?> values =
                context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE).getAll();
        for (final Map.Entry<String, ?> e : values.entrySet()) {
            if (e.getValue() instanceof Boolean) {
                config.putBoolean(e.getKey(), (Boolean) e.getValue());
            } else if (e.getValue() instanceof Integer) {
                config.putInt(e.getKey(), (Integer) e.getValue());
            } else if (e.getValue() != null) {
                config.putString(e.getKey(), String.valueOf(e.getValue()));
            }
        }
    }

    private static void setDebugValues(final Context context, final Bundle values) {
        final SharedPreferences.Editor editor =
                context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE).edit().clear();
        for (final String key : values.keySet()) {
            final Object value = values.get(key);
            if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof Integer) {
                editor.putInt(key, (Integer) value);
            } else if (value != null) {
                editor.putString(key, String.valueOf(value));
            }
        }
        editor.apply();
        Log.d(TAG, "debug values set: " + values.keySet());
    }

    // only call() is offered

    @Override
    public Cursor query(final Uri uri, final String[] projection, final String selection,
            final String[] selectionArgs, final String sortOrder) {
        return null;
    }

    @Override
    public String getType(final Uri uri) {
        return null;
    }

    @Override
    public Uri insert(final Uri uri, final ContentValues values) {
        return null;
    }

    @Override
    public int delete(final Uri uri, final String selection, final String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(final Uri uri, final ContentValues values, final String selection,
            final String[] selectionArgs) {
        return 0;
    }
}
