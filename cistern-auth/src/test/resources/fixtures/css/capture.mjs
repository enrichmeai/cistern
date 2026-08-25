import { generateKeyPair, exportJWK, SignJWT, calculateJwkThumbprint, decodeJwt, decodeProtectedHeader } from 'jose';
import { writeFileSync, mkdirSync } from 'node:fs';

const BASE = 'http://localhost:3939';
const OUT  = process.argv[2];
mkdirSync(OUT, { recursive: true });
const j = async (r) => { const t = await r.text(); try { return JSON.parse(t); } catch { throw new Error(`${r.status} ${t.slice(0,200)}`); } };

// 1. account
const acct = await j(await fetch(`${BASE}/.account/account/`, { method: 'POST' }));
const AUTH = { authorization: `CSS-Account-Token ${acct.authorization}` };
const ctrl = async () => (await j(await fetch(`${BASE}/.account/`, { headers: AUTH }))).controls;

// 2. password login
let c = await ctrl();
await j(await fetch(c.password.create, { method: 'POST', headers: { ...AUTH, 'content-type': 'application/json' },
  body: JSON.stringify({ email: 'alice@example.org', password: 'alice-secret' }) }));

// 3. pod (creates the WebID)
c = await ctrl();
const pod = await j(await fetch(c.account.pod, { method: 'POST', headers: { ...AUTH, 'content-type': 'application/json' },
  body: JSON.stringify({ name: 'alice' }) }));
const webId = pod.webId;

// 4. client credentials
c = await ctrl();
const cred = await j(await fetch(c.account.clientCredentials, { method: 'POST', headers: { ...AUTH, 'content-type': 'application/json' },
  body: JSON.stringify({ name: 'cistern-capture', webId }) }));

// 5. DPoP-bound access token via client_credentials
const { publicKey, privateKey } = await generateKeyPair('ES256');
const pubJwk = await exportJWK(publicKey);
const jkt = await calculateJwkThumbprint(pubJwk, 'sha256');
const tokenUrl = `${BASE}/.oidc/token`;
const proof = await new SignJWT({ htu: tokenUrl, htm: 'POST' })
  .setProtectedHeader({ alg: 'ES256', typ: 'dpop+jwt', jwk: pubJwk })
  .setIssuedAt().setJti(crypto.randomUUID()).sign(privateKey);

const basic = Buffer.from(`${encodeURIComponent(cred.id)}:${encodeURIComponent(cred.secret)}`).toString('base64');
const tok = await j(await fetch(tokenUrl, { method: 'POST',
  headers: { authorization: `Basic ${basic}`, dpop: proof, 'content-type': 'application/x-www-form-urlencoded' },
  body: 'grant_type=client_credentials&scope=webid' }));

// 6. a resource-request DPoP proof bound to the access token (what T4.2 will verify)
const { createHash } = await import('node:crypto');
const ath = createHash('sha256').update(tok.access_token).digest('base64url');
const resourceUrl = `${webId.replace(/profile\/card#me$/, '')}private/note.ttl`;
const resProof = await new SignJWT({ htu: resourceUrl, htm: 'GET', ath })
  .setProtectedHeader({ alg: 'ES256', typ: 'dpop+jwt', jwk: pubJwk })
  .setIssuedAt().setJti(crypto.randomUUID()).sign(privateKey);

const disco = await j(await fetch(`${BASE}/.well-known/openid-configuration`));
const jwks  = await j(await fetch(disco.jwks_uri));
const w = (n, s) => writeFileSync(`${OUT}/${n}`, s);
w('openid-configuration.json', JSON.stringify(disco, null, 2));
w('jwks.json', JSON.stringify(jwks, null, 2));
w('access-token.jwt', tok.access_token);
w('dpop-proof-token-request.jwt', proof);
w('dpop-proof-resource-request.jwt', resProof);

const claims = decodeJwt(tok.access_token), header = decodeProtectedHeader(tok.access_token);
w('access-token.decoded.json', JSON.stringify({ header, claims }, null, 2));
console.log(JSON.stringify({
  webId, token_type: tok.token_type, expires_in: tok.expires_in,
  header, claims, computed_jkt: jkt,
  cnf_jkt_matches: claims.cnf?.jkt === jkt,
  HAS_client_id: 'client_id' in claims, HAS_azp: 'azp' in claims, HAS_webid: 'webid' in claims,
}, null, 2));

// ---- derived negatives (ground rule 6: the positive above is verbatim from CSS; these are
// stated derivations, because no IdP will mint a token that fails its own verification) ----
const { generateKeyPair: gkp, exportJWK: ejwk, SignJWT: SJ } = await import('jose');
const foreign = await gkp('ES256');
const foreignJwk = await ejwk(foreign.publicKey);
foreignJwk.kid = 'foreign-key-not-in-css-jwks';
const reSign = (over) => new SJ({ ...claims, ...over })
  .setProtectedHeader({ alg: 'ES256', typ: 'at+jwt', kid: foreignJwk.kid })
  .sign(foreign.privateKey);
w('access-token-wrong-key.jwt', await reSign({}));
w('access-token-wrong-issuer.jwt', await reSign({ iss: 'https://evil.example/' }));
w('access-token-unusable-issuer.jwt', await reSign({ iss: 'not-a-uri' }));
w('jwks-foreign.json', JSON.stringify({ keys: [foreignJwk] }, null, 2));
// Flip one bit of the signature's first byte, not its last base64url character: an ES256
// signature is 64 raw bytes, so the final character carries four bits that decoding discards
// — changing it yields a token that is byte-identical once decoded, and verifies.
const parts = tok.access_token.split('.');
const raw = Buffer.from(parts[2], 'base64url');
raw[0] ^= 0x01;
const tampered = raw.toString('base64url');
if (tampered === parts[2] || Buffer.compare(Buffer.from(tampered, 'base64url'), Buffer.from(parts[2], 'base64url')) === 0) {
  throw new Error('bad-signature derivation did not change the signature bytes');
}
w('access-token-bad-signature.jwt', [parts[0], parts[1], tampered].join('.'));
console.log('derived negatives written');
