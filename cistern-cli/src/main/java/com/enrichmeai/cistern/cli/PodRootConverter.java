package com.enrichmeai.cistern.cli;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * {@code --root} on the command line → {@link PodPath}, with one rule on top of the path's own:
 * a pod root is a container. A document cannot be a pod — only a container's ACL can carry the
 * {@code acl:default} that makes a subtree the owner's — so the tool refuses it before sending
 * anything, as a usage error.
 */
final class PodRootConverter implements ITypeConverter<PodPath> {

    @Override
    public PodPath convert(String value) {
        PodPath path;
        try {
            path = new PodPath(value.strip());
        } catch (IllegalArgumentException e) {
            throw new TypeConversionException(CliMessage.INVALID_ROOT.format(value));
        }
        if (!path.isContainer()) {
            throw new TypeConversionException(CliMessage.INVALID_ROOT.format(value));
        }
        return path;
    }
}
