package com.enrichmeai.cistern.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Deliberately failing test that exists ONLY to verify the CI gate (T0.3 DoD:
 * "a deliberately failing test on a branch fails the check"). This class lives on a
 * throwaway branch, must never be merged, and will be deleted once the failing CI run
 * has been captured as evidence.
 */
class CiGateVerificationTest {

    @Test
    void ciGateMustFailThisBranch() {
        assertEquals(1, 2, "Intentional failure: proves the CI gate blocks a red build (T0.3)");
    }
}
