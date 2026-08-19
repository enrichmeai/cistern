package com.enrichmeai.cistern.cli;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/** {@code <path>} on the command line → {@link PodPath}, with the path's own rules deciding validity. */
final class PodPathConverter implements ITypeConverter<PodPath> {

    @Override
    public PodPath convert(String value) {
        try {
            return new PodPath(value.strip());
        } catch (IllegalArgumentException e) {
            throw new TypeConversionException(e.getMessage());
        }
    }
}
