package com.enrichmeai.cistern.auth;

import com.nimbusds.jwt.JWTClaimsSet;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Claim and template mappings over the claims of tokens Keycloak issued (T4.0). */
class WebIdMappingTest {

    private static final JWTClaimsSet ALICE = Fixtures.claims("alice-valid");
    private static final JWTClaimsSet LEGAL = Fixtures.claims("valuedocs-legal-valid");

    private static URI webId(WebIdMapping.Result result) {
        return assertInstanceOf(WebIdMapping.Result.WebId.class, result, String.valueOf(result)).uri();
    }

    private static WebIdMapping.Result.Unmapped unmapped(WebIdMapping.Result result) {
        return assertInstanceOf(WebIdMapping.Result.Unmapped.class, result, String.valueOf(result));
    }

    @Test
    @DisplayName("Claim: the realm's webid user-attribute mapper puts a webid claim in the token")
    void claimMapping() {
        WebIdMapping mapping = new WebIdMapping.Claim("webid");
        assertEquals(Fixtures.ALICE, webId(mapping.webIdOf(ALICE)));
        assertEquals(Fixtures.VALUEDOCS_LEGAL, webId(mapping.webIdOf(LEGAL)));
    }

    @Test
    @DisplayName("Claim: a claim the token lacks is WEBID_MISSING, naming the claim")
    void claimAbsent() {
        WebIdMapping.Result.Unmapped result = unmapped(new WebIdMapping.Claim("solid_webid").webIdOf(ALICE));
        assertEquals(JwtRejectionReason.WEBID_MISSING, result.reason());
        assertEquals(AuthMessage.WEBID_CLAIM_ABSENT.format("solid_webid"), result.detail());
    }

    @Test
    @DisplayName("Claim: a claim that is not a URI is WEBID_INVALID — preferred_username, say")
    void claimNotAUri() {
        WebIdMapping.Result.Unmapped result = unmapped(new WebIdMapping.Claim("preferred_username").webIdOf(ALICE));
        assertEquals(JwtRejectionReason.WEBID_INVALID, result.reason());
        assertEquals("alice", result.detail());
    }

    @Test
    @DisplayName("Claim: a non-string claim (aud is a list here) is WEBID_MISSING")
    void claimNotAString() {
        assertEquals(JwtRejectionReason.WEBID_MISSING, unmapped(new WebIdMapping.Claim("aud").webIdOf(ALICE)).reason());
    }

    @Test
    @DisplayName("Template: {iss}/users/{sub}#me over the real iss and sub")
    void templateMapping() {
        WebIdMapping mapping = new WebIdMapping.Template("{iss}/users/{sub}#me");
        assertEquals(URI.create(Fixtures.ISSUER + "/users/" + ALICE.getSubject() + "#me"),
                webId(mapping.webIdOf(ALICE)));
    }

    @Test
    @DisplayName("Template: any string claim is a placeholder — azp names the client here")
    void templateOverOtherClaims() {
        WebIdMapping mapping = new WebIdMapping.Template("https://valuedocs.co.in/apps/{azp}#id");
        assertEquals(URI.create("https://valuedocs.co.in/apps/valuedocs-legal#id"), webId(mapping.webIdOf(LEGAL)));
    }

    @Test
    @DisplayName("Template: an unresolvable placeholder is WEBID_MISSING, naming it")
    void templatePlaceholderUnresolved() {
        WebIdMapping.Result.Unmapped result =
                unmapped(new WebIdMapping.Template("{iss}/users/{employee_id}#me").webIdOf(ALICE));
        assertEquals(JwtRejectionReason.WEBID_MISSING, result.reason());
        assertEquals(AuthMessage.WEBID_TEMPLATE_PLACEHOLDER_UNRESOLVED.format("employee_id"), result.detail());
    }

    @Test
    @DisplayName("Template: a result that is not absolute is WEBID_INVALID")
    void templateNotAbsolute() {
        assertEquals(JwtRejectionReason.WEBID_INVALID,
                unmapped(new WebIdMapping.Template("users/{sub}#me").webIdOf(ALICE)).reason());
    }

    @Test
    @DisplayName("a template with no placeholder would give every token one WebID: refused")
    void templateWithoutPlaceholderRefused() {
        assertThrows(IllegalArgumentException.class, () -> new WebIdMapping.Template("https://one.example/#me"));
        assertThrows(IllegalArgumentException.class, () -> new WebIdMapping.Claim(" "));
    }
}
