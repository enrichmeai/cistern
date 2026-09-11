package com.enrichmeai.cistern.wac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.net.URI;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The invariants of a decision that carries its policy (T5.9). */
class AccessDecisionTest {

    private static final ResourceIdentifier ACL =
            new ResourceIdentifier(URI.create("https://pod.example/notes/.acl"));
    private static final URI RULE = URI.create("https://pod.example/notes/.acl#owner");

    @Test
    @DisplayName("a denial names no policy: decidedBy on an empty decision is refused")
    void denialCannotNameAPolicy() {
        assertThrows(IllegalArgumentException.class,
                () -> new AccessDecision(Set.of(), Optional.of(ACL), Set.of(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new AccessDecision(Set.of(), Optional.empty(), Set.of(RULE), Optional.empty()));
    }

    @Test
    @DisplayName("a grant names its policy: modes without a decidedBy are refused")
    void grantMustNameAPolicy() {
        assertThrows(IllegalArgumentException.class,
                () -> new AccessDecision(EnumSet.of(AccessMode.READ), Optional.empty(), Set.of(), Optional.empty()));
    }

    @Test
    @DisplayName("AccessDecision.of collapses no modes to the canonical DENIED")
    void ofCollapsesToDenied() {
        assertSame(AccessDecision.DENIED, AccessDecision.of(Set.of(), ACL, Set.of(RULE)));
    }

    @Test
    @DisplayName("a grant may name no rule — a blank-node authorization has no IRI")
    void grantMayNameNoRule() {
        AccessDecision decision = AccessDecision.of(EnumSet.of(AccessMode.READ), ACL, Set.of());

        assertTrue(decision.allows(AccessMode.READ));
        assertEquals(Optional.of(ACL), decision.decidedBy());
        assertTrue(decision.authorizations().isEmpty());
    }

    @Test
    @DisplayName("DENIED is denied, names nothing, is narrowed by nothing, and is what isDenied() means")
    void deniedIsEmpty() {
        assertTrue(AccessDecision.DENIED.isDenied());
        assertTrue(AccessDecision.DENIED.decidedBy().isEmpty());
        assertTrue(AccessDecision.DENIED.authorizations().isEmpty());
        assertTrue(AccessDecision.DENIED.narrowedBy().isEmpty());
        assertEquals("", AccessDecision.DENIED.toHeaderModes());
    }

    // ---- the delegation term (T6.5, AD-DEL-5) ---------------------------------------------

    @Test
    @DisplayName("a denial a delegation caused still names no policy, but names the term — and is not DENIED")
    void narrowedDenialNamesTheTermAndNoPolicy() {
        AccessDecision capped = AccessDecision.of(Set.of(), ACL, Set.of(RULE), Optional.of(DelegationTerm.CLIENT));

        assertTrue(capped.isDenied());
        assertTrue(capped.isNarrowed());
        assertEquals(Optional.of(DelegationTerm.CLIENT), capped.narrowedBy());
        assertTrue(capped.decidedBy().isEmpty(), "no rule refused; a denial names no policy");
        assertTrue(capped.authorizations().isEmpty());
        assertNotSame(AccessDecision.DENIED, capped);
        assertNotEquals(AccessDecision.DENIED, capped, "'never had it' and 'the delegation capped it' differ");
    }

    @Test
    @DisplayName("a grant may be narrowed too: some modes remain, and the term says others were capped")
    void narrowedGrantKeepsItsPolicy() {
        AccessDecision capped = AccessDecision.of(
                EnumSet.of(AccessMode.READ), ACL, Set.of(RULE), Optional.of(DelegationTerm.CLIENT));

        assertTrue(capped.allows(AccessMode.READ));
        assertEquals(Optional.of(ACL), capped.decidedBy());
        assertEquals(Optional.of(DelegationTerm.CLIENT), capped.narrowedBy());
    }

    @Test
    @DisplayName("the three-argument factory applies no term — what every pre-delegation caller gets")
    void threeArgumentFactoryIsUnnarrowed() {
        assertTrue(AccessDecision.of(EnumSet.of(AccessMode.READ), ACL, Set.of()).narrowedBy().isEmpty());
        assertSame(AccessDecision.DENIED, AccessDecision.of(Set.of(), ACL, Set.of()));
    }
}
