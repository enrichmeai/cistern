/**
 * Entry point. Reads the kit's environment, casts the actors (who holds which credential),
 * runs the story for the active mode, prints one line per request as
 *
 *     n  actor      METHOD  PATH  -> STATUS  ok|!!  why   [WAC-Allow: ...]
 *
 * and exits non-zero if any status was not the one the design promises.
 */
import { KitMode, loadConfig, type KitConfig } from './config.js';
import { loadGrant } from './grants.js';
import { bearer, FIRST_NON_SUCCESS_STATUS, HttpMethod, MediaType, NO_CREDENTIAL, send, type Credential, type Outcome } from './http.js';
import { clientCredentialsToken } from './keycloak.js';
import { Actor, REASON_TEXT, storyFor, type Step } from './story.js';

interface CastMember {
  readonly credential: Credential;
  readonly description: string;
}

type Cast = Readonly<Record<Actor, CastMember>>;

enum Verdict {
  Matched = 'ok',
  Mismatched = '!!',
}

const EXIT_OK = 0;
const EXIT_MISMATCH = 1;
const EXIT_ERROR = 2;

const OWNER_TOKEN_DESCRIPTION = 'Authorization: Bearer <CISTERN_OWNER_TOKEN> (today\'s only server-side principal)';
const NO_CREDENTIAL_DESCRIPTION = 'no credential — the server names no per-app principal until #88; the grant is foaf:Agent';

function jwtDescription(clientId: string, webId: URL): string {
  return `Authorization: Bearer <JWT from Keycloak, client ${clientId}, webid ${webId.href}>`;
}

async function cast(config: KitConfig): Promise<Cast> {
  const owner: CastMember = { credential: bearer(config.ownerToken), description: OWNER_TOKEN_DESCRIPTION };
  switch (config.mode) {
    case KitMode.Today: {
      const anonymous: CastMember = { credential: NO_CREDENTIAL, description: NO_CREDENTIAL_DESCRIPTION };
      return { [Actor.Owner]: owner, [Actor.LegalApp]: anonymous, [Actor.TaxApp]: anonymous };
    }
    case KitMode.After88: {
      const [legalToken, taxToken] = await Promise.all([
        clientCredentialsToken(config.keycloakBase, config.keycloakRealm, config.legal),
        clientCredentialsToken(config.keycloakBase, config.keycloakRealm, config.tax),
      ]);
      return {
        [Actor.Owner]: owner,
        [Actor.LegalApp]: { credential: bearer(legalToken), description: jwtDescription(config.legal.clientId, config.legal.webId) },
        [Actor.TaxApp]: { credential: bearer(taxToken), description: jwtDescription(config.tax.clientId, config.tax.webId) },
      };
    }
  }
}

async function perform(config: KitConfig, members: Cast, step: Step): Promise<Outcome> {
  const grantText = step.grant ? await loadGrant(config.grantsDir, config.mode, step.grant, config.ownerWebId) : undefined;
  return send(config.cisternBase, {
    method: step.method,
    path: step.path,
    credential: members[step.actor].credential,
    ...(grantText !== undefined ? { body: { mediaType: MediaType.Turtle, text: grantText } } : {}),
    ...(step.accept !== undefined ? { accept: step.accept } : {}),
  });
}

function describe(outcome: Outcome, step: Step): string {
  const parts: string[] = [];
  if (step.method === HttpMethod.Get && outcome.wacAllow !== undefined) {
    parts.push(`WAC-Allow: ${outcome.wacAllow}`);
  }
  if (step.method === HttpMethod.Get && outcome.status < FIRST_NON_SUCCESS_STATUS && outcome.contentType !== undefined) {
    parts.push(`${outcome.contentType}, ${outcome.bytes} bytes`);
  }
  return parts.length === 0 ? '' : `   [${parts.join('; ')}]`;
}

const COL_INDEX = 3;
const COL_ACTOR = 10;
const COL_METHOD = 7;
const COL_PATH = 30;

async function main(): Promise<number> {
  const config = loadConfig(process.env);
  const members = await cast(config);
  const steps = storyFor(config.mode);

  console.log(`cistern integration kit — sample app · mode=${config.mode} · ${config.cisternBase.href} · keycloak ${config.keycloakBase.href}`);
  for (const actor of Object.values(Actor)) {
    console.log(`  ${actor.padEnd(COL_ACTOR)} ${members[actor].description}`);
  }
  console.log('');

  let mismatches = 0;
  for (const [i, step] of steps.entries()) {
    const outcome = await perform(config, members, step);
    const matched = step.expect.includes(outcome.status);
    if (!matched) mismatches += 1;
    const verdict = matched ? Verdict.Matched : `${Verdict.Mismatched} expected ${step.expect.join('|')}`;
    console.log(
      `${String(i + 1).padStart(COL_INDEX)}  ${step.actor.padEnd(COL_ACTOR)} ${step.method.padEnd(COL_METHOD)} ${step.path.padEnd(COL_PATH)} -> ${outcome.status}  ${verdict}  ${REASON_TEXT[step.reason]}${describe(outcome, step)}`,
    );
  }

  console.log('');
  if (mismatches === 0) {
    console.log(`all ${steps.length} steps matched the design (docs/INTEGRATION.md §8).`);
    return EXIT_OK;
  }
  console.log(`${mismatches} of ${steps.length} steps did not match.`);
  if (config.mode === KitMode.After88) {
    console.log('mode=after-88 needs a Cistern that resolves Keycloak JWTs (#88) with the cistern.auth.* lines in docker-compose.yml uncommented.');
  }
  return EXIT_MISMATCH;
}

main().then(
  (code) => process.exit(code),
  (error: unknown) => {
    console.error(error instanceof Error ? error.message : String(error));
    process.exit(EXIT_ERROR);
  },
);
