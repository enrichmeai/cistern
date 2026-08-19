package com.enrichmeai.cistern.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.wac.AccessMode;
import com.enrichmeai.cistern.wac.AclResource;
import com.enrichmeai.cistern.wac.GrantOutcome;
import com.enrichmeai.cistern.wac.GrantRequest;
import com.enrichmeai.cistern.wac.GrantService;
import com.enrichmeai.cistern.wac.Grantee;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The CLI against the real server (ground rule 6): the WebFlux stack from cistern-webflux is
 * booted in-process with an owner configured, so enforcement is on and {@code OwnerPodSeeder}
 * writes the root ACL exactly as a deployment would. Nothing on the wire is mocked; the only
 * test double is a <em>concurrent editor</em> that performs a real write between the CLI's read
 * and its write, to exercise the 412 path.
 *
 * <p>The scenario is {@code k8s/demo.sh} beats 3 and 5 with the CLI standing in for the
 * hand-written Turtle: grant public read on {@code /trips/}, the agent reads (200) but cannot
 * delete (401); revoke; the agent's very next read is 401 and the owner is unaffected.
 */
class CliEndToEndTest {

    private static final String OWNER = "https://you.example/profile/card#me";
    private static final String TOKEN = "e2e-owner-token";
    private static final String ALICE = "https://alice.example/profile/card#me";
    private static final String TURTLE = "text/turtle";
    private static final String NOTE = "<#t> <http://purl.org/dc/terms/title> \"Lisbon, May\" .";

    private static ConfigurableApplicationContext server;
    private static String base;
    private static final HttpClient http = HttpClient.newHttpClient();

    private final StringWriter stdout = new StringWriter();
    private final StringWriter stderr = new StringWriter();

