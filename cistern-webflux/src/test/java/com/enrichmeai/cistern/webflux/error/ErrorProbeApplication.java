package com.enrichmeai.cistern.webflux.error;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only Boot application for the error-mapper tests. Component-scans just
 * {@code com.enrichmeai.cistern.webflux.error}, so it picks up the production handler and the
 * {@link ErrorProbeRoutes} probe routes and nothing else — in particular it does not depend
 * on the resource routes T2.1 is building in a sibling package.
 */
@SpringBootApplication
class ErrorProbeApplication {
}
