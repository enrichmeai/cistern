package com.enrichmeai.cistern.mcp;

import java.net.http.HttpRequest;
import java.util.Objects;

/**
 * The condition a conditional {@code PUT} is made under (RFC 9110 §13.1), the same closed set
 * cistern-cli writes ACLs with: replace only what was read ({@code If-Match}), or create only
 * ({@code If-None-Match: *}). {@link Unconditional} exists for the {@code write-resource} tool,
 * whose caller may choose to write without a precondition.
 */
sealed interface WritePrecondition
        permits WritePrecondition.IfMatch, WritePrecondition.IfNoneMatchAny,
        WritePrecondition.Unconditional {

    /** Add this precondition's header, if any, to {@code request}. */
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

    /** No precondition: create or replace whatever is there. */
    record Unconditional() implements WritePrecondition {

        @Override
        public HttpRequest.Builder apply(HttpRequest.Builder request) {
            return request;
        }
    }
}
