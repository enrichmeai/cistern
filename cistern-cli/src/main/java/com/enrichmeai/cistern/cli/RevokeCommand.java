package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.wac.Grantee;
import com.enrichmeai.cistern.wac.RevokeRequest;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code cistern revoke <webid|public> <path>}.
 */
@Command(name = Usage.REVOKE_NAME, description = Usage.REVOKE_DESCRIPTION,
        mixinStandardHelpOptions = true, sortOptions = false,
        exitCodeOnInvalidInput = ExitCode.Values.FAILURE,
        exitCodeOnExecutionException = ExitCode.Values.FAILURE)
final class RevokeCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Parameters(index = "0", paramLabel = Usage.GRANTEE_PARAM, description = Usage.GRANTEE_DESCRIPTION,
            converter = GranteeConverter.class)
    Grantee grantee;

    @Parameters(index = "1", paramLabel = Usage.PATH_PARAM, description = Usage.PATH_DESCRIPTION,
            converter = PodPathConverter.class)
    PodPath path;

    @Mixin
    ServerOptions server;

    @Override
    public Integer call() {
        Session session = Session.open(server, spec.commandLine().getOut(), spec.commandLine().getErr());
        ResourceIdentifier target = session.base().resolve(path);
        RevokeRequest request = new RevokeRequest(target, grantee);
        return session.run(session.editor().revoke(request)
                        .map(outcome -> session.report().revoke(outcome, target, grantee)))
                .code();
    }
}
