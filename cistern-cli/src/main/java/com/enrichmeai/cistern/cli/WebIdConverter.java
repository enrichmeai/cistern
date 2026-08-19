package com.enrichmeai.cistern.cli;

import java.net.URI;
import java.net.URISyntaxException;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * {@code --owner} on the command line → a WebID: an absolute URI, and nothing else. Unlike
 * {@link GranteeConverter} there is no keyword — a pod is owned by somebody, never by
 * "public".
 */
final class WebIdConverter implements ITypeConverter<URI> {

    @Override
    public URI convert(String value) {
        try {
            URI webId = new URI(value.strip());
            if (!webId.isAbsolute()) {
                throw invalid(value);
            }
            return webId;
        } catch (URISyntaxException e) {
            throw invalid(value);
        }
    }

    private static TypeConversionException invalid(String value) {
        return new TypeConversionException(CliMessage.INVALID_OWNER.format(value));
    }
}
