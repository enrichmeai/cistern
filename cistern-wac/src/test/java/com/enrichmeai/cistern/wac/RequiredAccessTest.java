package com.enrichmeai.cistern.wac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link RequiredAccess}: the method-to-requirement table, and the row that overrides it —
 * an ACL resource requires Control on the resource it governs, whatever the method (#112).
 */
class RequiredAccessTest {

    private static final String ROOT = "https://pod.example/";
    private static final ResourceIdentifier CONTAINER = id(ROOT + "trips/");
    private static final ResourceIdentifier CONTAINER_ACL = id(ROOT + "trips/.acl");
    private static final ResourceIdentifier DOCUMENT = id(ROOT + "trips/lisbon");
    private static final ResourceIdentifier DOCUMENT_ACL = id(ROOT + "trips/lisbon.acl");

    private static ResourceIdentifier id(String uri) {
        return new ResourceIdentifier(URI.create(uri));
    }

    private static List<AccessRequirement> controlOn(ResourceIdentifier governed) {
        return List.of(new AccessRequirement(governed, AccessMode.CONTROL));
    }

    // ---------------------------------------------------------------- the ACL row

    @ParameterizedTest(name = "{0} /trips/.acl requires Control on /trips/")
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS", "PUT", "PATCH", "POST", "DELETE", "get", "PROPFIND"})
    @DisplayName("every method on a container's ACL requires Control on the container — nothing else")
    void everyMethodOnAContainerAclRequiresControlOnTheContainer(String method) {
        assertEquals(controlOn(CONTAINER), RequiredAccess.forRequest(method, CONTAINER_ACL));
    }

    @ParameterizedTest(name = "{0} /trips/lisbon.acl requires Control on /trips/lisbon")
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS", "PUT", "PATCH", "POST", "DELETE"})
    @DisplayName("every method on a document's ACL requires Control on the document — nothing else")
    void everyMethodOnADocumentAclRequiresControlOnTheDocument(String method) {
        assertEquals(controlOn(DOCUMENT), RequiredAccess.forRequest(method, DOCUMENT_ACL));
    }

    @Test
    @DisplayName("DELETE of an ACL is one requirement: Control on the governed resource, no parent Write")
    void deleteOfAnAclDoesNotRequireWriteOnTheParent() {
        List<AccessRequirement> requirements = RequiredAccess.forRequest("DELETE", DOCUMENT_ACL);
        assertEquals(1, requirements.size(), requirements.toString());
        assertEquals(controlOn(DOCUMENT), requirements);
    }

    @Test
    @DisplayName("forAcl is the row forRequest consults, and it refuses a non-ACL")
    void forAclIsTheSingleSourceOfTruth() {
        assertEquals(controlOn(CONTAINER), RequiredAccess.forAcl(CONTAINER_ACL));
        assertEquals(RequiredAccess.forAcl(DOCUMENT_ACL), RequiredAccess.forRequest("PUT", DOCUMENT_ACL));
        assertThrows(IllegalArgumentException.class, () -> RequiredAccess.forAcl(DOCUMENT),
                "a non-ACL has no governed resource; forRequest never routes one here");
    }

    // ---------------------------------------------------------------- the rest of the table is unchanged

    @Test
    @DisplayName("GET, HEAD, OPTIONS on an ordinary resource still require Read")
    void readMethodsRequireRead() {
        for (String method : List.of("GET", "HEAD", "OPTIONS")) {
            assertEquals(List.of(new AccessRequirement(DOCUMENT, AccessMode.READ)),
                    RequiredAccess.forRequest(method, DOCUMENT), method);
        }
    }

    @Test
    @DisplayName("PUT requires Write; POST and PATCH require Append")
    void writeMethodsRequireWriteOrAppend() {
        assertEquals(List.of(new AccessRequirement(DOCUMENT, AccessMode.WRITE)),
                RequiredAccess.forRequest("PUT", DOCUMENT));
        assertEquals(List.of(new AccessRequirement(CONTAINER, AccessMode.APPEND)),
                RequiredAccess.forRequest("POST", CONTAINER));
        assertEquals(List.of(new AccessRequirement(DOCUMENT, AccessMode.APPEND)),
                RequiredAccess.forRequest("PATCH", DOCUMENT));
    }

    @Test
    @DisplayName("DELETE of an ordinary resource still requires Write on it and on its parent")
    void deleteRequiresWriteOnResourceAndParent() {
        assertEquals(List.of(
                        new AccessRequirement(DOCUMENT, AccessMode.WRITE),
                        new AccessRequirement(CONTAINER, AccessMode.WRITE)),
                RequiredAccess.forRequest("DELETE", DOCUMENT));
    }

    @Test
    @DisplayName("receipts require Control on the resource asked about — on the resource, never its ACL")
    void receiptsRequireControlOnTheResource() {
        assertEquals(controlOn(CONTAINER), RequiredAccess.forReceipts(CONTAINER));
        assertEquals(controlOn(DOCUMENT), RequiredAccess.forReceipts(DOCUMENT));
    }
}
