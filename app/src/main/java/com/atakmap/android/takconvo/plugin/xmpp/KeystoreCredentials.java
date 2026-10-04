package com.atakmap.android.takconvo.plugin.xmpp;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.atakmap.coremap.log.Log;

import eu.siacs.conversations.utils.TakConvoCompat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts the password and FAST token in Conversations' accounts table with an AES-GCM key in
 * the Android Keystore, which never leaves it: a copy of ATAK's databases (a backup, a
 * debuggable build's run-as) doesn't give the TAK password away. ATAK's credential store keeps
 * the original, so a value that can't be encrypted or decrypted is stored or read as empty and
 * provisioning sets it again. See docs/02.
 */
final class KeystoreCredentials implements TakConvoCompat.CredentialCipher {

    private static final String TAG = "TakConvo.Credentials";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "takconvo_credentials";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    /** Marks an encrypted value; anything else was stored before encryption. */
    private static final String PREFIX = "takconvo-gcm1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private SecretKey key;
    private volatile boolean sawPlaintext;

    @Override
    public String encrypt(final String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            final byte[] iv = cipher.getIV();
            final byte[] sealed = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            final byte[] out = ByteBuffer.allocate(iv.length + sealed.length)
                    .put(iv).put(sealed).array();
            return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (final GeneralSecurityException | IOException e) {
            // never in clear: provisioning has the original
            Log.e(TAG, "unable to encrypt a credential, storing none", e);
            return "";
        }
    }

    @Override
    public String decrypt(final String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (!stored.startsWith(PREFIX)) {
            sawPlaintext = true;
            return stored;
        }
        try {
            final byte[] in = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, in, 0,
                    IV_BYTES));
            return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES),
                    StandardCharsets.UTF_8);
        } catch (final GeneralSecurityException | IOException | IllegalArgumentException e) {
            // e.g. the key is gone (the data was restored elsewhere): provisioning sets it again
            Log.w(TAG, "unable to decrypt a stored credential", e);
            return "";
        }
    }

    /** Whether a value stored before encryption was read: the engine stores it again. */
    boolean sawPlaintext() {
        return sawPlaintext;
    }

    /** Deletes the key: what it encrypted can't be read any more. */
    static void deleteKey() {
        try {
            final KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            store.deleteEntry(ALIAS);
        } catch (final GeneralSecurityException | IOException e) {
            Log.w(TAG, "unable to delete the credentials key", e);
        }
    }

    private synchronized SecretKey key() throws GeneralSecurityException, IOException {
        if (key == null) {
            final KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            final KeyStore.Entry entry = store.getEntry(ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                key = ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            } else {
                final KeyGenerator generator =
                        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
                generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build());
                key = generator.generateKey();
            }
        }
        return key;
    }
}
