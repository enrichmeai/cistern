package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.CisternException;

import java.io.PrintWriter;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;

/**
 * The {@code cistern} command (T5.7): {@code grant} and {@code revoke}, over HTTP against a
 * running server, with the caller's own credential.
 *
 * <p>The tool authors the file; the server enforces who may. There is no privileged path — a
 * {@code cistern grant} is exactly the {@code PUT <target>.acl} the owner could do by hand, and
 * is refused exactly when that would be. What the tool adds is that the file it writes is
 * always the right shape (see {@code GrantService}): the owner is never locked out, a container
 * grant always reaches inside the container, and the result is read back and reported in plain
 * language.
 *
 * <p>Exit codes are {@link ExitCode}; the mapping from a failure to a code happens in one place,
 * {@link #exitCodeFor}, and every message is a {@link CliMessage}.
 */
@Command(name = Usage.COMMAND_NAME, description = Usage.COMMAND_DESCRIPTION,
        mixinStandardHelpOptions = true, versionProvider = CisternCli.Version.class,
        subcommands = {GrantCommand.class, RevokeCommand.class},
        exitCodeListHeading = Usage.EXIT_CODES_HEADING,
        exitCodeList = {Usage.EXIT_OK, Usage.EXIT_FAILURE, Usage.EXIT_REFUSED, Usage.EXIT_CONFLICT},
        exitCodeOnInvalidInput = ExitCode.Values.FAILURE,
        exitCodeOnExecutionException = ExitCode.Values.FAILURE)
public final class CisternCli {

    public static void main(String[] args) {
        System.exit(execute(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true)));
    }

    /** Run with the given streams; returns the exit status. Public so a test can drive it in-process. */
    public static int execute(String[] args, PrintWriter out, PrintWriter err) {
        return new CommandLine(new CisternCli())
                .setOut(out)
                .setErr(err)
                .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                    cmd.getErr().println(messageFor(ex));
                    cmd.getErr().flush();
                    return exitCodeFor(ex).code();
                })
                .execute(args);
    }

    /**
     * The one place a failure becomes an exit status.
     *
     * <p>{@code CisternException.Conflict} is how {@code GrantService} refuses a revoke that would
     * drop Control: refused, like a 403, because retrying will not help and the reason is policy.
     */
    static ExitCode exitCodeFor(Throwable failure) {
        return switch (failure) {
            case CliFailure.Refused _ -> ExitCode.REFUSED;
            case CliFailure.Conflict _ -> ExitCode.CONFLICT;
            case CliFailure.NoAcl _ -> ExitCode.FAILURE;
            case CliFailure.UnexpectedStatus _ -> ExitCode.FAILURE;
            case CliFailure.MissingValidator _ -> ExitCode.FAILURE;
            case CliFailure.Transport _ -> ExitCode.FAILURE;
            case CisternException.Conflict _ -> ExitCode.REFUSED;
            default -> ExitCode.FAILURE;
        };
    }

    static String messageFor(Throwable failure) {
        return switch (failure) {
            case CliFailure known -> known.getMessage();
            case CisternException.Conflict refused -> CliMessage.REVOKE_REFUSED.format(refused.getMessage());
            default -> CliMessage.FAILED.format(
                    failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName());
        };
    }

    /** {@code --version}: the jar's {@code Implementation-Version}, or a placeholder when run from classes. */
    static final class Version implements IVersionProvider {

        static final String UNPACKAGED = "development";

        @Override
        public String[] getVersion() {
            String version = CisternCli.class.getPackage().getImplementationVersion();
            return new String[] {Usage.COMMAND_NAME + " " + (version != null ? version : UNPACKAGED)};
        }
    }
}
