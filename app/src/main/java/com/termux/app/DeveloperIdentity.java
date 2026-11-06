package com.termux.app;

import android.content.Context;
import android.util.Base64;

import com.termux.R;
import com.termux.shared.logger.Logger;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Decrypts the app's obfuscated developer label for display in About. */
public final class DeveloperIdentity {

    private static final String LOG_TAG = "DeveloperIdentity";
    private static final byte[] KEY = {
        0x78, 0x47, 0x69, 0x6d, (byte) 0xac, 0x58, (byte) 0xac, 0x1a,
        0x72, (byte) 0x9b, (byte) 0x80, (byte) 0xda, 0x7f, (byte) 0xe4, (byte) 0xa2, 0x0d,
        (byte) 0x80, 0x43, 0x00, (byte) 0xb8, 0x2a, 0x7a, 0x27, (byte) 0xe8,
        (byte) 0xb0, 0x4c, 0x4d, 0x1f, 0x62, 0x66, 0x00, (byte) 0xc9
    };
    private static final byte[] NONCE = {
        0x4d, 0x0b, 0x78, (byte) 0xa3, 0x58, (byte) 0xfc,
        (byte) 0xfe, 0x5f, (byte) 0xc7, 0x04, 0x53, 0x05
    };
    private static volatile String sCachedName;

    private DeveloperIdentity() {}

    public static String getName(Context context) {
        String cached = sCachedName;
        if (cached != null) {
            return cached;
        }
        byte[] ciphertext = Base64.decode(context.getString(R.string.app_developer_name_ciphertext), Base64.DEFAULT);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"), new GCMParameterSpec(128, NONCE));
            String decoded = new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            sCachedName = decoded;
            return decoded;
        } catch (GeneralSecurityException e) {
            Logger.logErrorExtended(LOG_TAG, "Unable to decode the developer identity.");
            return "Unavailable";
        } finally {
            Arrays.fill(ciphertext, (byte) 0);
        }
    }
}
