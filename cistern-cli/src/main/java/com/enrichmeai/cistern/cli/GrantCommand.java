package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.wac.GrantRequest;
import com.enrichmeai.cistern.wac.Grantee;

import java.util.concurrent.Callable;

import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code cistern grant <webid|public> --read|--write|--append|--control <path>}.
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

    @Mixin
    ServerOptions server;

    @Override
    public Integer call() {
        Session session = Session.open(server, spec.commandLine().getOut(), spec.commandLine().getErr());
        ResourceIdentifier target = session.base().resolve(path);
        GrantRequest request = new GrantRequest(target, grantee, modes.modes());
        return session.run(session.editor().grant(request)
                        .map(outcome -> session.report().grant(outcome, target, grantee, request.modes())))
                .code();
    }
}
