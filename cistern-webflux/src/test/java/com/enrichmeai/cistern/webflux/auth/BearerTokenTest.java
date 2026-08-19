package com.enrichmeai.cistern.webflux.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One reading of {@code Authorization: Bearer} for every resolver (T4.0). */
class BearerTokenTest {

    private static MockServerHttpRequest withAuthorization(String value) {
        return MockServerHttpRequest.get("/").header(HttpHeaders.AUTHORIZATION, value).build();
    }

    @Test
    @DisplayName("the token is what follows the scheme, trimmed")
    void extractsToken() {
        assertEquals("abc", BearerToken.from(withAuthorization("Bearer abc")).orElseThrow().value());
        assertEquals("abc", BearerToken.from(withAuthorization("Bearer   abc  ")).orElseThrow().value());
    }

    @Test
    @DisplayName("the scheme is matched case-insensitively (RFC 9110 §11.1)")
    void schemeIsCaseInsensitive() {
        assertEquals("abc", BearerToken.from(withAuthorization("bearer abc")).orElseThrow().value());
        assertEquals("abc", BearerToken.from(withAuthorization("BEARER abc")).orElseThrow().value());
    }

    @Test
    @DisplayName("no header, another scheme, or an empty token: nothing presented")
    void absentWhenNotABearer() {
        assertTrue(BearerToken.from(MockServerHttpRequest.get("/").build()).isEmpty());
        assertTrue(BearerToken.from(withAuthorization("Basic abc")).isEmpty());
        assertTrue(BearerToken.from(withAuthorization("Bearer")).isEmpty());
        assertTrue(BearerToken.from(withAuthorization("Bearer ")).isEmpty());
        assertTrue(BearerToken.from(withAuthorization("Bearer    ")).isEmpty());
    }

    @Test
    @DisplayName("a blank token cannot be constructed, and the value never prints")
    void blankRefusedAndRedacted() {
        assertThrows(IllegalArgumentException.class, () -> new BearerToken(" "));
        assertFalse(new BearerToken("secret").toString().contains("secret"));
    }
}
