package com.atakmap.android.takconvo.plugin.config;

import android.content.ContentProviderClient;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Base64;

import androidx.annotation.VisibleForTesting;

import com.atakmap.coremap.log.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The managed configuration ("app config") an MDM sets for the plugin's package. Its values
 * become the ATAK preferences a .pref file would set, and stay: a .pref import or the tool
 * preferences can't change a key it sets. A server it sets needs no approval. Immutable.
 * See docs/03.
 */
public final class AppConfig {

    private static final String TAG = "TakConvo.AppConfig";

    /** A CA certificate as text: PEM, or Base64 DER. Stored in {@link PrivateFiles}. */
    public static final String KEY_TRUSTED_CA_CERTIFICATE =
            "takconvo_xmpp_trusted_ca_certificate";
    /** The schema's choice for "not managed"; an empty value means the same. */
    private static final String UNSET = "unset";
    /** {@link PrivateFiles} name of the configuration last applied. */
    private static final String SNAPSHOT = "managed_config";
    private static final String CA_FILE_PREFIX = "managed_ca_";
    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", Pattern.DOTALL);
    /** Whitespace, and line breaks a text field kept as {@code \n}: never Base64. */
    private static final Pattern NOT_BASE64 = Pattern.compile("\\\\[nr]|\\s");

    private enum Type {
        BOOLEAN,
        STRING,
        PORT,
        CHANNEL_DISCOVERY
    }

    /** The plugin's own settings an MDM may set, as preference keys. */
    private static final Map<String, Type> KEYS = new HashMap<>();

    static {
        for (final String key : new String[] {XmppSettings.KEY_ENABLED,
                XmppSettings.KEY_USE_TAK_CREDENTIALS, XmppSettings.KEY_USE_TAK_TRUSTSTORE,
                XmppSettings.KEY_USE_ANDROID_CA_STORE, XmppSettings.KEY_USE_CALLSIGN,
                XmppSettings.KEY_NOTIFICATION_MESSAGES, XmppSettings.KEY_NOTIFICATION_SOUND,
                XmppSettings.KEY_NOTIFICATION_VIBRATE, XmppSettings.KEY_SHOW_QUICK_MESSAGES}) {
            KEYS.put(key, Type.BOOLEAN);
        }
        for (final String key : new String[] {XmppSettings.KEY_DOMAIN, XmppSettings.KEY_HOST,
                XmppSettings.KEY_TAK_SERVER, XmppSettings.KEY_USERNAME,
                XmppSettings.KEY_CHANNEL_SERVER, XmppSettings.KEY_QUICK_MESSAGES}) {
            KEYS.put(key, Type.STRING);
        }
        KEYS.put(XmppSettings.KEY_PORT, Type.PORT);
        KEYS.put(XmppSettings.KEY_CHANNEL_DISCOVERY, Type.CHANNEL_DISCOVERY);
    }

    /** Where the credentials go: kept at their defaults when the MDM sets only the domain. */
    private static final String[] SERVER_KEYS = {XmppSettings.KEY_HOST, XmppSettings.KEY_PORT,
            XmppSettings.KEY_USE_TAK_CREDENTIALS, XmppSettings.KEY_TAK_SERVER,
            XmppSettings.KEY_USE_TAK_TRUSTSTORE, XmppSettings.KEY_USE_ANDROID_CA_STORE,
            XmppSettings.KEY_TRUSTED_CA};

    /** A key the MDM keeps at its default: absent from the preferences. */
    static final Object DEFAULT = new Object();

    /** No managed configuration. */
    public static final AppConfig NONE =
            new AppConfig(Collections.emptyMap(), false, null, false);

    /** Preference key to a Boolean, a String or {@link #DEFAULT}. */
    private final Map<String, Object> values;
    private final boolean managesLogin;
    /** Where the managed values send the credentials; null unless the MDM sets the domain. */
    private final ServerIdentity server;
    /** The login was stored in this read; not part of the configuration. */
    private final boolean savedLogin;

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    AppConfig(final Map<String, Object> values, final boolean managesLogin,
            final ServerIdentity server, final boolean savedLogin) {
        this.values = Collections.unmodifiableMap(values);
        this.managesLogin = managesLogin;
        this.server = server;
        this.savedLogin = savedLogin;
    }

    /**
     * The MDM's values for the plugin's package, read in the plugin's own process; null if that
     * process can't be reached. IPC: not on the main thread.
     */
    public static Bundle read(final Context atakContext) {
        return call(atakContext, AppConfigProvider.METHOD_GET, null);
    }

