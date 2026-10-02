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
 * XMPP settings from ATAK's preferences, so a {@code .pref} file can provision them; the keys
 * are described in provisioning/takconvo-template.pref. The XMPP login lives in ATAK's
 * encrypted credential store ({@link #CREDENTIALS_TYPE}); a password provisioned in the
 * preferences is moved there ({@link #importLogin}).
 */
public final class XmppSettings {

    private static final String TAG = "TakConvo.Settings";

    public static final String KEY_ENABLED = "takconvo_xmpp_enabled";
    public static final String KEY_DOMAIN = "takconvo_xmpp_domain";
    public static final String KEY_HOST = "takconvo_xmpp_host";
    public static final String KEY_PORT = "takconvo_xmpp_port";
    public static final String KEY_USE_TAK_CREDENTIALS = "takconvo_xmpp_use_tak_credentials";
    public static final String KEY_USERNAME = "takconvo_xmpp_username";
    /** Only in transit: moved to the credential store, see {@link #importLogin}. */
    public static final String KEY_PASSWORD = "takconvo_xmpp_password";
    public static final String KEY_TRUSTED_CA = "takconvo_xmpp_trusted_ca";
    public static final String KEY_USE_TAK_TRUSTSTORE = "takconvo_xmpp_use_tak_truststore";
    public static final String KEY_USE_ANDROID_CA_STORE = "takconvo_xmpp_use_android_ca_store";
    public static final String KEY_CHANNEL_DISCOVERY = "takconvo_xmpp_channel_discovery";
    public static final String KEY_CHANNEL_SERVER = "takconvo_xmpp_channel_server";
    public static final String KEY_USE_CALLSIGN = "takconvo_xmpp_use_callsign";
    // not takconvo_xmpp_*: no provisioning on change
    public static final String KEY_NOTIFICATION_SOUND = "takconvo_notification_sound";
    public static final String KEY_NOTIFICATION_VIBRATE = "takconvo_notification_vibrate";
    /** The quick message buttons' texts, separated by {@code |}. */
    public static final String KEY_QUICK_MESSAGES = "takconvo_quick_messages";
    /** ATAK's preference for this device's callsign. */
    public static final String KEY_ATAK_CALLSIGN = "locationCallsign";

    public static final String KEY_PREFIX = "takconvo_xmpp_";

    /** Type of the XMPP login in {@link AtakAuthenticationDatabase}. */
    public static final String CREDENTIALS_TYPE = "takconvo.xmpp";

    public static final int DEFAULT_PORT = 5222;

    public enum CredentialSource {
        TAK_SERVER,
        /** Entered on the XMPP login screen. */
        LOGIN,
        NONE
    }

    /** Where "Discover channels" looks. */
    public enum ChannelDiscovery {
        /** The account's server. */
        XMPP_SERVER("xmpp_server"),
        /** {@link #channelServer}. */
        SERVER("server"),
        /** search.jabber.network. */
        PUBLIC("public");

        /** The preference value. */
        public final String value;

        ChannelDiscovery(final String value) {
            this.value = value;
        }

        public static ChannelDiscovery parse(final String value) {
            for (final ChannelDiscovery c : values()) {
                if (c.value.equalsIgnoreCase(value == null ? "" : value.trim())) {
                    return c;
                }
            }
            return XMPP_SERVER;
        }
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
    /** The TAK server the credentials belong to, or null. */
    public final String credentialOrigin;
    /** Username suggested on the login screen, or null. */
    public final String suggestedUsername;
    public final ChannelDiscovery channelDiscovery;
    /** For {@link ChannelDiscovery#SERVER}, or null. */
    public final String channelServer;

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
            final String suggestedUsername,
            final Channels channels) {
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
        this.channelDiscovery = channels.discovery;
        this.channelServer = channels.server;
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
        final Channels channels = new Channels(
                ChannelDiscovery.parse(getString(prefs, KEY_CHANNEL_DISCOVERY)),
                trimToNull(getString(prefs, KEY_CHANNEL_SERVER)));

        if (useTak) {
            // no fallback to the XMPP login: the TAK credentials often come later, and
            // switching identities would provision the wrong account
            final TakCredentials tak = findTakCredentials();
            if (tak != null) {
                return new XmppSettings(enabled, domain, host, port, tak.username, tak.password,
                        trust, true, CredentialSource.TAK_SERVER, tak.server, suggested,
                        channels);
            }
            Log.d(TAG, "no TAK server credentials available yet");
            return new XmppSettings(enabled, domain, host, port, null, null, trust,
                    true, CredentialSource.NONE, null, suggested, channels);
        }
        final AtakAuthenticationCredentials login =
                AtakAuthenticationDatabase.getCredentials(CREDENTIALS_TYPE);
        if (login != null && !TextUtils.isEmpty(login.username)
                && !TextUtils.isEmpty(login.password)) {
            return new XmppSettings(enabled, domain, host, port, login.username.trim(),
                    login.password, trust, false, CredentialSource.LOGIN, null, suggested,
                    channels);
        }
        return new XmppSettings(enabled, domain, host, port, null, null, trust,
                false, CredentialSource.NONE, null, suggested, channels);
    }

    private static final class Channels {
        final ChannelDiscovery discovery;
        final String server;

        Channels(final ChannelDiscovery discovery, final String server) {
            this.discovery = discovery;
            this.server = server;
        }
    }

    /** See {@link TrustSources}. */
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

    /** Stores the XMPP login (username or JID) in ATAK's credential store. */
    public static void saveLogin(final String username, final String password) {
        AtakAuthenticationDatabase.saveCredentials(CREDENTIALS_TYPE, username, password, false);
    }

    public static void clearLogin() {
        AtakAuthenticationDatabase.delete(CREDENTIALS_TYPE, CREDENTIALS_TYPE);
    }

    /**
     * Moves a login a .pref file provisioned ({@link #KEY_USERNAME} and {@link #KEY_PASSWORD})
     * into ATAK's credential store. The password is removed from the preferences either way.
     */
    public static void importLogin(final SharedPreferences prefs) {
        final String password = getString(prefs, KEY_PASSWORD);
        if (password == null) {
            return;
        }
        final String username = trimToNull(getString(prefs, KEY_USERNAME));
        if (username != null && !password.isEmpty()) {
            saveLogin(username, password);
            Log.i(TAG, "XMPP login moved from the preferences to ATAK's credential store");
        } else {
            Log.w(TAG, "ignoring " + KEY_PASSWORD + " without " + KEY_USERNAME);
        }
        prefs.edit().remove(KEY_PASSWORD).apply();
    }

    /** The stored XMPP login's username, or null. */
    public static String getLoginUsername() {
        final AtakAuthenticationCredentials login =
                AtakAuthenticationDatabase.getCredentials(CREDENTIALS_TYPE);
        return login == null ? null : trimToNull(login.username);
    }

    public static boolean usesCallsign(final SharedPreferences prefs) {
        return parseBoolean(getString(prefs, KEY_USE_CALLSIGN), true);
    }

    /** This device's ATAK callsign, or null. */
    public static String atakCallsign(final SharedPreferences prefs) {
        return trimToNull(getString(prefs, KEY_ATAK_CALLSIGN));
    }

    public static boolean notificationSound(final SharedPreferences prefs) {
        return parseBoolean(getString(prefs, KEY_NOTIFICATION_SOUND), true);
    }

    public static boolean notificationVibrate(final SharedPreferences prefs) {
        return parseBoolean(getString(prefs, KEY_NOTIFICATION_VIBRATE), true);
    }

    /** Stores .pref string booleans as Booleans, which the settings' check boxes need. */
    public static void normalize(final SharedPreferences prefs) {
        final SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (final String key : new String[] {KEY_ENABLED, KEY_USE_TAK_CREDENTIALS,
                KEY_USE_TAK_TRUSTSTORE, KEY_USE_ANDROID_CA_STORE, KEY_USE_CALLSIGN,
                KEY_NOTIFICATION_SOUND, KEY_NOTIFICATION_VIBRATE}) {
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

    /** Why no account can be provisioned, or null. */
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

    /** The username if it has a domain, otherwise username@domain. */
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
            // ATAK's encrypted credential store, keyed by host
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

    // --- .pref values are strings or typed ---

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

    /** Without the password. */
    @Override
    public String toString() {
        return "XmppSettings{enabled=" + enabled + ", jid=" + jid() + ", host=" + host
                + ", port=" + port + ", credentials=" + credentialSource
                + (credentialOrigin != null ? " (" + credentialOrigin + ")" : "")
                + ", trust=" + (useTakTrustStore ? "tak " : "")
                + (useAndroidCaStore ? "android " : "")
                + (trustedCaPath != null ? trustedCaPath : "")
                + ", channels=" + channelDiscovery.value
                + (channelDiscovery == ChannelDiscovery.SERVER ? " " + channelServer : "") + "}";
    }
}
