package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.wac.PodSpec;

import java.net.URI;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code cistern pod create --root </firms/acme/> --owner <webid>} (T5.6).
 *
 * <p>The same pod boot-time seeding would make from {@code cistern.pods.seed[]}, made over HTTP
 * with the caller's credential — so a hosting operator, or an application's backend acting as
 * one, can add a pod to a running server without a restart, and the server decides whether
 * they may. See {@link RemotePodProvisioner} for the sequence and its guarantees.
 */
@Command(name = Usage.POD_CREATE_NAME, description = Usage.POD_CREATE_DESCRIPTION,
        mixinStandardHelpOptions = true, sortOptions = false,
        exitCodeOnInvalidInput = ExitCode.Values.FAILURE,
        exitCodeOnExecutionException = ExitCode.Values.FAILURE)
final class PodCreateCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(names = Usage.ROOT_OPTION, description = Usage.ROOT_DESCRIPTION, required = true,
            converter = PodRootConverter.class, paramLabel = Usage.PATH_PARAM)
    PodPath root;

    @Option(names = Usage.OWNER_OPTION, description = Usage.OWNER_DESCRIPTION, required = true,
            converter = WebIdConverter.class, paramLabel = Usage.WEBID_PARAM)
    URI owner;

    @Mixin
    ServerOptions server;

    @Override
    public Integer call() {
        Session session = Session.open(server, spec.commandLine().getOut(), spec.commandLine().getErr());
        PodSpec pod = new PodSpec(session.base().resolve(root), owner);
        return session.run(session.provisioner().provision(pod)
                        .map(outcome -> session.report().provisioned(outcome, pod)))
                .code();
    }
}
