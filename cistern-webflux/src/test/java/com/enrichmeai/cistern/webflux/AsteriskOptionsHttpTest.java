package com.enrichmeai.cistern.webflux;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.URI;

/**
 * {@code OPTIONS *} through the whole chain, which is the only place the bug was visible.
 *
 * <p>{@link ResourceOptionsHandler} always had a correct asterisk-form branch, tested at the
 * handler. {@link AuthorizationFilter} sat in front of it resolving a target from the request
 * path, and an asterisk-form arrives with an empty path that {@code RequestPaths} refuses — so
 * every enforced deployment answered 400 and the handler's branch never ran. Handler-level
 * tests could not see it, because they were the layer that worked.
 *
 * <p>So this asserts through {@code WebTestClient}, and it is the shape of test worth copying
 * for anything else the filter guards: correct behind a filter is not correct.
 */
@SpringBootTest(properties = {
    "cistern.base-url=" + AsteriskOptionsHttpTest.BASE,
    "cistern.owner.web-id=" + AsteriskOptionsHttpTest.OWNER,
    "cistern.owner.token=" + AsteriskOptionsHttpTest.TOKEN,
})
@AutoConfigureWebTestClient
@DisplayName("OPTIONS * asks about the server, so the filter must not look for a resource")
class AsteriskOptionsHttpTest {

    static final String BASE = "http://localhost:3000";
    static final String OWNER = "https://alice.example/profile/card#me";
    static final String TOKEN = "asterisk-test-token";

    @Autowired private WebTestClient client;

    /**
     * Reactor Netty renders the asterisk-form as an empty path, which is what the handler's
     * {@code ASTERISK_FORM_PATH} documents; {@code WebTestClient} reaches the same shape via
     * a bare authority URI.
     */
    private WebTestClient.RequestHeadersSpec<?> optionsAsterisk() {
        return client.method(HttpMethod.OPTIONS).uri(URI.create(BASE));
    }

    @Test
    @DisplayName("anonymously it answers, rather than 400 — there is no resource to be refused")
    void anonymousAsteriskIsAnswered() {
        optionsAsterisk()
                .exchange()
                // 204 is what the handler answers; the assertion that matters is that it is
                // the handler answering at all rather than the filter's 400.
                .expectStatus().isNoContent()
                .expectHeader().exists(HttpHeaders.ALLOW);
    }

    @Test
    @DisplayName("and it answers the same for the owner: the response is about the server")
    void ownerAsteriskIsAnswered() {
        optionsAsterisk()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().exists(HttpHeaders.ALLOW);
    }

    @Test
    @DisplayName("the exemption is narrow: OPTIONS on a real path is still enforced")
    void optionsOnAResourceIsStillJudged() {
        // The exemption keys on the empty path, not on the method — an OPTIONS carrying a
        // real target must still go through WAC like anything else, or the fix would be a
        // hole rather than a correction.
        client.method(HttpMethod.OPTIONS).uri("/some/resource")
                .exchange()
                .expectStatus().value(status -> org.assertj.core.api.Assertions
                        .assertThat(status)
                        .describedAs("a targeted OPTIONS is judged; only the asterisk-form is exempt")
                        .isNotEqualTo(204));
    }
}
