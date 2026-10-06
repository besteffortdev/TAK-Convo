package com.atakmap.android.takconvo.plugin.config;

import com.atakmap.coremap.log.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

/**
 * Where provisioning sends the credentials, and what it trusts there: the XMPP domain, host and
 * port, which credentials (the TAK server they belong to), the CA sources and the CA file's
 * contents. A {@code .pref} file or a data package can change all of them, so the engine
 * connects only to a server the user approved. See docs/03.
 */
public final class ServerIdentity {

    private static final String TAG = "TakConvo.Server";

    public final boolean takCredentials;
    /**
     * The TAK server whose credentials go there, lower case; null with an XMPP login, or while
     * there are no TAK credentials.
     */
    public final String takServer;
    public final String domain;
    /** Null: found through DNS. */
    public final String host;
    public final int port;
    public final boolean takTrustStore;
    public final boolean androidCaStore;
    /** The trusted CA file, or null. */
    public final String caPath;
    /** The CA file's SHA-256 in hex, "" if it can't be read; null without a CA file. */
    public final String caSha256;

    private ServerIdentity(final boolean takCredentials, final String takServer,
            final String domain, final String host, final int port, final boolean takTrustStore,
            final boolean androidCaStore, final String caPath, final String caSha256) {
        this.takCredentials = takCredentials;
        this.takServer = takServer;
        this.domain = domain;
        this.host = host;
        this.port = port;
        this.takTrustStore = takTrustStore;
        this.androidCaStore = androidCaStore;
        this.caPath = caPath;
        this.caSha256 = caSha256;
    }

    /** The server the settings connect to, or null without a domain. Reads the CA file. */
    public static ServerIdentity of(final XmppSettings settings) {
        final String jid = settings.jid();
        final String domain = jid != null ? domainOf(jid) : settings.domain;
        if (domain == null || domain.isEmpty()) {
            return null;
        }
        return of(settings.usesTakCredentials,
                settings.usesTakCredentials ? settings.credentialOrigin : null, domain,
                settings.host, settings.port, settings.useTakTrustStore,
                settings.useAndroidCaStore, settings.trustedCaPath,
                settings.trustedCaPath == null ? null : sha256(settings.trustedCaPath));
    }

    /**
     * The server of settings with these values: what a managed configuration approves. A null
     * {@code takServer} there stands for any TAK server ({@link #sameServer}).
     */
    static ServerIdentity of(final boolean takCredentials, final String takServer,
            final String domain, final String host, final int port, final boolean takTrustStore,
            final boolean androidCaStore, final String caPath, final String caSha256) {
        return new ServerIdentity(takCredentials, lowerCase(takServer),
                domain.toLowerCase(Locale.ROOT), lowerCase(host), port, takTrustStore,
                androidCaStore, caPath, caSha256);
    }

    private static String lowerCase(final String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    /** What {@link #serialize} wrote, or null. */
    public static ServerIdentity parse(final String serialized) {
        if (serialized == null) {
            return null;
        }
        try {
            final JSONObject o = new JSONObject(serialized);
            // no takServer: saved by a version before it, or without TAK credentials yet
            return new ServerIdentity(o.getBoolean("takCredentials"),
                    o.has("takServer") ? o.getString("takServer") : null, o.getString("domain"),
                    o.has("host") ? o.getString("host") : null, o.getInt("port"),
                    o.getBoolean("takTrustStore"), o.getBoolean("androidCaStore"),
                    o.has("caPath") ? o.getString("caPath") : null,
                    o.has("caSha256") ? o.getString("caSha256") : null);
        } catch (final JSONException e) {
            Log.w(TAG, "unreadable approved server", e);
            return null;
        }
    }

    public String serialize() {
        try {
            final JSONObject o = new JSONObject()
                    .put("takCredentials", takCredentials)
                    .put("domain", domain)
                    .put("port", port)
                    .put("takTrustStore", takTrustStore)
                    .put("androidCaStore", androidCaStore);
            // put(name, null) leaves the name out
            o.put("takServer", takServer).put("host", host).put("caPath", caPath)
                    .put("caSha256", caSha256);
            return o.toString();
        } catch (final JSONException e) {
            // only for non-finite numbers, which there are none of
            throw new IllegalStateException(e);
        }
    }

    /** {@code host:port}, or the port alone if not the default; empty for the defaults. */
    public String connection() {
        if (host != null) {
            return host + ":" + port;
        }
        return port == XmppSettings.DEFAULT_PORT ? "" : String.valueOf(port);
    }

    /** The domain of a JID: after the local part, before the resource. */
    private static String domainOf(final String jid) {
        final int slash = jid.indexOf('/');
        final String bare = slash < 0 ? jid : jid.substring(0, slash);
        return bare.substring(bare.indexOf('@') + 1);
    }

    /** The file's SHA-256 in hex, "" if it can't be read. */
    static String sha256(final String path) {
        try (InputStream in = new FileInputStream(path)) {
            final MessageDigest digest = sha256();
            final byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
            return hex(digest.digest());
        } catch (final IOException | SecurityException e) {
            // a file that appears later changes the identity
            Log.w(TAG, "unable to read the trusted CA file", e);
            return "";
        }
    }

    static String sha256(final byte[] data) {
        return hex(sha256().digest(data));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(final byte[] bytes) {
        final StringBuilder hex = new StringBuilder();
        for (final byte b : bytes) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        return hex.toString();
    }

    /** The same server and trust, whichever TAK server's credentials go there. */
    public boolean sameServer(final ServerIdentity other) {
        return other != null && takCredentials == other.takCredentials && port == other.port
                && takTrustStore == other.takTrustStore
                && androidCaStore == other.androidCaStore
                && domain.equals(other.domain) && Objects.equals(host, other.host)
                && Objects.equals(caPath, other.caPath)
                && Objects.equals(caSha256, other.caSha256);
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof ServerIdentity && sameServer((ServerIdentity) o)
                && Objects.equals(takServer, ((ServerIdentity) o).takServer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(takCredentials, takServer, domain, host, port, takTrustStore,
                androidCaStore, caPath, caSha256);
    }

    /** Names the server: for debug logs only ({@code SensitiveLog}). */
    @Override
    public String toString() {
        return serialize();
    }
}
