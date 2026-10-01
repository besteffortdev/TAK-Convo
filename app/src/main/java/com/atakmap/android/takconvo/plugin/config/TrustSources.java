package com.atakmap.android.takconvo.plugin.config;

import com.atakmap.coremap.log.Log;
import com.atakmap.net.CertificateManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * CAs trusted for the XMPP server besides the public ones: ATAK's TAK server truststores, the
 * Android CA store (user and MDM CAs) and a CA file. Any of them may validate the chain;
 * Conversations keeps hostname verification. See docs/03.
 */
public final class TrustSources {

    private static final String TAG = "TakConvo.Trust";

    private TrustSources() {
    }

    /** A trust manager over the enabled sources, or null if none has a CA. */
    public static X509TrustManager build(final XmppSettings settings) {
        final Map<String, X509TrustManager> sources = new LinkedHashMap<>();
        if (settings.useTakTrustStore) {
            put(sources, "TAK server truststore", takTrustStore());
        }
        if (settings.useAndroidCaStore) {
            put(sources, "Android CA store", androidCaStore());
        }
        if (settings.trustedCaPath != null) {
            put(sources, settings.trustedCaPath, TrustedCa.load(settings.trustedCaPath));
        }
        if (sources.isEmpty()) {
            return null;
        }
        final Composite composite = new Composite(sources);
        Log.d(TAG, "trust sources " + composite);
        return composite;
    }

    /** Changes only when the set of trusted CAs does, so the engine reconnects only then. */
    public static String fingerprint(final X509TrustManager trust) {
        if (trust == null) {
            return "";
        }
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final List<String> issuers = new ArrayList<>();
            for (final X509Certificate cert : trust.getAcceptedIssuers()) {
                issuers.add(Arrays.toString(MessageDigest.getInstance("SHA-256")
                        .digest(cert.getEncoded())));
            }
            issuers.sort(null);
            for (final String issuer : issuers) {
                digest.update(issuer.getBytes(StandardCharsets.UTF_8));
            }
            return Arrays.toString(digest.digest());
        } catch (final GeneralSecurityException e) {
            // unique per trust manager: counts as a change
            Log.w(TAG, "unable to fingerprint the trusted CAs", e);
            return String.valueOf(System.identityHashCode(trust));
        }
    }

    /** CAs of all TAK server truststores in ATAK, no public CAs. */
    private static X509TrustManager takTrustStore() {
        try {
            // the String variant re-reads ATAK's database, so it sees new imports; null: all
            return CertificateManager.getInstance().getLocalTrustManager((String) null);
        } catch (final RuntimeException e) {
            Log.e(TAG, "unable to read the TAK server truststores", e);
            return null;
        }
    }

    /** System and user CAs; apps don't trust user (and MDM) CAs by default. */
    private static X509TrustManager androidCaStore() {
        try {
            final KeyStore store = KeyStore.getInstance("AndroidCAStore");
            store.load(null, null);
            final TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(store);
            for (final TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
        } catch (final GeneralSecurityException | IOException e) {
            Log.e(TAG, "unable to read the Android CA store", e);
        }
        return null;
    }

    private static void put(final Map<String, X509TrustManager> sources, final String name,
            final X509TrustManager tm) {
        if (tm != null && tm.getAcceptedIssuers() != null && tm.getAcceptedIssuers().length > 0) {
            sources.put(name, tm);
        } else {
            Log.d(TAG, name + ": no CA certificates");
        }
    }

    /** Trusts what any of its sources trusts. */
    private static final class Composite implements X509TrustManager {

        private final Map<String, X509TrustManager> sources;

        Composite(final Map<String, X509TrustManager> sources) {
            this.sources = sources;
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, final String authType)
                throws CertificateException {
            CertificateException first = null;
            for (final Map.Entry<String, X509TrustManager> source : sources.entrySet()) {
                try {
                    source.getValue().checkServerTrusted(chain, authType);
                    Log.d(TAG, "server certificate " + subject(chain) + " trusted via "
                            + source.getKey());
                    return;
                } catch (final CertificateException e) {
                    if (first == null) {
                        first = e;
                    }
                }
            }
            Log.d(TAG, "server certificate " + subject(chain) + " not trusted by " + this);
            throw first;
        }

        @Override
        public void checkClientTrusted(final X509Certificate[] chain, final String authType)
                throws CertificateException {
            CertificateException first = null;
            for (final X509TrustManager tm : sources.values()) {
                try {
                    tm.checkClientTrusted(chain, authType);
                    return;
                } catch (final CertificateException e) {
                    if (first == null) {
                        first = e;
                    }
                }
            }
            throw first;
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            final List<X509Certificate> all = new ArrayList<>();
            for (final X509TrustManager tm : sources.values()) {
                all.addAll(Arrays.asList(tm.getAcceptedIssuers()));
            }
            return all.toArray(new X509Certificate[0]);
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("[");
            for (final Map.Entry<String, X509TrustManager> source : sources.entrySet()) {
                if (sb.length() > 1) {
                    sb.append(", ");
                }
                sb.append(source.getKey()).append(": ")
                        .append(source.getValue().getAcceptedIssuers().length).append(" CA(s)");
            }
            return sb.append(']').toString();
        }

        private static String subject(final X509Certificate[] chain) {
            return chain == null || chain.length == 0 ? "?"
                    : chain[0].getSubjectX500Principal().getName();
        }
    }
}
