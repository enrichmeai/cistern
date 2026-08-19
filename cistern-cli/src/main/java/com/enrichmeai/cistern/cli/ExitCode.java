package com.enrichmeai.cistern.cli;

/**
 * The process exit codes the {@code cistern} command can end with — a closed set, so an enum
 * (ground rule 7), and the contract a script can branch on.
 *
 * <p>The numeric values are also needed as annotation constants (picocli's
 * {@code exitCodeOnInvalidInput} and friends must be compile-time constants), which is why
 * they are spelled once in {@link Values} and the enum wraps them rather than the other way
 * round. picocli's own default for a usage error is 2, which would collide with
 * {@link #REFUSED}; every command therefore pins invalid input to {@link #FAILURE}.
 */
public enum ExitCode {

    /** The operation completed — including "nothing to do", which is not a failure. */
    OK(Values.OK),

    /** Anything else went wrong: bad arguments, network failure, an unexpected server response. */
    FAILURE(Values.FAILURE),

    /**
     * The operation was refused and retrying will not help: the server answered 401 or 403 —
     * granting on a resource requires {@code acl:Control} there, and the server, not this tool,
     * enforces that — or the grant service refused a revoke that would remove Control.
     */
    REFUSED(Values.REFUSED),

    /**
     * The ACL changed under us: the write failed its precondition (412) even after the one
     * automatic re-read and retry. Nothing was written; running the command again will pick
     * up the new state.
     */
    CONFLICT(Values.CONFLICT);

    /** The numeric codes, as compile-time constants for annotation use. */
    public static final class Values {
        public static final int OK = 0;
        public static final int FAILURE = 1;
        public static final int REFUSED = 2;
        public static final int CONFLICT = 3;

        private Values() {
            // constants only
        }
    }

    private final int code;

    ExitCode(int code) {
        this.code = code;
    }

    /** The process exit status. */
    public int code() {
        return code;
    }
}
