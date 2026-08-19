package com.enrichmeai.cistern.wac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.InMemoryResourceStore;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * {@link AccessControl#authorize}: the verdict a request gets, and what it names (T5.9). The
 * store, discovery and engine are the real ones; only the store is in memory.
 */
class AccessControlTest {

    private static final String ROOT = "https://pod.example/";
    private static final String ALICE = "https://alice.example/profile/card#me";
    private static final String BOB = "https://bob.example/profile/card#me";
    private static final Agent ALICE_AGENT = Agent.of(URI.create(ALICE));
    private static final Agent BOB_AGENT = Agent.of(URI.create(BOB));

    private InMemoryResourceStore store;
    private AccessControl accessControl;

    @BeforeEach
    void setUp() {
        store = new InMemoryResourceStore();
        accessControl = new AccessControl(new AclDiscovery(store), new WacEngine());
        // Root: Alice owns everything, by accessTo and default.
        writeAcl(ROOT, "<#owner> a acl:Authorization ;\n"
                + "  acl:agent <" + ALICE + "> ;\n"
                + "  acl:accessTo <" + ROOT + "> ; acl:default <" + ROOT + "> ;\n"
                + "  acl:mode acl:Read, acl:Write, acl:Append, acl:Control .");
    }

    private static ResourceIdentifier id(String uri) {
        return new ResourceIdentifier(URI.create(uri));
    }

    private void writeAcl(String governedResource, String body) {
        String turtle = "@prefix acl: <http://www.w3.org/ns/auth/acl#> .\n" + body;
        store.put(AclResource.of(id(governedResource)),
                        new Representation("text/turtle", turtle.getBytes(StandardCharsets.UTF_8)))
                .block();
    }

    @Test
    @DisplayName("an allowed GET names the ACL that granted it — the root's, when inherited")
    void allowedGetNamesTheInheritedAcl() {
        StepVerifier.create(accessControl.authorize(
                        RequiredAccess.forRequest("GET", id(ROOT + "notes/hello")), ALICE_AGENT))
                .assertNext(verdict -> {
                    assertTrue(verdict.allowed());
                    assertEquals(Optional.of(id(ROOT + ".acl")), verdict.decidedBy());
                    assertEquals(id(ROOT + "notes/hello"), verdict.primary().requirement().target());
                    assertEquals(AccessMode.READ, verdict.primary().requirement().mode());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("an allowed GET under a container's own ACL names that ACL, not the root's")
    void allowedGetNamesTheNearestAcl() {
        writeAcl(ROOT + "notes/", "<#owner> a acl:Authorization ;\n"
                + "  acl:agent <" + ALICE + "> ;\n"
                + "  acl:accessTo <" + ROOT + "notes/> ; acl:default <" + ROOT + "notes/> ;\n"
                + "  acl:mode acl:Read, acl:Write, acl:Control .");

        StepVerifier.create(accessControl.authorize(
                        RequiredAccess.forRequest("GET", id(ROOT + "notes/hello")), ALICE_AGENT))
                .assertNext(verdict -> assertEquals(
                        Optional.of(id(ROOT + "notes/.acl")), verdict.decidedBy()))
                .verifyComplete();
    }

    @Test
    @DisplayName("a refused request names no policy, even when some other mode was granted")
    void deniedNamesNothing() {
        // Bob may read /notes/ but a PUT needs Write: the ACL granted him something, but not
        // this — and the record must not blame (or credit) it.
        writeAcl(ROOT + "notes/", "<#owner> a acl:Authorization ;\n"
                + "  acl:agent <" + ALICE + "> ;\n"
                + "  acl:accessTo <" + ROOT + "notes/> ; acl:default <" + ROOT + "notes/> ;\n"
                + "  acl:mode acl:Read, acl:Write, acl:Control .\n"
                + "<#bob> a acl:Authorization ;\n"
                + "  acl:agent <" + BOB + "> ;\n"
                + "  acl:accessTo <" + ROOT + "notes/> ; acl:default <" + ROOT + "notes/> ;\n"
                + "  acl:mode acl:Read .");

        StepVerifier.create(accessControl.authorize(
                        RequiredAccess.forRequest("PUT", id(ROOT + "notes/hello")), BOB_AGENT))
                .assertNext(verdict -> {
                    assertFalse(verdict.allowed());
                    assertTrue(verdict.decidedBy().isEmpty(), "a denial names no policy");
                    assertTrue(verdict.primary().decision().allows(AccessMode.READ),
                            "the underlying decision still says what Bob does hold");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("DELETE judges the target first and the parent second; the primary is the target")
    void deleteHasTwoJudgementsAndThePrimaryIsTheTarget() {
        StepVerifier.create(accessControl.authorize(
                        RequiredAccess.forRequest("DELETE", id(ROOT + "notes/hello")), ALICE_AGENT))
                .assertNext(verdict -> {
                    assertEquals(2, verdict.judgements().size());
                    assertEquals(id(ROOT + "notes/hello"), verdict.primary().requirement().target());
                    assertEquals(id(ROOT + "notes/"), verdict.judgements().get(1).requirement().target());
                    assertTrue(verdict.allowed());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("DELETE refused by the PARENT is refused, and the record still describes the target")
    void deleteRefusedByParent() {
        // Bob holds Write on the document itself, nothing on its container.
        writeAcl(ROOT + "notes/hello", "<#bob> a acl:Authorization ;\n"
                + "  acl:agent <" + BOB + "> ;\n"
                + "  acl:accessTo <" + ROOT + "notes/hello> ;\n"
                + "  acl:mode acl:Write .");

        StepVerifier.create(accessControl.authorize(
                        RequiredAccess.forRequest("DELETE", id(ROOT + "notes/hello")), BOB_AGENT))
                .assertNext(verdict -> {
                    assertFalse(verdict.allowed());
                    assertTrue(verdict.judgements().get(0).satisfied(), "Write on the resource");
                    assertFalse(verdict.judgements().get(1).satisfied(), "but not on the parent");
                    assertEquals(id(ROOT + "notes/hello"), verdict.primary().requirement().target());
                    assertEquals(AccessMode.WRITE, verdict.primary().requirement().mode());
                    assertTrue(verdict.decidedBy().isEmpty());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("receipts require Control on the resource — Read alone is refused")
    void receiptsRequireControl() {
        writeAcl(ROOT + "notes/", "<#owner> a acl:Authorization ;\n"
                + "  acl:agent <" + ALICE + "> ;\n"
                + "  acl:accessTo <" + ROOT + "notes/> ; acl:default <" + ROOT + "notes/> ;\n"
                + "  acl:mode acl:Read, acl:Write, acl:Control .\n"
                + "<#bob> a acl:Authorization ;\n"
                + "  acl:agent <" + BOB + "> ;\n"
                + "  acl:accessTo <" + ROOT + "notes/> ; acl:default <" + ROOT + "notes/> ;\n"
                + "  acl:mode acl:Read .");
        List<AccessRequirement> receipts = RequiredAccess.forReceipts(id(ROOT + "notes/hello"));
        assertEquals(List.of(new AccessRequirement(id(ROOT + "notes/hello"), AccessMode.CONTROL)), receipts);

        StepVerifier.create(accessControl.authorize(receipts, BOB_AGENT))
                .assertNext(verdict -> assertFalse(verdict.allowed(), "Bob reads; Bob does not control"))
                .verifyComplete();
        StepVerifier.create(accessControl.authorize(receipts, ALICE_AGENT))
                .assertNext(verdict -> assertTrue(verdict.allowed()))
                .verifyComplete();
    }

    @Test
    @DisplayName("isAllowed is authorize().allowed() — one evaluation path, two shapes of answer")
    void isAllowedIsTheVerdict() {
        StepVerifier.create(accessControl.isAllowed("GET", id(ROOT + "notes/hello"), ALICE_AGENT))
                .expectNext(true).verifyComplete();
        StepVerifier.create(accessControl.isAllowed("GET", id(ROOT + "notes/hello"), BOB_AGENT))
                .expectNext(false).verifyComplete();
        StepVerifier.create(accessControl.isAllowed("GET", id(ROOT + "notes/hello"), Agent.ANONYMOUS))
                .expectNext(false).verifyComplete();
    }
}
