package com.enrichmeai.cistern.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import reactor.core.publisher.Mono;

/**
 * {@code cistern sync <local-dir> <pod-path> [--dry-run] [--delete]} (T7.17): make the container
 * at {@code <pod-path>} match the folder.
 *
 * <p>The plan is computed from the folder and {@code <local-dir>/.cistern-sync.json} alone
 * ({@link SyncPlan}); a dry run prints it and stops before a session is even opened. A real run
 * performs it in order ({@link Synchronizer}), printing each step as the server answers it —
 * a transcript, not a report at the end — and the counts last. Each step is remembered in the
 * state file as it succeeds; a run that stops partway resumes from there.
 *
 * <p>What the server refuses, this command reports exactly as the other commands do: 401/403
 * exit {@link ExitCode#REFUSED}, a copy that changed on the pod exits {@link ExitCode#CONFLICT}
 * and is left standing. There is no privileged path and no blind retry.
 */
@Command(name = Usage.SYNC_NAME, description = Usage.SYNC_DESCRIPTION,
        mixinStandardHelpOptions = true, sortOptions = false,
        exitCodeOnInvalidInput = ExitCode.Values.FAILURE,
        exitCodeOnExecutionException = ExitCode.Values.FAILURE)
final class SyncCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Parameters(index = "0", paramLabel = Usage.LOCAL_DIR_PARAM, description = Usage.LOCAL_DIR_DESCRIPTION)
    Path localDir;

    @Parameters(index = "1", paramLabel = Usage.PATH_PARAM, description = Usage.SYNC_TARGET_DESCRIPTION,
            converter = ContainerPathConverter.class)
    PodPath target;

    @Option(names = Usage.DRY_RUN_OPTION, description = Usage.DRY_RUN_DESCRIPTION)
    boolean dryRun;

    @Option(names = Usage.DELETE_OPTION, description = Usage.DELETE_DESCRIPTION)
    boolean delete;

    @Mixin
    ServerOptions server;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        Path folder = localDir.toAbsolutePath().normalize();
        SyncStateFile state = SyncStateFile.in(folder, server.base.resolve(target));
        LocalTree tree = LocalTree.walk(folder, state::owns);
        SyncReport report = new SyncReport(server.base);
        for (LocalTree.Skipped skipped : tree.skipped()) {
            err.println(report.skipped(skipped));
        }
        SyncPlan plan = SyncPlan.of(tree, state.current(), server.base, target, delete);

        if (dryRun) {
            report.plan(plan, localDir, target).forEach(out::println);
            out.flush();
            return ExitCode.OK.code();
        }
        Session session = Session.open(server, out, err);
        return session.run(session.synchronizer().sync(plan, state)
                        .doOnNext(step -> out.println(report.step(step)))
                        .then(Mono.fromSupplier(() -> report.summary(plan, localDir, target))))
                .code();
    }
}
