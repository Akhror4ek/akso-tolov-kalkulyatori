package uz.akso.kalkulyator;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores only the short-lived WASYT bearer token, encrypted by Android Keystore. */
final class WasytSecureStore {
    private static final String PREFS = "akso_wasyt_secure_local_only";
    private static final String KEY_ALIAS = "akso_wasyt_token_key_v1";
    private static final String TOKEN = "encrypted_token";
    private static final String USERNAME = "username";
    private static final String DISPLAY_NAME = "display_name";
    private static final String COMPANY_NAME = "company_name";
    private static final String COMPANY_ID = "company_id";
    private static final String EXPIRES_AT = "expires_at";
    private static final long TOKEN_LIFETIME_MS = TimeUnit.DAYS.toMillis(7) - TimeUnit.MINUTES.toMillis(5);

    private WasytSecureStore() { }

    static void save(Context context, String username, String token,
                     WasytApiClient.AccountInfo account) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
        String data = Base64.encodeToString(encrypted, Base64.NO_WRAP);

        prefs(context).edit()
                .putString(TOKEN, iv + ":" + data)
                .putString(USERNAME, username)
                .putString(DISPLAY_NAME, account == null ? "" : account.displayName)
                .putString(COMPANY_NAME, account == null ? "" : account.companyName)
                .putLong(COMPANY_ID, account == null ? -1L : account.companyId)
                .putLong(EXPIRES_AT, System.currentTimeMillis() + TOKEN_LIFETIME_MS)
                .apply();
    }

    static String getValidToken(Context context) {
        SharedPreferences preferences = prefs(context);
        long expiresAt = preferences.getLong(EXPIRES_AT, 0L);
        if (expiresAt <= System.currentTimeMillis()) {
            clearToken(context);
            return null;
        }
        String packed = preferences.getString(TOKEN, null);
        if (packed == null) return null;
        try {
            String[] parts = packed.split(":", 2);
            if (parts.length != 2) throw new IllegalStateException("Encrypted token format is invalid.");
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception error) {
            clearToken(context);
            return null;
        }
    }

    static String getUsername(Context context) {
        return prefs(context).getString(USERNAME, "");
    }

    static String getDisplayName(Context context) {
        return prefs(context).getString(DISPLAY_NAME, "");
    }

    static String getCompanyName(Context context) {
        return prefs(context).getString(COMPANY_NAME, "");
    }

    static long getCompanyId(Context context) {
        return prefs(context).getLong(COMPANY_ID, -1L);
    }

    static long getExpiresAt(Context context) {
        return prefs(context).getLong(EXPIRES_AT, 0L);
    }

    static void clearToken(Context context) {
        prefs(context).edit()
                .remove(TOKEN)
                .remove(EXPIRES_AT)
                .remove(DISPLAY_NAME)
                .remove(COMPANY_NAME)
                .remove(COMPANY_ID)
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        // This preference file is deliberately NOT included in Firebase cloud-sync buckets.
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build();
        generator.init(spec);
        return generator.generateKey();
    }
}
