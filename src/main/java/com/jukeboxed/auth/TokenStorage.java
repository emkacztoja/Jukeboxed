package com.jukeboxed.auth;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;

/**
 * Persists {@link SpotifyToken} to disk under AES-256-GCM, with the AES key derived
 * via PBKDF2-HMAC-SHA256 (100,000 iterations, 16-byte salt) from the machine UUID
 * obtained by {@link MachineIdProvider}.
 * <p>
 * File layout (all binary):
 * <pre>
 * | offset | bytes | content                       |
 * | -----: | ----: | ----------------------------- |
 * | 0      |    16 | PBKDF2 salt (random per file) |
 * | 16     |    12 | AES-GCM IV (random per write) |
 * | 28     |   N   | ciphertext ∥ 16-byte GCM tag  |
 * </pre>
 * Writes are atomic (temp + rename). Decrypt failures (wrong key, tampered file)
 * surface as {@link TokenStorageException}.
 */
final class TokenStorage {

    private static final String CIPHER_TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 100_000;

    private static final SecureRandom RNG = new SecureRandom();
    private static final Gson GSON = new Gson();

    private final Path file;

    TokenStorage() {
        this.file = FabricLoader.getInstance()
                .getConfigDir()
                .resolve("jukeboxed")
                .resolve("tokens.bin");
    }

    /** Visible for tests with a custom backing file. */
    TokenStorage(Path backingFile) {
        this.file = backingFile;
    }

    Path file() {
        return file;
    }

    /** Encrypt and persist the given token. Atomic write. */
    void save(SpotifyToken token) {
        String json = GSON.toJson(token);
        byte[] plaintext = json.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] salt = randomBytes(SALT_BYTES);
            byte[] iv = randomBytes(IV_BYTES);
            SecretKey key = deriveKey(salt);
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            byte[] blob = new byte[SALT_BYTES + IV_BYTES + ciphertext.length];
            System.arraycopy(salt, 0, blob, 0, SALT_BYTES);
            System.arraycopy(iv, 0, blob, SALT_BYTES, IV_BYTES);
            System.arraycopy(ciphertext, 0, blob, SALT_BYTES + IV_BYTES, ciphertext.length);

            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, blob);
            try {
                Files.move(tmp, file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                // Some filesystems lack atomic move; fall back to a non-atomic replace.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (GeneralSecurityException | IOException ex) {
            throw new TokenStorageException("Failed to encrypt and persist token", ex);
        }
    }

    /**
     * Load and decrypt the token file. Throws {@link TokenStorageException} if the
     * file is missing, unreadable, tampered, or encrypted under a different key.
     */
    SpotifyToken load() {
        if (!Files.exists(file)) {
            throw new TokenStorageException("Token file does not exist: " + file);
        }
        byte[] blob;
        try {
            blob = Files.readAllBytes(file);
        } catch (IOException ex) {
            throw new TokenStorageException("Could not read token file", ex);
        }
        if (blob.length < SALT_BYTES + IV_BYTES + (GCM_TAG_BITS / 8)) {
            throw new TokenStorageException("Token file is truncated");
        }
        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        byte[] ciphertext = new byte[blob.length - SALT_BYTES - IV_BYTES];
        System.arraycopy(blob, 0, salt, 0, SALT_BYTES);
        System.arraycopy(blob, SALT_BYTES, iv, 0, IV_BYTES);
        System.arraycopy(blob, SALT_BYTES + IV_BYTES, ciphertext, 0, ciphertext.length);
        try {
            SecretKey key = deriveKey(salt);
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            SpotifyToken token = GSON.fromJson(new String(plaintext, StandardCharsets.UTF_8), SpotifyToken.class);
            if (token == null) {
                throw new TokenStorageException("Token payload was empty");
            }
            return token;
        } catch (GeneralSecurityException ex) {
            // GCM auth-tag failure ⇒ wrong key OR tampered blob. Caller treats as expired/missing session.
            throw new TokenStorageException("Token file failed authentication — wrong machine, key rotated, or file tampered", ex);
        }
    }

    /** Delete the persisted token (used by logout). No-op if the file is absent. */
    void delete() {
        try {
            Files.deleteIfExists(file);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
        } catch (IOException ex) {
            // best-effort
        }
    }

    private static SecretKey deriveKey(byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(
                    MachineIdProvider.get().toCharArray(),
                    salt,
                    PBKDF2_ITERATIONS,
                    KEY_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } catch (GeneralSecurityException ex) {
            throw new TokenStorageException("PBKDF2 key derivation failed", ex);
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] b = new byte[length];
        RNG.nextBytes(b);
        return b;
    }

    /** Marker exception so callers can distinguish storage errors from auth ones. */
    static final class TokenStorageException extends RuntimeException {
        TokenStorageException(String message) {
            super(message);
        }
        TokenStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
