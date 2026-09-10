package com.enrichmeai.cistern.cli;

import java.util.Objects;

/**
 * One action performed and what it did — the unit the transcript prints, one line each, as the
 * run goes.
 */
record SyncStep(SyncAction action, SyncEffect effect) {

    SyncStep {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(effect, "effect");
    }
}
