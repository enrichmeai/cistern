package com.enrichmeai.cistern.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The T6.2 binding guard: an enabled door without a credential is refused at bind time. */
class CisternMcpPropertiesTest {

    @Test
    @DisplayName("enabled without a credential is refused — a door must not act as anonymous")
    void enabledRequiresCredential() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new CisternMcpProperties(true, null, null));
        assertTrue(refused.getMessage().contains("cistern.mcp.credential"), refused.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new CisternMcpProperties(true, "  ", null));
    }

    @Test
    @DisplayName("disabled needs nothing; enabled with a credential binds")
    void validShapes() {
        assertDoesNotThrow(() -> new CisternMcpProperties(false, null, null));
        assertDoesNotThrow(() -> new CisternMcpProperties(
                true, "secret", URI.create("http://127.0.0.1:3737")));
    }

    @Test
    @DisplayName("the credential never appears in toString")
    void credentialIsRedacted() {
        assertTrue(!new BearerCredential("s3cret").toString().contains("s3cret"));
    }
}
