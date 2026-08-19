package com.enrichmeai.cistern.cli;

import java.util.Optional;

import picocli.CommandLine.Option;

/**
 * Where the server is and who is calling — shared by every subcommand as a picocli mixin.
 *
 * <p>The token defaults to the {@value #TOKEN_ENV} environment variable so it need not appear
 * in shell history; {@code --token} overrides it. No token is allowed — the request goes out
 * anonymous, the server answers 401, and the tool says why — because "you must authenticate"
 * is the server's message to give, and giving it locally would mean guessing at the server's
 * policy.
 */
final class ServerOptions {

    /** The environment variable {@code --token} falls back to; the same one {@code k8s/demo.sh} reads. */
    static final String TOKEN_ENV = "CISTERN_TOKEN";

    /** picocli's syntax for "default from this environment variable". */
    private static final String TOKEN_DEFAULT = "${env:" + TOKEN_ENV + "}";

    @Option(names = Usage.BASE_OPTION, description = Usage.BASE_DESCRIPTION,
            defaultValue = PodBase.DEFAULT, converter = PodBaseConverter.class, paramLabel = "<url>")
    PodBase base;

    @Option(names = Usage.TOKEN_OPTION, description = Usage.TOKEN_DESCRIPTION,
            defaultValue = TOKEN_DEFAULT, paramLabel = "<credential>", arity = "1")
    String token;

    Optional<BearerToken> credential() {
        return token == null || token.isBlank() ? Optional.empty() : Optional.of(new BearerToken(token));
    }
}
