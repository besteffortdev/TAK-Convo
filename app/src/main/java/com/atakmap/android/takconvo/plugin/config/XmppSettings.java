package com.atakmap.android.takconvo.plugin.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.comms.NetConnectString;
import com.atakmap.comms.TAKServer;
import com.atakmap.comms.TAKServerListener;
import com.atakmap.coremap.log.Log;
import com.atakmap.net.AtakAuthenticationCredentials;
import com.atakmap.net.AtakAuthenticationDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * XMPP account settings, read from ATAK's shared preferences so they can be provisioned with a
 * {@code .pref} file (or a mission package containing one) and edited in the plugin's tool
 * preferences. By default the TAK server's username and password are reused for XMPP.
 *
 * <pre>
 * takconvo_xmpp_enabled               connect at all (Boolean, default true)
 * takconvo_xmpp_domain                XMPP domain, e.g. xmpp.example.org
 * takconvo_xmpp_host                  host to connect to, if not resolvable from the domain
 * takconvo_xmpp_port                  port for takconvo_xmpp_host (default 5222)
 * takconvo_xmpp_use_tak_credentials   reuse the TAK server username/password (Boolean, default true)
 * takconvo_xmpp_username              suggested username for the XMPP login screen
 * takconvo_xmpp_use_tak_truststore    trust the CAs of the TAK server truststores (Boolean, default true)
 * takconvo_xmpp_use_android_ca_store  trust the device's CA store, including user and MDM
 *                                     installed CAs (Boolean, default false)
 * takconvo_xmpp_trusted_ca            path to a PEM/DER CA certificate to trust for XMPP TLS
 * </pre>
 *
 * <p>Public CAs are always trusted, see {@link TrustSources} for the others.
 *
 * <p>Credentials entered on the login screen are kept in ATAK's encrypted credential store
 * ({@link #CREDENTIALS_TYPE}), never in the preferences.
 */
public final class XmppSettings {

    private static final String TAG = "TakConvo.Settings";

    public static final String KEY_ENABLED = "takconvo_xmpp_enabled";
    public static final String KEY_DOMAIN = "takconvo_xmpp_domain";
    public static final String KEY_HOST = "takconvo_xmpp_host";
    public static final String KEY_PORT = "takconvo_xmpp_port";
    public static final String KEY_USE_TAK_CREDENTIALS = "takconvo_xmpp_use_tak_credentials";
    public static final String KEY_USERNAME = "takconvo_xmpp_username";
    public static final String KEY_TRUSTED_CA = "takconvo_xmpp_trusted_ca";
    public static final String KEY_USE_TAK_TRUSTSTORE = "takconvo_xmpp_use_tak_truststore";
    public static final String KEY_USE_ANDROID_CA_STORE = "takconvo_xmpp_use_android_ca_store";

    public static final String KEY_PREFIX = "takconvo_xmpp_";

    /** Type of the XMPP login in {@link AtakAuthenticationDatabase}. */
    public static final String CREDENTIALS_TYPE = "takconvo.xmpp";

    public static final int DEFAULT_PORT = 5222;

    /** Where the credentials came from. */
    public enum CredentialSource {
        TAK_SERVER,
        /** entered on the XMPP login screen */
        LOGIN,
        NONE
    }

    /** Why no account can be provisioned. */
    public enum Problem {
        DISABLED,
        NO_TAK_CREDENTIALS,
        NOT_SIGNED_IN,
        NO_DOMAIN,
        INVALID_JID
    }

    public final boolean enabled;
    public final String domain;
    public final String host;
    public final int port;
    public final String username;
    public final String password;
    public final String trustedCaPath;
    public final boolean useTakTrustStore;
    public final boolean useAndroidCaStore;
    public final boolean usesTakCredentials;
    public final CredentialSource credentialSource;
    /** the TAK server the credentials belong to, for {@link CredentialSource#TAK_SERVER} */
    public final String credentialOrigin;
    /** provisioned username suggestion for the login screen, may be null */
    public final String suggestedUsername;

    private XmppSettings(
            final boolean enabled,
            final String domain,
            final String host,
            final int port,
            final String username,
            final String password,
            final Trust trust,
            final boolean usesTakCredentials,
            final CredentialSource credentialSource,
            final String credentialOrigin,
            final String suggestedUsername) {
        this.enabled = enabled;
        this.domain = domain;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.trustedCaPath = trust.caPath;
        this.useTakTrustStore = trust.takTrustStore;
        this.useAndroidCaStore = trust.androidCaStore;
        this.usesTakCredentials = usesTakCredentials;
        this.credentialSource = credentialSource;
        this.credentialOrigin = credentialOrigin;
        this.suggestedUsername = suggestedUsername;
    }

    public static XmppSettings load(final Context atakContext) {
        final SharedPreferences prefs = AtakPreferences.getInstance(atakContext).getSharedPrefs();
        final boolean enabled = parseBoolean(getString(prefs, KEY_ENABLED), true);
        final String domain = trimToNull(getString(prefs, KEY_DOMAIN));
        final String host = trimToNull(getString(prefs, KEY_HOST));
        final int port = parsePort(getString(prefs, KEY_PORT));
        final Trust trust = new Trust(
                parseBoolean(getString(prefs, KEY_USE_TAK_TRUSTSTORE), true),
                parseBoolean(getString(prefs, KEY_USE_ANDROID_CA_STORE), false),
                trimToNull(getString(prefs, KEY_TRUSTED_CA)));
        final boolean useTak = parseBoolean(getString(prefs, KEY_USE_TAK_CREDENTIALS), true);
        final String suggested = trimToNull(getString(prefs, KEY_USERNAME));

        if (useTak) {
            // no fallback to the XMPP login: at ATAK startup the TAK server credentials are
            // often not available yet, and silently switching identities would provision the
            // wrong account
            final TakCredentials tak = findTakCredentials();
            if (tak != null) {
                return new XmppSettings(enabled, domain, host, port, tak.username, tak.password,
                        trust, true, CredentialSource.TAK_SERVER, tak.server, suggested);
            }
            Log.d(TAG, "no TAK server credentials available yet");
            return new XmppSettings(enabled, domain, host, port, null, null, trust,
                    true, CredentialSource.NONE, null, suggested);
        }
        final AtakAuthenticationCredentials login =
                AtakAuthenticationDatabase.getCredentials(CREDENTIALS_TYPE);
        if (login != null && !TextUtils.isEmpty(login.username)
                && !TextUtils.isEmpty(login.password)) {
            return new XmppSettings(enabled, domain, host, port, login.username.trim(),
                    login.password, trust, false, CredentialSource.LOGIN, null, suggested);
        }
        return new XmppSettings(enabled, domain, host, port, null, null, trust,
                false, CredentialSource.NONE, null, suggested);
    }

    /** Which CA sources to trust, see {@link TrustSources}. */
    private static final class Trust {
        final boolean takTrustStore;
        final boolean androidCaStore;
        final String caPath;

        Trust(final boolean takTrustStore, final boolean androidCaStore, final String caPath) {
            this.takTrustStore = takTrustStore;
            this.androidCaStore = androidCaStore;
            this.caPath = caPath;
        }
    }

    /** Stores the XMPP login (username or full JID) in ATAK's credential store. */
    public static void saveLogin(final String username, final String password) {
        AtakAuthenticationDatabase.saveCredentials(CREDENTIALS_TYPE, username, password, false);
    }

    public static void clearLogin() {
        AtakAuthenticationDatabase.delete(CREDENTIALS_TYPE, CREDENTIALS_TYPE);
    }

    /** @return the username of the stored XMPP login, or null */
    public static String getLoginUsername() {
        final AtakAuthenticationCredentials login =
                AtakAuthenticationDatabase.getCredentials(CREDENTIALS_TYPE);
        return login == null ? null : trimToNull(login.username);
    }

    /**
     * Stores boolean values as Booleans. {@code .pref} files may carry them as strings, and the
     * check boxes of the settings screen fail on those.
     */
    public static void normalize(final SharedPreferences prefs) {
        final SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (final String key : new String[] {KEY_ENABLED, KEY_USE_TAK_CREDENTIALS,
                KEY_USE_TAK_TRUSTSTORE, KEY_USE_ANDROID_CA_STORE}) {
            final Object value = prefs.getAll().get(key);
            if (value != null && !(value instanceof Boolean)) {
                editor.putBoolean(key, Boolean.parseBoolean(String.valueOf(value).trim()));
                changed = true;
            }
        }
        final Object port = prefs.getAll().get(KEY_PORT);
        if (port != null && !(port instanceof String)) {
            editor.putString(KEY_PORT, String.valueOf(port));
            changed = true;
        }
        if (changed) {
            editor.apply();
        }
    }

    /** @return null if an account can be provisioned, otherwise why not */
    public Problem problem() {
        if (!enabled) {
            return Problem.DISABLED;
        }
        if (username == null || TextUtils.isEmpty(password)) {
            return usesTakCredentials ? Problem.NO_TAK_CREDENTIALS : Problem.NOT_SIGNED_IN;
        }
        if (domain == null && !username.contains("@")) {
            return Problem.NO_DOMAIN;
        }
        return null;
    }

    /** Bare JID: the username as-is if it already contains a domain, otherwise user@domain. */
    public String jid() {
        if (username == null) {
            return null;
        }
        if (username.contains("@")) {
            return username;
        }
        return domain == null ? null : username + "@" + domain;
    }

    // --- TAK server credentials ---

    private static final class TakCredentials {
        final String server;
        final String username;
        final String password;

        TakCredentials(final String server, final String username, final String password) {
            this.server = server;
            this.username = username;
            this.password = password;
        }
    }

    /** Credentials of the first connected TAK server, else of the first enabled one. */
    private static TakCredentials findTakCredentials() {
        final TAKServerListener listener = TAKServerListener.getInstance();
        if (listener == null) {
            return null;
        }
        final List<TAKServer> candidates = new ArrayList<>();
        addAll(candidates, listener.getConnectedServers());
        final TAKServer[] all = listener.getServers();
        if (all != null) {
            for (final TAKServer s : all) {
                if (s != null && s.isEnabled() && !candidates.contains(s)) {
                    candidates.add(s);
                }
            }
        }
        for (final TAKServer server : candidates) {
            final TakCredentials creds = credentialsFor(server);
            if (creds != null) {
                return creds;
            }
        }
        return null;
    }

    private static TakCredentials credentialsFor(final TAKServer server) {
        final String connectString = server.getConnectString();
        final NetConnectString ncs = NetConnectString.fromString(connectString);
        final String host = ncs != null ? ncs.getHost() : null;
        if (host != null) {
            // ATAK keeps the password in its encrypted credential store, keyed by host
            final AtakAuthenticationCredentials stored = AtakAuthenticationDatabase
                    .getCredentials(AtakAuthenticationCredentials.TYPE_COT_SERVICE, host);
            if (stored != null && !TextUtils.isEmpty(stored.username)
                    && !TextUtils.isEmpty(stored.password)) {
                return new TakCredentials(host, stored.username, stored.password);
            }
        }
        final String user = server.getUsername();
        final String pass = server.getPassword();
        if (!TextUtils.isEmpty(user) && !TextUtils.isEmpty(pass)) {
            return new TakCredentials(host != null ? host : connectString, user, pass);
        }
        return null;
    }

    private static void addAll(final List<TAKServer> list, final TAKServer[] servers) {
        if (servers == null) {
            return;
        }
        for (final TAKServer s : servers) {
            if (s != null) {
                list.add(s);
            }
        }
    }

    // --- .pref values arrive as strings, but may also be typed ---

    private static String getString(final SharedPreferences prefs, final String key) {
        final Object value = prefs.getAll().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static int parsePort(final String value) {
        try {
            return value == null || value.trim().isEmpty()
                    ? DEFAULT_PORT : Integer.parseInt(value.trim());
        } catch (final NumberFormatException e) {
            Log.w(TAG, "invalid " + KEY_PORT + ": " + value);
            return DEFAULT_PORT;
        }
    }

    private static boolean parseBoolean(final String value, final boolean def) {
        return value == null ? def : Boolean.parseBoolean(value.trim());
    }

    private static String trimToNull(final String value) {
        if (value == null) {
            return null;
        }
        final String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    @Override
    public String toString() {
        return "XmppSettings{enabled=" + enabled + ", jid=" + jid() + ", host=" + host
                + ", port=" + port + ", credentials=" + credentialSource
                + (credentialOrigin != null ? " (" + credentialOrigin + ")" : "")
                + ", trust=" + (useTakTrustStore ? "tak " : "")
                + (useAndroidCaStore ? "android " : "")
                + (trustedCaPath != null ? trustedCaPath : "") + "}";
    }
}
