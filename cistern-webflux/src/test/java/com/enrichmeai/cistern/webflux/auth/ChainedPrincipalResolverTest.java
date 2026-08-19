package com.enrichmeai.cistern.webflux.auth;

import com.enrichmeai.cistern.core.Agent;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The chain's contract (T4.0): first authenticated wins, lazily; else anonymous. */
class ChainedPrincipalResolverTest {

    private static final Agent ALICE = Agent.of(URI.create("https://alice.example/profile/card#me"));
    private static final Agent BOB = Agent.of(URI.create("https://bob.example/profile/card#me"));

    private static final ServerWebExchange EXCHANGE =
            MockServerWebExchange.from(MockServerHttpRequest.get("/"));

    private static PrincipalResolver always(Agent agent) {
        return exchange -> Mono.just(agent);
    }

    @Test
    @DisplayName("the first member to authenticate wins")
    void firstAuthenticatedWins() {
        var chain = new ChainedPrincipalResolver(
                List.of(new AnonymousResolver(), always(ALICE), always(BOB)));

        StepVerifier.create(chain.resolve(EXCHANGE)).expectNext(ALICE).verifyComplete();
    }

    @Test
    @DisplayName("members after the winner are never asked — a JWT is not verified for a request the owner token accepted")
    void laterMembersAreNotInvoked() {
        AtomicInteger asked = new AtomicInteger();
        PrincipalResolver counting = exchange -> {
            asked.incrementAndGet();
            return Mono.just(BOB);
        };
        var chain = new ChainedPrincipalResolver(List.of(always(ALICE), counting));

        StepVerifier.create(chain.resolve(EXCHANGE)).expectNext(ALICE).verifyComplete();
        assertEquals(0, asked.get(), "the second member must not have been subscribed to");
    }

    @Test
    @DisplayName("when nobody authenticates, the result is ANONYMOUS")
    void allAnonymousIsAnonymous() {
        var chain = new ChainedPrincipalResolver(
                List.of(new AnonymousResolver(), always(Agent.ANONYMOUS)));

        StepVerifier.create(chain.resolve(EXCHANGE)).expectNext(Agent.ANONYMOUS).verifyComplete();
    }

    @Test
    @DisplayName("an empty chain is a chain that authenticates nobody, not an error")
    void emptyChainIsAnonymous() {
        var chain = new ChainedPrincipalResolver(List.of());

        StepVerifier.create(chain.resolve(EXCHANGE)).expectNext(Agent.ANONYMOUS).verifyComplete();
    }

    @Test
    @DisplayName("a member that violates the contract by erroring is not hidden behind a 401")
    void memberErrorsPropagate() {
        PrincipalResolver broken = exchange -> Mono.error(new IllegalStateException("bug"));
        var chain = new ChainedPrincipalResolver(List.of(new AnonymousResolver(), broken));

        StepVerifier.create(chain.resolve(EXCHANGE)).expectError(IllegalStateException.class).verify();
    }

    @Test
    @DisplayName("members are exposed read-only, in order")
    void membersInOrder() {
        PrincipalResolver first = always(ALICE);
        PrincipalResolver second = always(BOB);
        var chain = new ChainedPrincipalResolver(List.of(first, second));

        assertEquals(List.of(first, second), chain.members());
    }
}