    @BeforeAll
    static void bootServer() throws IOException {
        int port = freePort();
        base = "http://127.0.0.1:" + port;
        Path storage = Files.createTempDirectory("cistern-cli-e2e");
        server = new SpringApplicationBuilder(TestServer.class)
                .properties(
                        "server.port=" + port,
                        "cistern.base-url=" + base,
                        "cistern.storage.root=" + storage,
                        "cistern.owner.web-id=" + OWNER,
                        "cistern.owner.token=" + TOKEN)
                .run();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    /**
     * Fresh state per test: {@code /trips/} inherits from the owner-seeded root again and the note
     * exists. The ACLs a previous test wrote are removed straight from the store, as the webflux
     * HTTP tests do for their fixtures — a fixture must not depend on enforcement, and a test that
     * (deliberately) writes a bad ACL must not be able to lock the next test out.
     */
    @BeforeEach
    void resetTrips() throws Exception {
        ResourceStore store = server.getBean(ResourceStore.class);
        for (String path : List.of("/trips/.acl", "/trips/lisbon.acl")) {
            ResourceIdentifier acl = new ResourceIdentifier(URI.create(base + path));
            store.exists(acl).filter(Boolean::booleanValue).flatMap(exists -> store.delete(acl)).block();
        }
        int status = owner("PUT", "/trips/lisbon", TURTLE, NOTE).statusCode();
        assertTrue(status == 201 || status == 204, "the note exists: " + status);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // ---- driving the command ---------------------------------------------------------------

    private int cistern(String... args) {
        String[] full = new String[args.length + 4];
        System.arraycopy(args, 0, full, 0, args.length);
        full[args.length] = Usage.BASE_OPTION;
        full[args.length + 1] = base;
        full[args.length + 2] = Usage.TOKEN_OPTION;
        full[args.length + 3] = TOKEN;
        return CisternCli.execute(full, new PrintWriter(stdout, true), new PrintWriter(stderr, true));
    }

    private static HttpResponse<String> request(String method, String path, String token, String contentType,
                                                String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) {
            builder.header(HttpHeaderName.AUTHORIZATION.fieldName(), new BearerToken(token).headerValue());
        }
        if (contentType != null) {
            builder.header(HttpHeaderName.CONTENT_TYPE.fieldName(), contentType);
        }
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> owner(String method, String path, String contentType, String body)
            throws Exception {
        return request(method, path, TOKEN, contentType, body);
    }

    private static int agent(String method, String path) throws Exception {
        return request(method, path, null, null, null).statusCode();
    }

    // ---- the demo, beats 3 and 5 -----------------------------------------------------------

    @Nested
    @DisplayName("k8s/demo.sh beats 3 and 5, with the CLI in place of hand-written Turtle")
    class Demo {

        @Test
        @DisplayName("grant public read on /trips/: agent GET 200, DELETE 401; revoke: GET 401, owner 200")
        void grantThenRevoke() throws Exception {
            assertEquals(401, agent("GET", "/trips/lisbon"), "beat 2: no grant, denied");

            assertEquals(ExitCode.OK.code(), cistern("grant", "public", "--read", "/trips/"), stderr.toString());
            String granted = stdout.toString();
            assertTrue(granted.contains(CliMessage.GRANTED.format(
                    CliMessage.ANYONE.format(), AccessMode.READ.headerToken(),
                    CliMessage.TARGET_CONTAINER.format("/trips/"))), granted);
            assertTrue(granted.contains(OWNER), "the owner is re-stated and reported: " + granted);

            assertEquals(200, agent("GET", "/trips/lisbon"), "beat 4: inside the grant");
            assertEquals(401, agent("DELETE", "/trips/lisbon"), "beat 4: read is not write");
            assertEquals(200, owner("GET", "/trips/lisbon", null, null).statusCode());

            assertEquals(ExitCode.OK.code(), cistern("revoke", "public", "/trips/"), stderr.toString());
            assertTrue(stdout.toString().contains(
                    CliMessage.REVOKED.format(CliMessage.ANYONE.format(), "/trips/")), stdout.toString());

            assertEquals(401, agent("GET", "/trips/lisbon"), "beat 5: the very next request");
            assertEquals(200, owner("GET", "/trips/lisbon", null, null).statusCode(), "beat 5: owner unaffected");
        }

        @Test
        @DisplayName("what the CLI wrote is what the engine reads: the owner keeps Control under /trips/")
        void ownerKeepsControl() throws Exception {
            assertEquals(ExitCode.OK.code(), cistern("grant", ALICE, "--read", "--write", "/trips/"));

            // Control is what lets the owner read and write the ACL itself.
            HttpResponse<String> acl = owner("GET", "/trips/.acl", null, null);
            assertEquals(200, acl.statusCode());
            assertTrue(acl.body().contains(OWNER), acl.body());
            assertTrue(acl.body().contains(ALICE), acl.body());
            assertEquals(201, owner("PUT", "/trips/paris", TURTLE, NOTE).statusCode(), "owner still writes inside");
        }

        @Test
        @DisplayName("a document grant reaches the document only, not its container")
        void documentGrant() throws Exception {
            assertEquals(ExitCode.OK.code(), cistern("grant", "public", "--read", "/trips/lisbon"), stderr.toString());

            assertEquals(200, agent("GET", "/trips/lisbon"));
            assertEquals(401, agent("GET", "/trips/"), "the container was not granted");
        }
    }

    // ---- refusals and exit codes -----------------------------------------------------------

    @Nested
    @DisplayName("Refusals are the server's, and the exit code says so")
    class Refusals {

        @Test
        @DisplayName("without a credential the server answers 401 and the command exits 2")
        void anonymousIsRefused() {
            int exit = CisternCli.execute(
                    new String[] {"grant", "public", "--read", "/trips/", Usage.BASE_OPTION, base, Usage.TOKEN_OPTION, ""},
                    new PrintWriter(stdout, true), new PrintWriter(stderr, true));

            assertEquals(ExitCode.REFUSED.code(), exit, stderr.toString());
            assertTrue(stderr.toString().contains(CliMessage.NO_CREDENTIAL.format(ServerOptions.TOKEN_ENV)));
        }

        @Test
        @DisplayName("revoking the owner would drop Control: refused locally, exit 2, nothing written")
        void revokingControlIsRefused() throws Exception {
            String before = owner("GET", "/.acl", null, null).body();

            assertEquals(ExitCode.REFUSED.code(), cistern("revoke", OWNER, "/trips/"), stderr.toString());

            assertEquals(before, owner("GET", "/.acl", null, null).body(), "root ACL untouched");
            assertEquals(404, owner("GET", "/trips/.acl", null, null).statusCode(), "no resource-level ACL created");
        }

        @Test
        @DisplayName("revoking what was never granted is not an error and writes nothing")
        void nothingToRevoke() throws Exception {
            assertEquals(ExitCode.OK.code(), cistern("revoke", "public", "/trips/"), stderr.toString());

            assertTrue(stdout.toString().contains(
                    CliMessage.NOTHING_TO_REVOKE.format(CliMessage.ANYONE.format(), "/trips/")), stdout.toString());
            assertEquals(404, owner("GET", "/trips/.acl", null, null).statusCode());
        }

        @Test
        @DisplayName("bad arguments exit 1, not picocli's default 2 (which would read as 'refused')")
        void badArgumentsExitOne() {
            assertEquals(ExitCode.FAILURE.code(), cistern("grant", "public", "/trips/"), "no mode");
            assertEquals(ExitCode.FAILURE.code(), cistern("grant", "public", "--read", "trips/"), "relative path");
            assertEquals(ExitCode.FAILURE.code(), cistern("grant", "not a webid", "--read", "/trips/"), "bad grantee");
        }

        @Test
        @DisplayName("a server that is not there is a failure, exit 1")
        void unreachableServer() {
            int exit = CisternCli.execute(
                    new String[] {"grant", "public", "--read", "/trips/", Usage.BASE_OPTION, "http://127.0.0.1:9",
                        Usage.TOKEN_OPTION, TOKEN},
                    new PrintWriter(stdout, true), new PrintWriter(stderr, true));

            assertEquals(ExitCode.FAILURE.code(), exit, stderr.toString());
        }
    }

    // ---- the conditional write ------------------------------------------------------------

    @Nested
    @DisplayName("Writes are conditional: a concurrent edit is retried once from a fresh read, then reported")
    class ConditionalWrite {

        private final ResourceIdentifier trips = new ResourceIdentifier(URI.create(base + "/trips/"));

        /** A real editor over the real transport, with {@code interference} performed before the CLI's writes. */
        private AclEditor editorWith(AclTransport interfering) {
            return new AclEditor(new RemoteAclDiscovery(interfering), interfering, new GrantService());
        }

        /**
         * Another owner session that writes a competing grant to the same ACL, for real, over HTTP —
         * a valid one (the owner stays in), so what is being tested is the conditional write and not
         * a lock-out. {@code modes} varies per call so every competing write changes the graph.
         */
        private Mono<Void> someoneElseGrantsAlice(AccessMode... modes) {
            PodClient other = PodClient.connect(Optional.of(new BearerToken(TOKEN)));
            AclEditor editor = new AclEditor(new RemoteAclDiscovery(other), other, new GrantService());
            return editor.grant(new GrantRequest(trips, new Grantee.WebId(URI.create(ALICE)), EnumSet.of(modes[0], modes)))
                    .then();
        }

        @Test
        @DisplayName("one interposed edit: 412, re-read, retry — both grants end up in the ACL")
        void retriesOnceFromAFreshRead() {
            PodClient real = PodClient.connect(Optional.of(new BearerToken(TOKEN)));
            AtomicInteger puts = new AtomicInteger();
            AclTransport interfering = new AclTransport() {
                @Override
                public Mono<AclFetch> fetch(ResourceIdentifier acl) {
                    return real.fetch(acl);
                }

                @Override
                public Mono<Void> put(ResourceIdentifier acl, Model graph, WritePrecondition precondition) {
                    Mono<Void> before = puts.getAndIncrement() == 0 ? someoneElseGrantsAlice(AccessMode.READ) : Mono.empty();
                    return before.then(real.put(acl, graph, precondition));
                }
            };

            GrantRequest publicRead = new GrantRequest(trips, Grantee.PUBLIC, EnumSet.of(AccessMode.READ));
            StepVerifier.create(editorWith(interfering).grant(publicRead))
                    .assertNext(outcome -> {
                        assertEquals(AclResource.of(trips), outcome.aclResource());
                        assertTrue(outcome.authorizations().stream()
                                .anyMatch(a -> a.agents().contains(URI.create(ALICE))), "the concurrent grant survived");
                        assertTrue(outcome.authorizations().stream()
                                .anyMatch(a -> !a.agentClasses().isEmpty()), "and ours was applied on top of it");
                    })
                    .verifyComplete();
            assertEquals(2, puts.get(), "first PUT hit 412, second succeeded");
        }

        @Test
        @DisplayName("an edit that keeps landing between read and write is reported as a conflict; nothing written")
        void reportsConflictAfterTheRetry() throws Exception {
            PodClient real = PodClient.connect(Optional.of(new BearerToken(TOKEN)));
            AtomicInteger puts = new AtomicInteger();
            AclTransport alwaysInterfered = new AclTransport() {
                @Override
                public Mono<AclFetch> fetch(ResourceIdentifier acl) {
                    return real.fetch(acl);
                }

                @Override
                public Mono<Void> put(ResourceIdentifier acl, Model graph, WritePrecondition precondition) {
                    // A different competing grant each time, so every If-Match / If-None-Match is stale.
                    AccessMode[] competing = AccessMode.values();
                    return someoneElseGrantsAlice(competing[puts.getAndIncrement() % competing.length])
                            .then(real.put(acl, graph, precondition));
                }
            };

            GrantRequest publicRead = new GrantRequest(trips, Grantee.PUBLIC, EnumSet.of(AccessMode.READ));
            StepVerifier.create(editorWith(alwaysInterfered).grant(publicRead))
                    .expectError(CliFailure.Conflict.class)
                    .verify();
            assertEquals(1 + AclEditor.RETRIES_ON_CONFLICT, puts.get(), "the write was attempted, retried once, then given up");
            assertEquals(401, agent("GET", "/trips/lisbon"), "our grant was never written");
        }
    }

    // ---- what the transcript looks like ---------------------------------------------------

    @Test
    @DisplayName("the report is read back off the graph, in plain language")
    void reportReadsBackTheGraph() {
        assertEquals(ExitCode.OK.code(), cistern("grant", "public", "--read", "/trips/"), stderr.toString());

        List<String> lines = stdout.toString().lines().toList();
        assertEquals(CliMessage.GRANTED.format(CliMessage.ANYONE.format(), AccessMode.READ.headerToken(),
                CliMessage.TARGET_CONTAINER.format("/trips/")), lines.get(0));
        assertEquals(CliMessage.ACL_HOLDS.format("/trips/.acl"), lines.get(1));
        assertTrue(lines.contains(CliMessage.AUTHORIZATION_LINE.format(CliMessage.ANYONE.format(),
                AccessMode.READ.headerToken(), CliMessage.SCOPE_INHERITABLE.format())), lines.toString());
    }
}
