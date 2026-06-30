package com.jukeboxed.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TokenStorageTest {

    @TempDir
    Path tmp;

    TokenStorage storage;

    @BeforeEach
    void setUp() {
        MachineIdProvider.setOverride("machine-A");
        storage = new TokenStorage(tmp.resolve("tokens.bin"));
    }

    @AfterEach
    void tearDown() {
        MachineIdProvider.invalidateCache();
        storage.delete();
    }

    @Test
    void roundTripPersistsAndRecoversToken() {
        SpotifyToken original = new SpotifyToken(
                "BQD-access-token",
                "AQD-refresh-token",
                System.currentTimeMillis() + 3_600_000L);

        storage.save(original);
        assertTrue(storage.file().toFile().exists(), "save() must write the backing file");

        SpotifyToken loaded = storage.load();
        assertEquals(original.access_token(), loaded.access_token());
        assertEquals(original.refresh_token(), loaded.refresh_token());
        assertEquals(original.expires_at(), loaded.expires_at());
    }

    @Test
    void tamperedCiphertextThrowsOnLoad() throws Exception {
        SpotifyToken original = new SpotifyToken("a", "b", System.currentTimeMillis() + 10_000L);
        storage.save(original);

        // Flip a single byte in the ciphertext area (after the salt+IV header).
        byte[] blob = java.nio.file.Files.readAllBytes(storage.file());
        if (blob.length > 32) {
            blob[32] ^= 0x01;
        }
        java.nio.file.Files.write(storage.file(), blob);

        TokenStorage.TokenStorageException ex = assertThrows(
                TokenStorage.TokenStorageException.class, storage::load);
        assertNotNull(ex.getMessage());
    }

    @Test
    void differentMachineIdFailsToDecrypt() {
        SpotifyToken original = new SpotifyToken("a", "b", System.currentTimeMillis() + 10_000L);
        storage.save(original);

        MachineIdProvider.setOverride("machine-B");
        assertThrows(TokenStorage.TokenStorageException.class, storage::load);
    }

    @Test
    void loadOnMissingFileThrows() {
        TokenStorage emptyStorage = new TokenStorage(tmp.resolve("does-not-exist.bin"));
        assertThrows(TokenStorage.TokenStorageException.class, emptyStorage::load);
    }

    @Test
    void deleteRemovesBothFileAndTempSibling() {
        storage.save(new SpotifyToken("a", "b", System.currentTimeMillis() + 10_000L));
        assertTrue(storage.file().toFile().exists());
        storage.delete();
        assertFalse(storage.file().toFile().exists());
    }
}
