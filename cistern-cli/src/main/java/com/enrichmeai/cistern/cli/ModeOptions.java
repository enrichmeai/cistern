package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.wac.AccessMode;

import java.util.EnumSet;
import java.util.Set;

import picocli.CommandLine.Option;

/**
 * {@code --read --write --append --control}: one flag per {@link AccessMode}, at least one
 * required (the enclosing {@code @ArgGroup(multiplicity = "1")} enforces that). Flags rather
 * than {@code --mode read,write} so that a shell completion, a typo and a script all read the
 * same way as the ACL will.
 */
final class ModeOptions {

    @Option(names = Usage.READ_OPTION, description = Usage.READ_DESCRIPTION)
    boolean read;

    @Option(names = Usage.WRITE_OPTION, description = Usage.WRITE_DESCRIPTION)
    boolean write;

    @Option(names = Usage.APPEND_OPTION, description = Usage.APPEND_DESCRIPTION)
    boolean append;

    @Option(names = Usage.CONTROL_OPTION, description = Usage.CONTROL_DESCRIPTION)
    boolean control;

    /** The modes asked for, as typed; {@code GrantRequest} closes them under implication. */
    Set<AccessMode> modes() {
        Set<AccessMode> modes = EnumSet.noneOf(AccessMode.class);
        if (read) {
            modes.add(AccessMode.READ);
        }
        if (write) {
            modes.add(AccessMode.WRITE);
        }
        if (append) {
            modes.add(AccessMode.APPEND);
        }
        if (control) {
            modes.add(AccessMode.CONTROL);
        }
        return modes;
    }
}
