package com.enrichmeai.cistern.cli;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * {@code <pod-path>} on the command line → {@link PodPath}, with one rule on top of the path's
 * own: a folder mirrors into a container, so the path ends in {@code /} (Solid Protocol §3.1).
 * Refused before anything is sent, as a usage error, rather than minting document names under
 * something the server would treat as a document.
 */
final class ContainerPathConverter implements ITypeConverter<PodPath> {

    @Override
    public PodPath convert(String value) {
        PodPath path;
        try {
            path = new PodPath(value.strip());
        } catch (IllegalArgumentException e) {
            throw new TypeConversionException(CliMessage.INVALID_TARGET_CONTAINER.format(value));
        }
        if (!path.isContainer()) {
            throw new TypeConversionException(CliMessage.INVALID_TARGET_CONTAINER.format(value));
        }
        return path;
    }
}
