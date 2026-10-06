package com.atakmap.android.takconvo.plugin.config;

import android.annotation.SuppressLint;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.RestrictionsManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.atakmap.android.takconvo.plugin.BuildConfig;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    /** In this package's storage, out of ATAK's (and a .pref file's) reach. */
    private static final String CALLER_PREFS = "takconvo_app_config_caller";
    /** The signing certificates (SHA-256) of the first ATAK that called. */
    private static final String KEY_ATAK_SIGNERS = "atak_signers";

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
        final String caller = getCallingPackage();
        if (!BuildConfig.ATAK_PACKAGE_NAME.equals(caller) || !isAtak(caller)) {
            Log.w(TAG, "refused for " + caller);
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

    /**
     * Whether the package with ATAK's name is ATAK, by its signing key: any app installed under
     * that name while ATAK isn't would otherwise read the managed XMPP password. ATAK is signed
     * like the plugin (the SDK's development key for both), with a key build.gradle lists
     * (atakSigners), or like the first ATAK that called: release ATAK's key isn't known here,
     * and an MDM installs ATAK with the plugin.
     */
    @SuppressLint("ApplySharedPref") // a binder thread, and stored before the answer
    private synchronized boolean isAtak(final String caller) {
        final Context context = getContext();
        final PackageManager pm = context.getPackageManager();
        if (pm.checkSignatures(context.getPackageName(), caller)
                == PackageManager.SIGNATURE_MATCH) {
            return true;
        }
        final Set<String> signers = signers(pm, caller);
        if (signers.isEmpty()) {
            return false;
        }
        for (final String known : BuildConfig.ATAK_SIGNERS.split(",")) {
            if (signers.contains(known.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        final SharedPreferences prefs =
                context.getSharedPreferences(CALLER_PREFS, Context.MODE_PRIVATE);
        final Set<String> first = prefs.getStringSet(KEY_ATAK_SIGNERS, null);
        if (first == null) {
            Log.i(TAG, "remembering the signing key of " + caller);
            // commit: before the configuration goes out
            return prefs.edit().putStringSet(KEY_ATAK_SIGNERS, signers).commit();
        }
        // a rotated key keeps the earlier ones in its history
        return !Collections.disjoint(first, signers);
    }

    /** SHA-256 digests of a package's signing certificates, with their history; or none. */
    @SuppressLint("PackageManagerGetSignatures") // the only way before Android 9; compared whole
    @SuppressWarnings("deprecation")
    private static Set<String> signers(final PackageManager pm, final String packageName) {
        final Set<String> digests = new HashSet<>();
        try {
            final Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                final SigningInfo info = pm.getPackageInfo(packageName,
                        PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
                signatures = info == null ? null : info.hasMultipleSigners()
                        ? info.getApkContentsSigners() : info.getSigningCertificateHistory();
            } else {
                signatures = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                        .signatures;
            }
            if (signatures != null) {
                final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                for (final Signature signature : signatures) {
                    digests.add(hex(sha256.digest(signature.toByteArray())));
                }
            }
        } catch (final PackageManager.NameNotFoundException e) {
            Log.w(TAG, "unable to read the signing key of " + packageName, e);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return digests;
    }

    private static String hex(final byte[] bytes) {
        final StringBuilder hex = new StringBuilder();
        for (final byte b : bytes) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        return hex.toString();
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
