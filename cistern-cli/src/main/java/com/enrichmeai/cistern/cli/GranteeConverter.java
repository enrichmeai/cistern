package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.wac.Grantee;

import java.net.URI;
import java.net.URISyntaxException;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * {@code <webid|public>} on the command line → {@link Grantee}: the word {@value #PUBLIC_KEYWORD}
 * is everyone; anything else must be an absolute WebID URI.
 */
final class GranteeConverter implements ITypeConverter<Grantee> {

    /** The one word that is not a WebID. */
    static final String PUBLIC_KEYWORD = "public";

    @Override
    public Grantee convert(String value) {
        if (PUBLIC_KEYWORD.equalsIgnoreCase(value.strip())) {
            return Grantee.PUBLIC;
        }
        try {
            URI webId = new URI(value.strip());
            if (!webId.isAbsolute()) {
                throw invalid(value);
            }
            return new Grantee.WebId(webId);
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw invalid(value);
        }
    }

    private static TypeConversionException invalid(String value) {
        return new TypeConversionException(CliMessage.INVALID_GRANTEE.format(value, PUBLIC_KEYWORD));
    }
}
