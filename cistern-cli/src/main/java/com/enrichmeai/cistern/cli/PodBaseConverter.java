package com.enrichmeai.cistern.cli;

import java.net.URI;
import java.net.URISyntaxException;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/** {@code --base} on the command line → {@link PodBase}. */
final class PodBaseConverter implements ITypeConverter<PodBase> {

    @Override
    public PodBase convert(String value) {
        try {
            return new PodBase(new URI(value.strip()));
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new TypeConversionException(CliMessage.INVALID_BASE.format(value));
        }
    }
}
