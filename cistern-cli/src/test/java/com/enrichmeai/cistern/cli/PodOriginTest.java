package com.enrichmeai.cistern.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Origins compare as RFC 6454 §4 says, and two names for one machine stay two origins. */
class PodOriginTest {

    private static PodOrigin origin(String uri) {
        return PodOrigin.of(URI.create(uri)).orElseThrow();
    }

    @Test
    @DisplayName("scheme and host compare without regard to case, and a default port is made explicit")
    void normalised() {
        assertEquals(origin("http://pod.example/a"), origin("HTTP://Pod.Example:80/b"));
        assertEquals(origin("https://pod.example/"), origin("https://pod.example:443/x#y"));
        assertEquals("http://pod.example", origin("http://pod.example:80/").toString());
        assertEquals("http://127.0.0.1:3737", origin("http://127.0.0.1:3737/trips/").toString());
    }

    @Test
    @DisplayName("localhost and 127.0.0.1 are different origins, and so are different ports")
    void namesAreNotMachines() {
        assertNotEquals(origin("http://localhost:3737/"), origin("http://127.0.0.1:3737/"));
        assertNotEquals(origin("http://127.0.0.1:3737/"), origin("http://127.0.0.1:3000/"));
        assertNotEquals(origin("http://pod.example/"), origin("https://pod.example/"));
    }

    @Test
    @DisplayName("an IPv6 host keeps its brackets, so equal hosts compare equal and print as typed")
    void ipv6() {
        assertEquals(origin("http://[::1]:3737/a"), origin("http://[::1]:3737/b"));
        assertNotEquals(origin("http://[::1]:3737/"), origin("http://127.0.0.1:3737/"));
        assertEquals("http://[::1]:3737", origin("http://[::1]:3737/").toString());
    }

    @Test
    @DisplayName("an IRI that is not http(s) with a host has no origin")
    void notAWebOrigin() {
        assertTrue(PodOrigin.of(URI.create("urn:uuid:1")).isEmpty());
        assertTrue(PodOrigin.of(URI.create("mailto:a@pod.example")).isEmpty());
        assertTrue(PodOrigin.of(URI.create("/relative")).isEmpty());
    }
}
