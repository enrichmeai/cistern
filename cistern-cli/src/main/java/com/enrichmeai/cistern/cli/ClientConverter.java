package com.enrichmeai.cistern.cli;

import java.net.URI;
import java.net.URISyntaxException;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * {@code --client <uri>} on the command line → a client identifier: an absolute URI, and
 * nothing else — what an access token's {@code client_id} (or {@code azp}) carries when it
 * names a client a policy can be written against. Like {@link WebIdConverter} there is no
 * keyword: a grant is constrained to a named client or it is not constrained at all, and the
 * latter is spelled by leaving the option out.
 */
final class ClientConverter implements ITypeConverter<URI> {

    @Override
    public URI convert(String value) {
        try {
            URI client = new URI(value.strip());
            if (!client.isAbsolute()) {
                throw invalid(value);
            }
            return client;
        } catch (URISyntaxException e) {
            throw invalid(value);
        }
    }

    private static TypeConversionException invalid(String value) {
        return new TypeConversionException(CliMessage.INVALID_CLIENT.format(value));
    }
}
