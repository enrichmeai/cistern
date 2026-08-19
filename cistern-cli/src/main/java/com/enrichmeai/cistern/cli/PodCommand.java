package com.enrichmeai.cistern.cli;

import picocli.CommandLine.Command;

/**
 * {@code cistern pod …}: the group for operations on pods as wholes. Today one member,
 * {@link PodCreateCommand}; a group rather than a flat {@code pod-create}, so that the noun
 * has room to grow ({@code pod list}, {@code pod owner}) without renaming what is here.
 *
 * <p>Not itself runnable: {@code cistern pod} with no subcommand is a usage error, exit
 * {@link ExitCode#FAILURE} like every other bad invocation.
 */
@Command(name = Usage.POD_NAME, description = Usage.POD_DESCRIPTION,
        mixinStandardHelpOptions = true,
        subcommands = {PodCreateCommand.class},
        exitCodeOnInvalidInput = ExitCode.Values.FAILURE,
        exitCodeOnExecutionException = ExitCode.Values.FAILURE)
final class PodCommand {
}