    /** Debug builds: values added to the MDM's, as if it had set them. IPC. */
    public static void debugSet(final Context atakContext, final Bundle values) {
        call(atakContext, AppConfigProvider.METHOD_DEBUG_SET, values);
    }

    private static Bundle call(final Context atakContext, final String method,
            final Bundle extras) {
        // unstable: ATAK survives the plugin's process dying during the call
        final ContentProviderClient client = atakContext.getContentResolver()
                .acquireUnstableContentProviderClient(AppConfigProvider.uri());
        if (client == null) {
            Log.w(TAG, "the plugin's managed configuration provider is missing");
            return null;
        }
        try {
            return client.call(method, null, extras);
        } catch (final RemoteException | SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "unable to reach the managed configuration provider", e);
            return null;
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                client.close();
            } else {
                client.release();
            }
        }
    }

    /**
     * Checks the MDM's values, stores the CA certificate and the login they carry. Invalid
     * values are logged and left out. Disk work: not on the main thread.
     */
    @SuppressWarnings("deprecation") // Bundle.get: the values are of several types
    public static AppConfig load(final Context atakContext, final Bundle managed,
            final AppConfig previous) {
        final Map<String, Object> values = new HashMap<>();
        for (final String key : managed.keySet()) {
            final Object raw = managed.get(key);
            final String text = raw == null ? "" : String.valueOf(raw).trim();
            if (text.isEmpty() || UNSET.equalsIgnoreCase(text)
                    || XmppSettings.KEY_PASSWORD.equals(key)
                    || KEY_TRUSTED_CA_CERTIFICATE.equals(key)) {
                continue;
            }
            final Object value;
            if (KEYS.containsKey(key)) {
                value = parse(KEYS.get(key), raw, text);
            } else if (key.startsWith(ConversationsSettings.PREFIX)) {
                value = ConversationsSettings.typedValue(
                        key.substring(ConversationsSettings.PREFIX.length()), text);
            } else {
                Log.w(TAG, "not a managed setting: " + key);
                continue;
            }
            if (value == null) {
                Log.w(TAG, "invalid managed value for " + key);
            } else {
                values.put(key, value);
            }
        }

        final File ca = storeCertificate(atakContext, managed.getString(
                KEY_TRUSTED_CA_CERTIFICATE), previous);
        if (ca != null) {
            values.put(XmppSettings.KEY_TRUSTED_CA, ca.getAbsolutePath());
        }

        final String username = (String) values.get(XmppSettings.KEY_USERNAME);
        final String password = managed.getString(XmppSettings.KEY_PASSWORD);
        final boolean managesLogin = username != null && password != null && !password.isEmpty();
        if (managesLogin && !values.containsKey(XmppSettings.KEY_USE_TAK_CREDENTIALS)) {
            // the login is what the MDM sets it for: not the TAK credentials, the default
            values.put(XmppSettings.KEY_USE_TAK_CREDENTIALS, Boolean.FALSE);
        } else if (managesLogin
                && Boolean.TRUE.equals(values.get(XmppSettings.KEY_USE_TAK_CREDENTIALS))) {
            Log.w(TAG, "the managed XMPP login is unused: the TAK server credentials are on");
        }

        ServerIdentity server = null;
        if (values.containsKey(XmppSettings.KEY_DOMAIN)) {
            for (final String key : SERVER_KEYS) {
                if (!values.containsKey(key)) {
                    values.put(key, DEFAULT);
                }
            }
            server = serverOf(values, ca);
        }

        final boolean savedLogin = managesLogin && XmppSettings.ensureLogin(username, password);
        if (savedLogin) {
            Log.i(TAG, "XMPP login set by the device management");
        }
        return new AppConfig(values, managesLogin, server, savedLogin);
    }

    private static Object parse(final Type type, final Object raw, final String text) {
        switch (type) {
            case BOOLEAN:
                if (raw instanceof Boolean) {
                    return raw;
                }
                return "true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)
                        ? Boolean.valueOf(text.toLowerCase(Locale.ROOT)) : null;
            case PORT:
                try {
                    final int port = Integer.parseInt(text);
                    return port > 0 && port < 65536 ? String.valueOf(port) : null;
                } catch (final NumberFormatException e) {
                    // the caller logs the invalid value
                    return null;
                }
            case CHANNEL_DISCOVERY:
                for (final XmppSettings.ChannelDiscovery c
                        : XmppSettings.ChannelDiscovery.values()) {
                    if (c.value.equalsIgnoreCase(text)) {
                        return c.value;
                    }
                }
                return null;
            case STRING:
            default:
                return text;
        }
    }

    /**
     * The server the managed values connect to: what {@link ServerIdentity#of} would read.
     * Without a managed TAK server, its TAK server is null: any.
     */
    private static ServerIdentity serverOf(final Map<String, Object> values, final File ca) {
        final Object host = values.get(XmppSettings.KEY_HOST);
        final Object port = values.get(XmppSettings.KEY_PORT);
        final Object takServer = values.get(XmppSettings.KEY_TAK_SERVER);
        final boolean takCredentials = bool(values, XmppSettings.KEY_USE_TAK_CREDENTIALS, true);
        return ServerIdentity.of(takCredentials,
                takCredentials && takServer instanceof String ? (String) takServer : null,
                (String) values.get(XmppSettings.KEY_DOMAIN),
                host instanceof String ? (String) host : null,
                port instanceof String ? Integer.parseInt((String) port)
                        : XmppSettings.DEFAULT_PORT,
                bool(values, XmppSettings.KEY_USE_TAK_TRUSTSTORE, true),
                bool(values, XmppSettings.KEY_USE_ANDROID_CA_STORE, false),
                ca == null ? null : ca.getAbsolutePath(),
                ca == null ? null : ServerIdentity.sha256(ca.getAbsolutePath()));
    }

    private static boolean bool(final Map<String, Object> values, final String key,
            final boolean def) {
        final Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : def;
    }

    /**
     * Writes the certificates of {@code text} to a file named after its contents, so a new one
     * doesn't change the file the current settings trust. Null without a valid certificate.
     */
    private static File storeCertificate(final Context atakContext, final String text,
            final AppConfig previous) {
        final byte[] pem = text == null || text.trim().isEmpty() ? null : certificatePem(text);
        if (text != null && !text.trim().isEmpty() && pem == null) {
            Log.w(TAG, "invalid managed value for " + KEY_TRUSTED_CA_CERTIFICATE);
        }
        final String name = pem == null ? null
                : CA_FILE_PREFIX + ServerIdentity.sha256(pem).substring(0, 16) + ".pem";
        final File dir = PrivateFiles.dir(atakContext);
        // the one the preferences may still name until the new configuration is applied
        final Object inUse = previous.values.get(XmppSettings.KEY_TRUSTED_CA);
        final File[] old = dir.listFiles((d, n) -> n.startsWith(CA_FILE_PREFIX)
                && !n.equals(name) && !(inUse instanceof String
                        && new File(d, n).getAbsolutePath().equals(inUse)));
        if (old != null) {
            for (final File f : old) {
                if (!f.delete()) {
                    Log.w(TAG, "unable to delete an old managed CA file");
                }
            }
        }
        if (name == null) {
            return null;
        }
        final File file = new File(dir, name);
        if (!file.isFile() && !PrivateFiles.write(atakContext, name,
                new String(pem, StandardCharsets.US_ASCII))) {
            return null;
        }
        return file;
    }

    /**
     * PEM for the certificates in {@code text}: PEM blocks, however an MDM's text field broke
     * their lines, or one certificate in Base64 DER. Null if any isn't a certificate.
     */
    static byte[] certificatePem(final String text) {
        final List<String> bodies = new ArrayList<>();
        final Matcher m = PEM.matcher(text);
        while (m.find()) {
            bodies.add(m.group(1));
        }
        if (bodies.isEmpty()) {
            bodies.add(text);
        }
        final StringBuilder pem = new StringBuilder();
        try {
            final CertificateFactory factory = CertificateFactory.getInstance("X.509");
            for (final String body : bodies) {
                final byte[] der = Base64.decode(
                        NOT_BASE64.matcher(body).replaceAll(""), Base64.DEFAULT);
                factory.generateCertificate(new ByteArrayInputStream(der));
                final String base64 = Base64.encodeToString(der, Base64.NO_WRAP);
                pem.append("-----BEGIN CERTIFICATE-----\n");
                for (int i = 0; i < base64.length(); i += 64) {
                    pem.append(base64, i, Math.min(base64.length(), i + 64)).append('\n');
                }
                pem.append("-----END CERTIFICATE-----\n");
            }
        } catch (final IllegalArgumentException | CertificateException e) {
            Log.w(TAG, "not a certificate", e);
            return null;
        }
        return pem.toString().getBytes(StandardCharsets.US_ASCII);
    }

    // --- the configuration last applied, which applies again before the next read ---

    /** The configuration last saved, or {@link #NONE}. Disk work. */
    public static AppConfig restore(final Context atakContext) {
        final String saved = PrivateFiles.read(atakContext, SNAPSHOT);
        if (saved == null) {
            return NONE;
        }
        try {
            final JSONObject o = new JSONObject(saved);
            final JSONObject v = o.getJSONObject("values");
            final Map<String, Object> values = new HashMap<>();
            for (final Iterator<String> keys = v.keys(); keys.hasNext(); ) {
                final String key = keys.next();
                final Object value = v.get(key);
                values.put(key, value == JSONObject.NULL ? DEFAULT
                        : value instanceof Boolean ? value : String.valueOf(value));
            }
            return new AppConfig(values, o.optBoolean("login"),
                    ServerIdentity.parse(o.optString("server", null)), false);
        } catch (final JSONException e) {
            Log.w(TAG, "unreadable saved managed configuration", e);
            return NONE;
        }
    }

    /** Disk work; a few hundred bytes. */
    public void save(final Context atakContext) {
        try {
            final JSONObject v = new JSONObject();
            for (final Map.Entry<String, Object> e : values.entrySet()) {
                v.put(e.getKey(), e.getValue() == DEFAULT ? JSONObject.NULL : e.getValue());
            }
            final JSONObject o = new JSONObject().put("values", v).put("login", managesLogin);
            if (server != null) {
                o.put("server", server.serialize());
            }
            if (!PrivateFiles.write(atakContext, SNAPSHOT, o.toString())) {
                Log.w(TAG, "the managed configuration applies only once read again");
            }
        } catch (final JSONException e) {
            // only for non-finite numbers, which there are none of
            throw new IllegalStateException(e);
        }
    }

    // --- in ATAK's preferences ---

    /**
     * Writes the managed values into ATAK's preferences, and puts the keys {@code previous}
     * managed and this doesn't back to their defaults. Main thread.
     */
    public void applyTo(final SharedPreferences prefs, final AppConfig previous) {
        final SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (final String key : previous.values.keySet()) {
            if (!values.containsKey(key) && prefs.contains(key)) {
                editor.remove(key);
                changed = true;
            }
        }
        for (final Map.Entry<String, Object> e : values.entrySet()) {
            if (!matches(prefs, e.getKey(), e.getValue())) {
                put(editor, e.getKey(), e.getValue());
                changed = true;
            }
        }
        if (changed) {
            editor.apply();
        }
    }

    /** Puts back the managed value of {@code key} if something changed it; true if it did. */
    public boolean enforce(final SharedPreferences prefs, final String key) {
        final Object value = values.get(key);
        if (value == null || matches(prefs, key, value)) {
            return false;
        }
        Log.i(TAG, key + " is set by the device management: change undone");
        final SharedPreferences.Editor editor = prefs.edit();
        put(editor, key, value);
        editor.apply();
        return true;
    }

    private static boolean matches(final SharedPreferences prefs, final String key,
            final Object value) {
        if (value == DEFAULT) {
            return !prefs.contains(key);
        }
        try {
            if (value instanceof Boolean) {
                return prefs.contains(key) && prefs.getBoolean(key, false) == (Boolean) value;
            }
            return value.equals(prefs.getString(key, null));
        } catch (final ClassCastException e) {
            // another type, e.g. from a .pref file: rewritten
            return false;
        }
    }

    private static void put(final SharedPreferences.Editor editor, final String key,
            final Object value) {
        if (value == DEFAULT) {
            editor.remove(key);
        } else if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else {
            editor.putString(key, (String) value);
        }
    }

    // --- state ---

    public boolean isEmpty() {
        return values.isEmpty() && !managesLogin;
    }

    /** Whether the MDM sets this preference, so the user can't change it. */
    public boolean isManaged(final String key) {
        return values.containsKey(key);
    }

    /** Whether the MDM sets the XMPP login: a .pref file's login is ignored. */
    public boolean managesLogin() {
        return managesLogin;
    }

    /** The server the MDM's settings connect to; or null. */
    public ServerIdentity server() {
        return server;
    }

    /**
     * Whether the MDM's settings connect to {@code server}, which needs no approval then. Any
     * TAK server's credentials, unless the MDM names the TAK server.
     */
    public boolean approves(final ServerIdentity server) {
        return this.server != null && this.server.sameServer(server)
                && (this.server.takServer == null
                        || this.server.takServer.equals(server.takServer));
    }

    /** Whether reading it stored a new login, which the account then uses. */
    public boolean savedLogin() {
        return savedLogin;
    }

    @Override
    public boolean equals(final Object o) {
        if (!(o instanceof AppConfig)) {
            return false;
        }
        final AppConfig other = (AppConfig) o;
        return managesLogin == other.managesLogin && values.equals(other.values)
                && Objects.equals(server, other.server);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values, managesLogin, server);
    }
}
