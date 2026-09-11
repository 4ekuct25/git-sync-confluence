package com.gitlab.sync.confluence.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for GitLab PATs at rest.
 * Key is derived from a per-installation secret stored in Confluence's
 * system properties (set on first plugin init).
 *
 * Format: base64( IV[12] || ciphertext[auth tag included] )
 */
public final class PatEncryptor {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(PatEncryptor.class);

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private static final String KEY_PROPERTY = "gitsync.encryption.key";

    /**
     * Deterministic 32-byte fallback for installs with neither the system
     * property nor a writable secure directory. Clearly marked as dev-only.
     */
    private static final String DEV_KEY_B64 = "Z2l0c3luYy1kZXYta2V5LTAxMjM0NTY3ODlhYmNkZWY=";

    private PatEncryptor() {}

    /**
     * Encrypt a plaintext PAT. The key is obtained from the provided key bytes.
     *
     * @param plaintext the PAT to encrypt
     * @param key       32-byte AES key
     * @return base64-encoded IV||ciphertext
     */
    public static String encrypt(String plaintext, byte[] key) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException("AES-GCM encrypt failed", e);
        }
    }

    /**
     * Decrypt a stored (base64) blob back to plaintext using the
     * system-property / dev-fallback key.
     */
    public static String decrypt(String encryptedBase64) {
        return decrypt(encryptedBase64, getKey());
    }

    /**
     * Decrypt a stored (base64) blob using an explicit key.
     * Use this overload when the key is managed by the caller
     * (e.g. ConnectionRestResource persists its own key).
     */
    public static String decrypt(String encryptedBase64, byte[] key) {
        try {
            byte[] raw = Base64.getDecoder().decode(encryptedBase64);
            byte[] iv = new byte[GCM_IV_BYTES];
            byte[] ct = new byte[raw.length - GCM_IV_BYTES];
            System.arraycopy(raw, 0, iv, 0, GCM_IV_BYTES);
            System.arraycopy(raw, GCM_IV_BYTES, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ct), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES-GCM decrypt failed", e);
        }
    }

    /**
     * Generate a random 32-byte key (base64) for first-time init.
     */
    public static String generateKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /**
     * Resolve the AES-256 key used for PAT encryption at rest.
     *
     * <p>Resolution order (single source of truth for both the encrypting
     * admin endpoints and the decrypting GitLab client):</p>
     * <ol>
     *   <li>{@value #KEY_PROPERTY} system property (base64), if set — the
     *       intended production mechanism;</li>
     *   <li>a 32-byte key persisted at {@code {secure}/gitsync/key.bin} —
     *       read back on every JVM start so stored PATs survive restarts;</li>
     *   <li>otherwise a fresh random key is generated and persisted there;</li>
     *   <li>only if the secure directory is unusable, a well-known dev key
     *       (logged as a warning).</li>
     * </ol>
     */
    public static byte[] resolveOrCreateKey() {
        String keyB64 = System.getProperty(KEY_PROPERTY);
        if (keyB64 != null && !keyB64.isBlank()) {
            try {
                return Base64.getDecoder().decode(keyB64);
            } catch (IllegalArgumentException e) {
                LOG.warn("Invalid {} value; falling back to persisted key", KEY_PROPERTY);
            }
        }
        try {
            java.nio.file.Path keyFile =
                    com.gitlab.sync.confluence.GitsyncPaths.secureDir().toPath()
                            .resolve("gitsync").resolve("key.bin");
            if (java.nio.file.Files.exists(keyFile)) {
                byte[] key = java.nio.file.Files.readAllBytes(keyFile);
                if (key.length == 32) {
                    return key;
                }
                LOG.warn("Persisted key {} has {} bytes (expected 32); regenerating",
                        keyFile, key.length);
            }
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            java.nio.file.Files.createDirectories(keyFile.getParent());
            java.nio.file.Files.write(keyFile, key);
            LOG.info("Generated and persisted new AES-256 key at {}", keyFile);
            return key;
        } catch (Exception e) {
            LOG.warn("Secure directory unusable ({}); using dev key — stored PATs "
                    + "may not decrypt after a JVM restart.", e.getMessage());
            return Base64.getDecoder().decode(DEV_KEY_B64);
        }
    }

    private static byte[] getKey() {
        return resolveOrCreateKey();
    }
}
