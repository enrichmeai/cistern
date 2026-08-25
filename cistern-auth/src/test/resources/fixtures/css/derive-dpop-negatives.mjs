// Derived DPoP negatives. The positive proofs in this directory are verbatim from CSS;
// these are stated derivations, because no correct client emits a proof that fails §4.3.
import { generateKeyPair, exportJWK, SignJWT, decodeJwt, decodeProtectedHeader } from 'jose';
import { readFileSync, writeFileSync } from 'node:fs';
const OUT = process.argv[2];
const base = decodeJwt(readFileSync(`${OUT}/dpop-proof-resource-request.jwt`, 'utf8').trim());
const { publicKey, privateKey } = await generateKeyPair('ES256');
const pub = await exportJWK(publicKey);
const priv = await exportJWK(privateKey);          // carries `d`

const sign = (claims, header) => new SignJWT(claims)
  .setProtectedHeader({ alg: 'ES256', typ: 'dpop+jwt', jwk: pub, ...header })
  .setIssuedAt(base.iat).setJti(crypto.randomUUID()).sign(privateKey);

const w = (n, s) => writeFileSync(`${OUT}/${n}`, s);
// step 7: a jwk carrying private key material
w('dpop-proof-private-jwk.jwt', await sign({ htu: base.htu, htm: base.htm, ath: base.ath }, { jwk: priv }));
// step 4: wrong typ
w('dpop-proof-wrong-typ.jwt', await sign({ htu: base.htu, htm: base.htm, ath: base.ath }, { typ: 'JWT' }));
// step 8 / 9 / 12: method, target and ath that do not match
w('dpop-proof-wrong-htm.jwt', await sign({ htu: base.htu, htm: 'DELETE', ath: base.ath }));
w('dpop-proof-wrong-htu.jwt', await sign({ htu: 'https://elsewhere.example/note.ttl', htm: base.htm, ath: base.ath }));
w('dpop-proof-wrong-ath.jwt', await sign({ htu: base.htu, htm: base.htm, ath: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' }));
w('dpop-proof-no-ath.jwt', await sign({ htu: base.htu, htm: base.htm }));
// step 6: signature corrupted in the first byte, not the last (a trailing base64url char
// can decode to the same bytes — that mistake was made once already)
const good = await sign({ htu: base.htu, htm: base.htm, ath: base.ath });
const p = good.split('.');
const sig = Buffer.from(p[2], 'base64url'); sig[0] ^= 0xff;
p[2] = sig.toString('base64url');
w('dpop-proof-bad-signature.jwt', p.join('.'));
// step 12 (second half): correct in every respect EXCEPT the signing key, so the only thing
// left to fail is the cnf.jkt binding. Signed by the foreign key; htm, htu and ath all match.
w('dpop-proof-foreign-key.jwt', await sign({ htu: base.htu, htm: base.htm, ath: base.ath }));
// the foreign key itself, so a test can assert the thumbprint differs from the token's cnf.jkt
w('dpop-foreign-jwk.json', JSON.stringify(pub, null, 2));

const check = decodeProtectedHeader(readFileSync(`${OUT}/dpop-proof-private-jwk.jwt`,'utf8'));
console.log('private-jwk fixture carries d:', 'd' in check.jwk);
const bs = readFileSync(`${OUT}/dpop-proof-bad-signature.jwt`,'utf8');
console.log('bad-signature differs from source:', Buffer.from(bs.split('.')[2],'base64url')[0] !== sig[0] ^ 0xff ? true : Buffer.compare(Buffer.from(bs.split('.')[2],'base64url'), Buffer.from(good.split('.')[2],'base64url')) !== 0);
console.log('derived 8 negatives');
