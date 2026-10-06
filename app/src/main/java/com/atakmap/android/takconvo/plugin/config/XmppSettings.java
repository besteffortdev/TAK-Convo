package com.atakmap.android.takconvo.plugin.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.VisibleForTesting;

import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.comms.NetConnectString;
import com.atakmap.comms.TAKServer;
import com.atakmap.comms.TAKServerListener;
import com.atakmap.coremap.log.Log;
import com.atakmap.net.AtakAuthenticationCredentials;
import com.atakmap.net.AtakAuthenticationDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    /** The host of the TAK server whose credentials to use; empty: chosen, see load(). */
    public static final String KEY_TAK_SERVER = "takconvo_xmpp_tak_server";
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
    /** Whether new messages notify (default false: the Conversations app beside ATAK does). */
    public static final String KEY_NOTIFICATION_MESSAGES = "takconvo_notification_messages";
    public static final String KEY_NOTIFICATION_SOUND = "takconvo_notification_sound";
    public static final String KEY_NOTIFICATION_VIBRATE = "takconvo_notification_vibrate";
    /** Whether the quick message buttons show (default false). */
    public static final String KEY_SHOW_QUICK_MESSAGES = "takconvo_show_quick_messages";
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
        INVALID_JID,
        /** The server settings changed, e.g. by a .pref file: the user confirms first. */
        SERVER_UNCONFIRMED
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

    /** Reads ATAK's credential store: not on the main thread. */
    public static XmppSettings load(final Context atakContext) {
        // one copy of ATAK's preferences, not one per key
        final Map<String, ?> prefs = AtakPreferences.getInstance(atakContext).getSharedPrefs()
                .getAll();
        final boolean enabled = parseBoolean(value(prefs, KEY_ENABLED), true);
        final String domain = trimToNull(value(prefs, KEY_DOMAIN));
        final String host = trimToNull(value(prefs, KEY_HOST));
        final int port = parsePort(value(prefs, KEY_PORT));
        final Trust trust = new Trust(
                parseBoolean(value(prefs, KEY_USE_TAK_TRUSTSTORE), true),
                parseBoolean(value(prefs, KEY_USE_ANDROID_CA_STORE), false),
                trimToNull(value(prefs, KEY_TRUSTED_CA)));
        final boolean useTak = parseBoolean(value(prefs, KEY_USE_TAK_CREDENTIALS), true);
        final String takServer = trimToNull(value(prefs, KEY_TAK_SERVER));
        final String suggested = trimToNull(value(prefs, KEY_USERNAME));
        final Channels channels = new Channels(
                ChannelDiscovery.parse(value(prefs, KEY_CHANNEL_DISCOVERY)),
                trimToNull(value(prefs, KEY_CHANNEL_SERVER)));

        if (useTak) {
            // no fallback to the XMPP login: the TAK credentials often come later, and
            // switching identities would provision the wrong account
            final TakCredentials tak = findTakCredentials(takServer, domain, host);
            if (tak != null) {
                return new XmppSettings(enabled, domain, host, port, tak.username, tak.password,
                        trust, true, CredentialSource.TAK_SERVER, tak.server, suggested,
                        channels);
            }
            Log.d(TAG, takServer != null ? "no credentials of the TAK server set yet"
                    : "no TAK server credentials available yet");
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

    /** Stores the login unless the credential store holds it already; true if it stored it. */
    public static boolean ensureLogin(final String username, final String password) {
        final AtakAuthenticationCredentials login =
                AtakAuthenticationDatabase.getCredentials(CREDENTIALS_TYPE);
        if (login != null && username.equals(login.username)
                && password.equals(login.password)) {
            return false;
        }
        saveLogin(username, password);
        return true;
    }

    /**
     * Moves a login a .pref file provisioned ({@link #KEY_USERNAME} and {@link #KEY_PASSWORD})
     * into ATAK's credential store; not when {@code store} is false (the MDM sets the login).
     * The password is removed from the preferences either way.
     */
    public static void importLogin(final SharedPreferences prefs, final boolean store) {
        final String password = getString(prefs, KEY_PASSWORD);
        if (password == null) {
            return;
        }
        final String username = trimToNull(getString(prefs, KEY_USERNAME));
        if (!store) {
            Log.w(TAG, "ignoring " + KEY_PASSWORD + ": the device management sets the login");
        } else if (username != null && !password.isEmpty()) {
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
        return getBoolean(prefs, KEY_USE_CALLSIGN, true);
    }

    /** This device's ATAK callsign, or null. */
    public static String atakCallsign(final SharedPreferences prefs) {
        return trimToNull(getString(prefs, KEY_ATAK_CALLSIGN));
    }

    public static boolean notificationMessages(final SharedPreferences prefs) {
        return getBoolean(prefs, KEY_NOTIFICATION_MESSAGES, false);
    }

    public static boolean notificationSound(final SharedPreferences prefs) {
        return getBoolean(prefs, KEY_NOTIFICATION_SOUND, true);
    }

    public static boolean notificationVibrate(final SharedPreferences prefs) {
        return getBoolean(prefs, KEY_NOTIFICATION_VIBRATE, true);
    }

    public static boolean showQuickMessages(final SharedPreferences prefs) {
        return getBoolean(prefs, KEY_SHOW_QUICK_MESSAGES, false);
    }

    /** The quick messages' texts, separated by {@code |}, or {@code def}. */
    public static String quickMessages(final SharedPreferences prefs, final String def) {
        final String value = getString(prefs, KEY_QUICK_MESSAGES);
        return value == null ? def : value;
    }

    /** Stores .pref string booleans as Booleans, which the settings' check boxes need. */
    public static void normalize(final SharedPreferences prefs) {
        final Map<String, ?> all = prefs.getAll();
        final SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (final String key : new String[] {KEY_ENABLED, KEY_USE_TAK_CREDENTIALS,
                KEY_USE_TAK_TRUSTSTORE, KEY_USE_ANDROID_CA_STORE, KEY_USE_CALLSIGN,
                KEY_NOTIFICATION_MESSAGES, KEY_NOTIFICATION_SOUND, KEY_NOTIFICATION_VIBRATE,
                KEY_SHOW_QUICK_MESSAGES}) {
            final Object value = all.get(key);
            if (value != null && !(value instanceof Boolean)) {
                editor.putBoolean(key, Boolean.parseBoolean(String.valueOf(value).trim()));
                changed = true;
            }
        }
        final Object port = all.get(KEY_PORT);
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

    /** The XMPP address the credentials sign in as, or null. */
    public String jid() {
        return jid(username, domain, credentialSource == CredentialSource.TAK_SERVER);
    }

    /**
     * username@domain, or the username itself if it has a domain. A TAK username isn't an XMPP
     * address, though: with a domain set, user@corp.example (a Windows login, say) is
     * user@domain, as a TAK username without '@' is. Null without a domain to add.
     */
    static String jid(final String username, final String domain, final boolean takUsername) {
        if (username == null) {
            return null;
        }
        final int at = username.lastIndexOf('@');
        if (at < 0) {
            return domain == null ? null : username + "@" + domain;
        }
        if (takUsername && domain != null && !username.substring(at + 1).equalsIgnoreCase(domain)) {
            return username.substring(0, at) + "@" + domain;
        }
        return username;
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

    /**
     * Credentials of the TAK server {@code named} ({@link #KEY_TAK_SERVER}), and of no other:
     * another server's password must not go to the XMPP server. Without a name, of a TAK
     * server on the XMPP server's domain, else of another; connected ones first in each case.
     * The first connected server isn't always the same one.
     */
    private static TakCredentials findTakCredentials(final String named, final String domain,
            final String xmppHost) {
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
        final List<TAKServer> preferred = new ArrayList<>();
        final List<TAKServer> others = new ArrayList<>();
        for (final TAKServer s : candidates) {
            final String host = hostOf(s);
            if (named != null ? named.equalsIgnoreCase(host)
                    : sameDomain(host, domain) || sameDomain(host, xmppHost)) {
                preferred.add(s);
            } else if (named == null) {
                others.add(s);
            }
        }
        preferred.addAll(others);
        for (final TAKServer server : preferred) {
            final TakCredentials creds = credentialsFor(server);
            if (creds != null) {
                return creds;
            }
        }
        return null;
    }

    /**
     * Whether a TAK server's host is {@code name}, in its domain, or beside it in the same
     * domain (tak.example.org and xmpp.example.org).
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    static boolean sameDomain(final String host, final String name) {
        if (host == null || name == null) {
            return false;
        }
        final String h = host.toLowerCase(Locale.ROOT);
        final String n = name.toLowerCase(Locale.ROOT);
        if (h.equals(n)) {
            return true;
        }
        if (isIpAddress(h) || isIpAddress(n)) {
            return false;
        }
        final String parent = parentDomain(h);
        return h.endsWith("." + n) || n.endsWith("." + h)
                || (parent != null && parent.equals(parentDomain(n)));
    }

    /** example.org for tak.example.org; null for a name of two labels or fewer. */
    private static String parentDomain(final String host) {
        final int dot = host.indexOf('.');
        final String parent = dot < 0 ? null : host.substring(dot + 1);
        return parent != null && parent.indexOf('.') > 0 ? parent : null;
    }

    private static boolean isIpAddress(final String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }

    /** The hosts of ATAK's TAK server connections, to choose whose credentials to use. */
    public static List<String> takServerHosts() {
        final List<String> hosts = new ArrayList<>();
        final TAKServerListener listener = TAKServerListener.getInstance();
        final TAKServer[] all = listener == null ? null : listener.getServers();
        if (all != null) {
            for (final TAKServer s : all) {
                final String host = s == null ? null : hostOf(s);
                if (host != null && !hosts.contains(host)) {
                    hosts.add(host);
                }
            }
        }
        return hosts;
    }

    private static String hostOf(final TAKServer server) {
        final NetConnectString ncs = NetConnectString.fromString(server.getConnectString());
        return ncs != null ? ncs.getHost() : null;
    }

    private static TakCredentials credentialsFor(final TAKServer server) {
        final String connectString = server.getConnectString();
        final String host = hostOf(server);
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

    private static String value(final Map<String, ?> prefs, final String key) {
        final Object value = prefs.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** One key, without copying all of ATAK's preferences unless it isn't a string. */
    private static String getString(final SharedPreferences prefs, final String key) {
        try {
            return prefs.getString(key, null);
        } catch (final ClassCastException e) {
            // typed by a .pref file (a Boolean, a number): rare
            return value(prefs.getAll(), key);
        }
    }

    private static boolean getBoolean(final SharedPreferences prefs, final String key,
            final boolean def) {
        try {
            return prefs.getBoolean(key, def);
        } catch (final ClassCastException e) {
            // a .pref string, until normalize() stores it as a Boolean
            return parseBoolean(value(prefs.getAll(), key), def);
        }
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
