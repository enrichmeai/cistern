// Provision alice+bob at CSS for the CTH run: account -> password -> link the
// CISTERN-hosted WebID (ownership check disabled in css-config.json) -> client
// credentials for that WebID. Adapted from cistern-auth's fixtures/css/capture.mjs
// (which also verifies a full token round-trip — that stays the capture's job).
// Run via provision.sh, which handles readiness, host aliasing, and writing
// users.json only on success. Usage: node provision-css.mjs <CSS> <CISTERN>
const [CSS, CISTERN] = process.argv.slice(2);
if (!CSS || !CISTERN) { console.error('usage: provision-css.mjs <css-origin> <cistern-origin>'); process.exit(2); }

// CSS answers its errors AS well-formed JSON, so parseability is no proxy for
// success — the status check is the guard.
const j = async (r) => {
  const text = await r.text();
  if (!r.ok) throw new Error(`${r.status} ${r.url} ${text.slice(0, 300)}`);
  try { return JSON.parse(text); } catch { throw new Error(`unparseable ${r.url}: ${text.slice(0, 300)}`); }
};

async function provision(name) {
  const webId = `${CISTERN}/${name}/profile/card#me`;

  // The seeded profile must exist and name an issuer before an account is worth
  // linking to it — this catches a wrong port or an unseeded pod at the first
  // step, not three containers later inside the harness.
  const profile = await fetch(webId.replace(/#.*$/, ''), { headers: { accept: 'text/turtle' } });
  const turtle = await profile.text();
  if (!profile.ok || !turtle.includes('oidcIssuer')) {
    throw new Error(`WebID ${webId} not served with an oidcIssuer by Cistern `
        + `(HTTP ${profile.status}) — is the stack up with cth-application.yaml loaded?`);
  }

  const acct = await j(await fetch(`${CSS}/.account/account/`, { method: 'POST' }));
  const GET_AUTH = { authorization: `CSS-Account-Token ${acct.authorization}` };
  const AUTH = { ...GET_AUTH, 'content-type': 'application/json' };
  const ctrl = async () => (await j(await fetch(`${CSS}/.account/`, { headers: GET_AUTH }))).controls;

  let c = await ctrl();
  await j(await fetch(c.password.create, { method: 'POST', headers: AUTH,
    body: JSON.stringify({ email: `${name}@example.org`, password: `${name}-secret-cth` }) }));

  c = await ctrl();
  await j(await fetch(c.account.webId, { method: 'POST', headers: AUTH,
    body: JSON.stringify({ webId }) }));

  c = await ctrl();
  const cred = await j(await fetch(c.account.clientCredentials, { method: 'POST', headers: AUTH,
    body: JSON.stringify({ name: `cth-${name}`, webId }) }));
  if (!cred.id || !cred.secret) {
    throw new Error(`client credentials for ${name} came back without id/secret: `
        + JSON.stringify(cred).slice(0, 300));
  }

  return { name, webId, email: `${name}@example.org`, clientId: cred.id, clientSecret: cred.secret };
}

const out = {};
for (const name of ['alice', 'bob']) out[name] = await provision(name);
console.log(JSON.stringify(out, null, 2));
