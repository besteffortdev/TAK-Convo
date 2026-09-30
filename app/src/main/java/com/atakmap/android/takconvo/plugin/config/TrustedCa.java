package com.atakmap.android.takconvo.plugin.config;

import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Builds a trust manager from a CA file (PEM or DER, one or more certificates). */
public final class TrustedCa {

    private static final String TAG = "TakConvo.TrustedCa";

    private TrustedCa() {}

    /** @return a trust manager for the certificates in {@code path}, or null if unusable */
    public static X509TrustManager load(final String path) {
        if (path == null) {
            return null;
        }
        final File file = new File(path);
        if (!file.isFile()) {
            Log.w(TAG, "trusted CA file not found: " + path);
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            final Collection<? extends Certificate> certs =
                    CertificateFactory.getInstance("X.509").generateCertificates(in);
            if (certs.isEmpty()) {
                Log.w(TAG, "no certificates in " + path);
                return null;
            }
            final KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            int i = 0;
            for (final Certificate cert : certs) {
                keyStore.setCertificateEntry("takconvo-ca-" + i++, cert);
            }
            final TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);
            for (final TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    Log.d(TAG, "trusting " + certs.size() + " certificate(s) from " + path);
                    return (X509TrustManager) tm;
                }
            }
        } catch (final Exception e) {
            Log.e(TAG, "unable to load trusted CA " + path, e);
        }
        return null;
    }
}
