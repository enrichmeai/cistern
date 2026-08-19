package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.wac.GrantService;

import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;

import reactor.core.publisher.Mono;

/**
 * Everything a subcommand needs once the options are parsed: the server, the credential, the
 * editor and the provisioner over them, and the report writer. Built once per invocation from
 * {@link ServerOptions}.
 *
 * <p>{@link #run} is <strong>the</strong> reactive-to-synchronous boundary of the tool. The
 * whole operation — discover, transform, write, retry — is one {@code Mono}; a command-line
 * process has nothing else to do while it runs and must exit with its result, so blocking here,
 * once, at the very top, is the honest shape (ground rule 3 is about the server, where blocking
 * a request thread starves other requests; there are no other requests here).
 */
final class Session {

    private final PodBase base;
    private final AclEditor editor;
    private final RemotePodProvisioner provisioner;
    private final AclReport report;
    private final PrintWriter out;
    private final PrintWriter err;

    Session(PodBase base, AclEditor editor, RemotePodProvisioner provisioner, AclReport report,
            PrintWriter out, PrintWriter err) {
        this.base = Objects.requireNonNull(base, "base");
        this.editor = Objects.requireNonNull(editor, "editor");
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner");
        this.report = Objects.requireNonNull(report, "report");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
    }

    static Session open(ServerOptions options, PrintWriter out, PrintWriter err) {
        if (options.credential().isEmpty()) {
            err.println(CliMessage.NO_CREDENTIAL.format(ServerOptions.TOKEN_ENV));
        }
        PodClient client = PodClient.connect(options.credential());
        AclEditor editor = new AclEditor(new RemoteAclDiscovery(client), client, new GrantService());
        return new Session(options.base, editor, new RemotePodProvisioner(client),
                new AclReport(options.base), out, err);
    }

    PodBase base() {
        return base;
    }

    AclEditor editor() {
        return editor;
    }

    RemotePodProvisioner provisioner() {
        return provisioner;
    }

    AclReport report() {
        return report;
    }

    /** Run the operation to completion, print its lines, and say the command succeeded. */
    ExitCode run(Mono<List<String>> lines) {
        List<String> printed = lines.block();
        if (printed != null) {
            printed.forEach(out::println);
        }
        out.flush();
        return ExitCode.OK;
    }
}
