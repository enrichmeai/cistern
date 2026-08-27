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
                host -> new java.net.InetAddress[] {java.net.InetAddress.getByName("93.184.216.34")},
                java.util.Set.of());

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
    @DisplayName("a named origin is permitted, over its own scheme, and nothing else is")
    void trustedOriginPermitted() {
        WebIdFetchPolicy policy = WebIdFetchPolicy.trusting(
                java.util.Set.of("http://localhost:3939"));

        assertThat(policy.refuse(URI.create("http://localhost:3939/alice/profile/card")))
                .describedAs("the conformance harness's IdP, named explicitly")
                .isEmpty();
        assertThat(policy.refuse(URI.create("http://localhost:3940/alice/profile/card")))
                .describedAs("a different port is a different origin")
                .contains(JwtRejectionReason.WEBID_SCHEME_REFUSED);
        assertThat(policy.refuse(URI.create("http://elsewhere.invalid/profile")))
                .describedAs("naming one http origin must not permit http generally")
                .contains(JwtRejectionReason.WEBID_SCHEME_REFUSED);
    }

    @Test
    @DisplayName("naming one private origin does not open the cloud metadata endpoint")
    void metadataStaysRefused() {
        WebIdFetchPolicy policy = WebIdFetchPolicy.trusting(
                java.util.Set.of("https://idp.internal:8443"));

        assertThat(policy.refuse(URI.create("https://169.254.169.254/latest/meta-data/")))
                .describedAs("the whole reason this is a list of origins and not a boolean")
                .contains(JwtRejectionReason.WEBID_ADDRESS_REFUSED);
        assertThat(policy.refuse(URI.create("https://10.0.0.5/profile")))
                .contains(JwtRejectionReason.WEBID_ADDRESS_REFUSED);
    }

    @Test
    @DisplayName("an origin matches whatever the default port and a trailing slash do")
    void originCanonicalisation() {
        WebIdFetchPolicy policy = WebIdFetchPolicy.trusting(
                java.util.Set.of("https://idp.internal"));

        assertThat(policy.refuse(URI.create("https://idp.internal:443/profile/card#me")))
                .describedAs("a configured entry must not miss its own WebID over a port or slash")
                .isEmpty();
    }

    @Test
    @DisplayName("the default policy trusts no extra origin")
    void defaultsTrustNothingExtra() {
        assertThat(WebIdFetchPolicy.defaults().trustedOrigins()).isEmpty();
        assertThat(WebIdFetchPolicy.defaults().refuse(URI.create("http://localhost:3939/profile")))
                .contains(JwtRejectionReason.WEBID_SCHEME_REFUSED);
    }

    @Test
    @DisplayName("construction rejects limits that would disable a guard")
    void rejectsInvalidLimits() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WebIdFetchPolicy(Duration.ZERO, 3, 1024, WebIdFetchPolicy.HostResolver.system(), java.util.Set.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WebIdFetchPolicy(Duration.ofSeconds(5), -1, 1024, WebIdFetchPolicy.HostResolver.system(), java.util.Set.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WebIdFetchPolicy(Duration.ofSeconds(5), 3, 0, WebIdFetchPolicy.HostResolver.system(), java.util.Set.of()));
    }
}
