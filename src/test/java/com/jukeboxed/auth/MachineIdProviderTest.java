package com.jukeboxed.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MachineIdProviderTest {

    @AfterEach
    void cleanup() {
        MachineIdProvider.invalidateCache();
        MachineIdProvider.setOverride(null);
    }

    @Test
    void overrideBypassesOsLookup() {
        MachineIdProvider.setOverride("11111111-2222-3333-4444-555555555555");
        assertEquals("11111111-2222-3333-4444-555555555555", MachineIdProvider.get());
        assertEquals("11111111-2222-3333-4444-555555555555", MachineIdProvider.get(),
                "result must be stable across calls");
    }

    @Test
    void invalidateCacheForcesRedetection() {
        MachineIdProvider.setOverride("a");
        assertEquals("a", MachineIdProvider.get());
        MachineIdProvider.invalidateCache();
        MachineIdProvider.setOverride("b");
        assertEquals("b", MachineIdProvider.get());
    }

    @Test
    void osLookupProducesNonEmptyOrThrows() {
        // On the CI host, this test either returns a non-empty string or throws
        // MachineIdUnavailableException. Either is acceptable — we don't want to
        // hard-fail the test when running inside a CI container that has no UUID.
        try {
            String id = MachineIdProvider.get();
            assertNotNull(id);
            assertFalse(id.isEmpty());
        } catch (MachineIdUnavailableException ok) {
            // expected inside minimal containers
        }
    }
}
