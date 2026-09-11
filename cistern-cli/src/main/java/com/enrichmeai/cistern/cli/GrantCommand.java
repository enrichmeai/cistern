package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.wac.GrantRequest;
import com.enrichmeai.cistern.wac.Grantee;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code cistern grant <webid|public> --read|--write|--append|--control [--client <uri>]... <path>}.
 *
 * <p>{@code --client} narrows the grant to requests that come through the named client
 * ({@code cistern:client}, ADR 0004); the grantee is still what the grant is for, and a grant
 * with clients and no grantee has no spelling here — the positional is required — which is the
 * authoring half of AD-DEL-1.
 */
@Command(name = Usage.GRANT_NAME, description = Usage.GRANT_DESCRIPTION,
        mixinStandardHelpOptions = true, sortOptions = false,
        exitCodeOnInvalidInput = ExitCode.Values.FAILURE,
        exitCodeOnExecutionException = ExitCode.Values.FAILURE)
final class GrantCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Parameters(index = "0", paramLabel = Usage.GRANTEE_PARAM, description = Usage.GRANTEE_DESCRIPTION,
            converter = GranteeConverter.class)
    Grantee grantee;

    @Parameters(index = "1", paramLabel = Usage.PATH_PARAM, description = Usage.PATH_DESCRIPTION,
            converter = PodPathConverter.class)
    PodPath path;

    @ArgGroup(exclusive = false, multiplicity = "1", heading = Usage.MODES_HEADING)
    ModeOptions modes;

    @Option(names = Usage.CLIENT_OPTION, paramLabel = Usage.CLIENT_PARAM, description = Usage.CLIENT_DESCRIPTION,
            converter = ClientConverter.class)
    List<URI> clients = new ArrayList<>();

    @Mixin
    ServerOptions server;

    @Override
    public Integer call() {
        Session session = Session.open(server, spec.commandLine().getOut(), spec.commandLine().getErr());
        ResourceIdentifier target = session.base().resolve(path);
        GrantRequest request = new GrantRequest(target, grantee, modes.modes(), new LinkedHashSet<>(clients));
        return session.run(session.editor().grant(request)
                        .map(outcome -> session.report().grant(
                                outcome, target, grantee, request.modes(), request.clients())))
                .code();
    }
}
