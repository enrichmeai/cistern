/**
 * Client-credentials against Keycloak: the application authenticates as itself and gets a
 * JWT whose `webid` claim is the WebID the owner's grant names. Used only after #88 — until
 * then Cistern has no resolver that reads it, and the app runs without a credential.
 */
import type { ServicePrincipal } from './config.js';
import { HeaderName, HttpMethod, MediaType } from './http.js';

enum TokenParam {
  GrantType = 'grant_type',
  ClientId = 'client_id',
  ClientSecret = 'client_secret',
}

enum GrantType {
  ClientCredentials = 'client_credentials',
}

const TOKEN_ENDPOINT_TEMPLATE = '/realms/{realm}/protocol/openid-connect/token';
const REALM_PLACEHOLDER = '{realm}';
const ACCESS_TOKEN_FIELD = 'access_token';

export function tokenEndpoint(keycloakBase: URL, realm: string): URL {
  return new URL(TOKEN_ENDPOINT_TEMPLATE.replace(REALM_PLACEHOLDER, encodeURIComponent(realm)), keycloakBase);
}

export async function clientCredentialsToken(
  keycloakBase: URL,
  realm: string,
  principal: ServicePrincipal,
): Promise<string> {
  const form = new URLSearchParams({
    [TokenParam.GrantType]: GrantType.ClientCredentials,
    [TokenParam.ClientId]: principal.clientId,
    [TokenParam.ClientSecret]: principal.clientSecret,
  });
  const response = await fetch(tokenEndpoint(keycloakBase, realm), {
    method: HttpMethod.Post,
    headers: { [HeaderName.ContentType]: MediaType.Form, [HeaderName.Accept]: MediaType.Json },
    body: form,
  });
  if (!response.ok) {
    throw new Error(`Keycloak refused client ${principal.clientId}: ${response.status} ${await response.text()}`);
  }
  const json = (await response.json()) as Record<string, unknown>;
  const token = json[ACCESS_TOKEN_FIELD];
  if (typeof token !== 'string' || token === '') {
    throw new Error(`Keycloak's token response for ${principal.clientId} has no ${ACCESS_TOKEN_FIELD}`);
  }
  return token;
}
