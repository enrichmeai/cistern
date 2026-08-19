/**
 * Everything the sample app needs to know arrives in the environment, exported by
 * ../run.sh from ../lib/kit.sh, ../cistern.env and ../identities.env. One place reads it;
 * the rest of the program sees a typed, validated {@link KitConfig}.
 */

/** The two shapes the kit runs in — the same closed set as KIT_MODE in lib/kit.sh. */
export enum KitMode {
  /** Owner-token resolver only: the app holds no credential; refusal outside a grant is 401. */
  Today = 'today',
  /** After #88: the app authenticates to Keycloak as itself; refusal outside a grant is 403. */
  After88 = 'after-88',
}

/** The environment variables this program reads. Nowhere else names one. */
export enum EnvVar {
  KitMode = 'KIT_MODE',
  CisternBase = 'CISTERN_BASE',
  KeycloakBase = 'KEYCLOAK_BASE',
  KeycloakRealm = 'KEYCLOAK_REALM',
  GrantsDir = 'GRANTS_DIR',
  OwnerWebId = 'CISTERN_OWNER_WEBID',
  OwnerToken = 'CISTERN_OWNER_TOKEN',
  LegalClientId = 'KEYCLOAK_CLIENT_LEGAL_ID',
  LegalClientSecret = 'KEYCLOAK_CLIENT_LEGAL_SECRET',
  LegalWebId = 'KEYCLOAK_CLIENT_LEGAL_WEBID',
  TaxClientId = 'KEYCLOAK_CLIENT_TAX_ID',
  TaxClientSecret = 'KEYCLOAK_CLIENT_TAX_SECRET',
  TaxWebId = 'KEYCLOAK_CLIENT_TAX_WEBID',
}

/** A confidential OIDC client: what an application presents to the issuer to be itself. */
export interface ServicePrincipal {
  readonly clientId: string;
  readonly clientSecret: string;
  /** The WebID Keycloak puts in the `webid` claim; the WebID the grant names. */
  readonly webId: URL;
}

export interface KitConfig {
  readonly mode: KitMode;
  readonly cisternBase: URL;
  readonly keycloakBase: URL;
  readonly keycloakRealm: string;
  readonly grantsDir: string;
  readonly ownerWebId: URL;
  readonly ownerToken: string;
  readonly legal: ServicePrincipal;
  readonly tax: ServicePrincipal;
}

const MODES: ReadonlySet<string> = new Set(Object.values(KitMode));

function required(env: NodeJS.ProcessEnv, name: EnvVar): string {
  const value = env[name];
  if (value === undefined || value.trim() === '') {
    throw new Error(`${name} is not set — run this through sample-app/run.sh, which exports the kit's environment`);
  }
  return value.trim();
}

function requiredUrl(env: NodeJS.ProcessEnv, name: EnvVar): URL {
  const raw = required(env, name);
  try {
    return new URL(raw);
  } catch {
    throw new Error(`${name} is not an absolute URL: ${raw}`);
  }
}

function principal(env: NodeJS.ProcessEnv, id: EnvVar, secret: EnvVar, webId: EnvVar): ServicePrincipal {
  return { clientId: required(env, id), clientSecret: required(env, secret), webId: requiredUrl(env, webId) };
}

export function loadConfig(env: NodeJS.ProcessEnv): KitConfig {
  const mode = env[EnvVar.KitMode] ?? KitMode.Today;
  if (!MODES.has(mode)) {
    throw new Error(`${EnvVar.KitMode} must be one of ${[...MODES].join(' | ')} (got '${mode}')`);
  }
  return {
    mode: mode as KitMode,
    cisternBase: requiredUrl(env, EnvVar.CisternBase),
    keycloakBase: requiredUrl(env, EnvVar.KeycloakBase),
    keycloakRealm: required(env, EnvVar.KeycloakRealm),
    grantsDir: required(env, EnvVar.GrantsDir),
    ownerWebId: requiredUrl(env, EnvVar.OwnerWebId),
    ownerToken: required(env, EnvVar.OwnerToken),
    legal: principal(env, EnvVar.LegalClientId, EnvVar.LegalClientSecret, EnvVar.LegalWebId),
    tax: principal(env, EnvVar.TaxClientId, EnvVar.TaxClientSecret, EnvVar.TaxWebId),
  };
}
