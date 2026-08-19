package com.enrichmeai.cistern.cli;

import java.net.http.HttpRequest;
import java.util.Objects;

/**
 * The condition a {@code PUT} of an ACL is made under, so that a write never silently overwrites
 * an edit made between the read and the write (RFC 9110 §13.1). A closed set of two, matching
 * the two ways discovery can have found the effective ACL:
 *
 * <ul>
 *   <li>the target had its own ACL — replace it only if it is still the one that was read
 *       ({@code If-Match: <etag>});</li>
 *   <li>the target inherited — create {@code <target>.acl} only if nobody else has since
 *       ({@code If-None-Match: *}).</li>
 * </ul>
 *
 * <p>Either failure is a 412, which the editor answers by re-reading once and retrying.
 */
sealed interface WritePrecondition permits WritePrecondition.IfMatch, WritePrecondition.IfNoneMatchAny {

    /** Add this precondition's header to {@code request}. */
    HttpRequest.Builder apply(HttpRequest.Builder request);

    /** Replace only the representation that was read. */
    record IfMatch(EntityTagHeader etag) implements WritePrecondition {

        public IfMatch {
            Objects.requireNonNull(etag, "etag");
        }

        @Override
        public HttpRequest.Builder apply(HttpRequest.Builder request) {
            return request.header(HttpHeaderName.IF_MATCH.fieldName(), etag.value());
        }
    }

    /** Create only; fail if any representation now exists. */
    record IfNoneMatchAny() implements WritePrecondition {

        /** RFC 9110 §13.1.2: {@code *} matches any current representation. */
        static final String ANY = "*";

        @Override
        public HttpRequest.Builder apply(HttpRequest.Builder request) {
            return request.header(HttpHeaderName.IF_NONE_MATCH.fieldName(), ANY);
        }
    }
}
