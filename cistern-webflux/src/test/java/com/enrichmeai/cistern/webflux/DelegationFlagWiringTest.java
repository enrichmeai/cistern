package com.enrichmeai.cistern.webflux;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.enrichmeai.cistern.wac.DelegationMode;
import com.enrichmeai.cistern.wac.WacEngine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * That the bound flag reaches the one engine the filter decides with (T6.5). The binding test
 * proves the property binds; this proves the wiring hands it on — the two halves of AD-18's
 * "where two owners share one answer, the proof is end-to-end".
 */
@SpringBootTest(properties = {
    "cistern.base-url=http://localhost:3000",
    "cistern.wac.delegation.enabled=true",
})
class DelegationFlagWiringTest {

    private static final Path STORAGE_ROOT = createTempRoot();

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("cistern.storage.root", STORAGE_ROOT::toString);
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("cistern-t65-wiring-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired private WacEngine engine;

    @Test
    @DisplayName("the WacEngine bean evaluates delegation terms when the flag is on")
    void flagReachesTheEngine() {
        assertEquals(DelegationMode.ENABLED, engine.delegation());
    }
}
