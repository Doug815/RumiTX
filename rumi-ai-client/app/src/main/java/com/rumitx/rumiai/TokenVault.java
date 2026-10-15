package com.rumitx.rumiai;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Token persistence encrypted by hardware/OS-backed AndroidKeyStore AES-GCM. */
public final class TokenVault {
    private static final String ALIAS = "rumi_ai_v01_tokens";
    private final SharedPreferences prefs;

    public TokenVault(Context context) {
        prefs = context.getSharedPreferences("rumi_secure_prefs", Context.MODE_PRIVATE);
    }

    private SecretKey getKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build());
        return gen.generateKey();
    }

    public void save(String name, String token) throws Exception {
        if (!"read".equals(name) && !"command".equals(name)) throw new IllegalArgumentException("Invalid token slot");
        if (token == null || token.trim().length() < 32) throw new IllegalArgumentException("Token must be at least 32 characters");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        byte[] ciphertext = cipher.doFinal(token.trim().getBytes(StandardCharsets.UTF_8));
        prefs.edit().putString("secret_" + name, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
            + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)).apply();
    }

    public String load(String name) throws Exception {
        String stored = prefs.getString("secret_" + name, "");
        if (stored.isEmpty()) return "";
        String[] pair = stored.split(":", 2);
        if (pair.length != 2) throw new IllegalStateException("Credential encoding invalid");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, Base64.decode(pair[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(pair[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    public void clear() {
        prefs.edit().remove("secret_read").remove("secret_command").apply();
    }
}
