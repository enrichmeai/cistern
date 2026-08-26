package com.enrichmeai.cistern.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("The WebID fetch policy is what makes a caller-chosen URL survivable")
class WebIdFetchPolicyTest {

    private final WebIdFetchPolicy policy = WebIdFetchPolicy.defaults();

    @Test
    @DisplayName("a public https WebID is permitted")
    void permitsPublicHttps() {
        // A stub resolver, so this asserts the rule rather than the health of public DNS.
        WebIdFetchPolicy resolving = new WebIdFetchPolicy(
                WebIdFetchPolicy.DEFAULT_TIMEOUT, WebIdFetchPolicy.DEFAULT_MAX_REDIRECTS,
                WebIdFetchPolicy.DEFAULT_MAX_BODY_BYTES,
                host -> new java.net.InetAddress[] {java.net.InetAddress.getByName("93.184.216.34")});

        assertThat(resolving.refuse(URI.create("https://alice.example/profile/card"))).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("non-https schemes are refused")
    @ValueSource(strings = {
            "http://alice.example/profile",
            "file:///etc/passwd",
            "gopher://alice.example/1",
            "ftp://alice.example/profile"
    })
    void refusesOtherSchemes(String uri) {
        assertThat(policy.refuse(URI.create(uri)))
                .contains(JwtRejectionReason.WEBID_SCHEME_REFUSED);
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("addresses that are not on the public internet are refused")
    @ValueSource(strings = {
            "https://127.0.0.1/profile",          // the pod calling itself
            "https://localhost/profile",
            "https://169.254.169.254/latest/meta-data/",   // cloud metadata
            "https://10.0.0.5/profile",
            "https://192.168.1.10/profile",
            "https://172.16.0.9/profile",
            "https://0.0.0.0/profile",
            "https://[::1]/profile"
    })
    void refusesPrivateAddresses(String uri) {
        assertThat(policy.refuse(URI.create(uri)))
                .describedAs("a caller-chosen URL must not reach the pod's own network")
                .contains(JwtRejectionReason.WEBID_ADDRESS_REFUSED);
    }

    @Test
    @DisplayName("a host that does not resolve is refused, not allowed through")
    void refusesUnresolvable() {
        assertThat(policy.refuse(URI.create("https://no-such-host.invalid/profile")))
                .describedAs("the ruling is fail-closed")
                .contains(JwtRejectionReason.WEBID_ADDRESS_REFUSED);
    }

    @Test
    @DisplayName("a relative or hostless URI is refused")
    void refusesMalformed() {
        assertThat(policy.refuse(URI.create("/profile/card")))
                .contains(JwtRejectionReason.WEBID_INVALID);
        assertThat(policy.refuse(null)).contains(JwtRejectionReason.WEBID_INVALID);
    }

    @Test
    @DisplayName("construction rejects limits that would disable a guard")
    void rejectsInvalidLimits() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WebIdFetchPolicy(Duration.ZERO, 3, 1024, WebIdFetchPolicy.HostResolver.system()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WebIdFetchPolicy(Duration.ofSeconds(5), -1, 1024, WebIdFetchPolicy.HostResolver.system()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WebIdFetchPolicy(Duration.ofSeconds(5), 3, 0, WebIdFetchPolicy.HostResolver.system()));
    }
}
