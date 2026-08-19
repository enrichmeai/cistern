/**
 * The story the sample app tells: refuse, grant, allow inside, refuse outside, revoke, refuse
 * again. Two casts of characters, one per KitMode. The climax is a refusal — a demo whose
 * climax is successful access is a file browser (k8s/demo.sh).
 */
import { KitMode } from './config.js';
import { HttpMethod, HttpStatus, MediaType } from './http.js';
import { GrantFile } from './grants.js';

/** Who is speaking. The owner grants; the apps ask. An app never writes its own .acl. */
export enum Actor {
  Owner = 'owner',
  LegalApp = 'legal-app',
  TaxApp = 'tax-app',
}

/** The pod layout seed.sh provisions (docs/INTEGRATION.md step 2). */
export enum PodPath {
  Matter = '/matters/2026-114/',
  MatterIndex = '/matters/2026-114/index',
  MatterContract = '/matters/2026-114/contract.pdf',
  MatterAcl = '/matters/2026-114/.acl',
  Tax = '/tax/FY2025-26/',
  TaxReturn = '/tax/FY2025-26/return',
  TaxAcl = '/tax/FY2025-26/.acl',
}

/** Why each status is the right one — the message catalogue. Never inlined at the call site. */
export enum Reason {
  ResetNoGrant = 'RESET_NO_GRANT',
  AnonymousNoGrant = 'ANONYMOUS_NO_GRANT',
  AnonymousOutsideGrant = 'ANONYMOUS_OUTSIDE_GRANT',
  AuthenticatedNoGrant = 'AUTHENTICATED_NO_GRANT',
  AuthenticatedOutsideGrant = 'AUTHENTICATED_OUTSIDE_GRANT',
  OwnerGrantsMatter = 'OWNER_GRANTS_MATTER',
  OwnerGrantsTax = 'OWNER_GRANTS_TAX',
  ListingInsideGrant = 'LISTING_INSIDE_GRANT',
  ReadInsideGrant = 'READ_INSIDE_GRANT',
  ReadPdfInsideGrant = 'READ_PDF_INSIDE_GRANT',
  ReadIsNotWrite = 'READ_IS_NOT_WRITE',
  OwnerRevokes = 'OWNER_REVOKES',
  NextRequestRefused = 'NEXT_REQUEST_REFUSED',
  OwnerRestores = 'OWNER_RESTORES',
}

export const REASON_TEXT: Readonly<Record<Reason, string>> = {
  [Reason.ResetNoGrant]: 'reset: the story starts with no grant on the matter (204 removed one, 404 there was none)',
  [Reason.AnonymousNoGrant]: 'no credential and no rule grants the public: refused; 401 = "authenticate and it may work"',
  [Reason.AnonymousOutsideGrant]: 'outside the grant, no credential: refused',
  [Reason.AuthenticatedNoGrant]: 'authenticated as itself, but no rule names it yet: 403 = "do not retry"',
  [Reason.AuthenticatedOutsideGrant]: 'authenticated, but this container\'s ACL names another app: 403, do not retry',
  [Reason.OwnerGrantsMatter]: 'the owner writes the rule, a file in the pod: Read on the matter (accessTo + default), owner re-stated',
  [Reason.OwnerGrantsTax]: 'the owner grants the tax app the tax year, the same way',
  [Reason.ListingInsideGrant]: 'inside the grant: the container listing (ldp:contains)',
  [Reason.ReadInsideGrant]: 'inside the grant: WAC-Allow says exactly what the app holds',
  [Reason.ReadPdfInsideGrant]: 'inside the grant: a non-RDF document, served verbatim',
  [Reason.ReadIsNotWrite]: 'Read is not Write',
  [Reason.OwnerRevokes]: 'the owner revokes: delete the file. No restart, no token reissued, no cache to purge',
  [Reason.NextRequestRefused]: 'the very next request',
  [Reason.OwnerRestores]: 'restore the grant seed.sh wrote, so the pod is as you left it',
};

/** One request in the story and the statuses that mean it went as the design says. */
export interface Step {
  readonly actor: Actor;
  readonly method: HttpMethod;
  readonly path: PodPath;
  readonly expect: readonly HttpStatus[];
  readonly reason: Reason;
  /** For the owner's PUTs: which grant file to send as text/turtle. */
  readonly grant?: GrantFile;
  readonly accept?: MediaType;
}

const OK = [HttpStatus.Ok] as const;
const WRITTEN = [HttpStatus.Created, HttpStatus.NoContent] as const;
const REMOVED = [HttpStatus.NoContent] as const;
const REMOVED_OR_ABSENT = [HttpStatus.NoContent, HttpStatus.NotFound] as const;

/**
 * Today: the legal app is whoever holds no credential (the grant is foaf:Agent), and every
 * refusal is 401 because the server has never seen an identity.
 */
function todayStory(): readonly Step[] {
  const refused = [HttpStatus.Unauthorized] as const;
  return [
    { actor: Actor.Owner, method: HttpMethod.Delete, path: PodPath.MatterAcl, expect: REMOVED_OR_ABSENT, reason: Reason.ResetNoGrant },
    { actor: Actor.LegalApp, method: HttpMethod.Get, path: PodPath.TaxReturn, expect: refused, reason: Reason.AnonymousNoGrant },
    { actor: Actor.LegalApp, method: HttpMethod.Get, path: PodPath.MatterIndex, expect: refused, reason: Reason.AnonymousNoGrant },
    { actor: Actor.Owner, method: HttpMethod.Put, path: PodPath.MatterAcl, expect: WRITTEN, reason: Reason.OwnerGrantsMatter, grant: GrantFile.Matter },
    { actor: Actor.LegalApp, method: HttpMethod.Get, path: PodPath.Matter, expect: OK, reason: Reason.ListingInsideGrant, accept: MediaType.Turtle },
    { actor: Actor.LegalApp, method: HttpMethod.Get, path: PodPath.MatterIndex, expect: OK, reason: Reason.ReadInsideGrant, accept: MediaType.Turtle },
    { actor: Actor.LegalApp, method: HttpMethod.Get, path: PodPath.MatterContract, expect: OK, reason: Reason.ReadPdfInsideGrant },
    { actor: Actor.LegalApp, method: HttpMethod.Delete, path: PodPath.MatterIndex, expect: refused, reason: Reason.ReadIsNotWrite },
    { actor: Actor.LegalApp, method: HttpMethod.Get, path: PodPath.TaxReturn, expect: refused, reason: Reason.AnonymousOutsideGrant },
    { actor: Actor.Owner, method: HttpMethod.Delete, path: PodPath.MatterAcl, expect: REMOVED, reason: Reason.OwnerRevokes },
    { actor: Actor.LegalApp, method: HttpMethod.Get, path: PodPath.MatterIndex, expect: refused, reason: Reason.NextRequestRefused },
    { actor: Actor.Owner, method: HttpMethod.Put, path: PodPath.MatterAcl, expect: WRITTEN, reason: Reason.OwnerRestores, grant: GrantFile.Matter },
  ];
}

/**
 * After #88: both apps authenticate to Keycloak as themselves. Inside a grant the answer is the
 * same 200; outside it the answer becomes 403 — the server knows who asked and says no.
 */
function after88Story(): readonly Step[] {
  const refused = [HttpStatus.Forbidden] as const;
  return [
    { actor: Actor.Owner, method: HttpMethod.Delete, path: PodPath.MatterAcl, expect: REMOVED_OR_ABSENT, reason: Reason.ResetNoGrant },
    { actor: Actor.Owner, method: HttpMethod.Put, path: PodPath.TaxAcl, expect: WRITTEN, reason: Reason.OwnerGrantsTax, grant: GrantFile.Tax },
    { actor: Actor.LegalApp, method: HttpMethod.Get, path: PodPath.TaxReturn, expect: refused, reason: Reason.AuthenticatedOutsideGrant },
    { actor: Actor.LegalApp, method: HttpMethod.Get, path: PodPath.MatterIndex, expect: refused, reason: Reason.AuthenticatedNoGrant },
    { actor: Actor.Owner, method: HttpMethod.Put, path: PodPath.MatterAcl, expect: WRITTEN, reason: Reason.OwnerGrantsMatter, grant: GrantFile.Matter },
    { actor: Actor.LegalApp, method: HttpMethod.Get, path: PodPath.Matter, expect: OK, reason: Reason.ListingInsideGrant, accept: MediaType.Turtle },
    { actor: Actor.LegalApp, method: HttpMethod.Get, path: PodPath.MatterIndex, expect: OK, reason: Reason.ReadInsideGrant, accept: MediaType.Turtle },
    { actor: Actor.LegalApp, method: HttpMethod.Get, path: PodPath.MatterContract, expect: OK, reason: Reason.ReadPdfInsideGrant },
    { actor: Actor.LegalApp, method: HttpMethod.Delete, path: PodPath.MatterIndex, expect: refused, reason: Reason.ReadIsNotWrite },
    { actor: Actor.LegalApp, method: HttpMethod.Get, path: PodPath.TaxReturn, expect: refused, reason: Reason.AuthenticatedOutsideGrant },
    { actor: Actor.TaxApp, method: HttpMethod.Get, path: PodPath.TaxReturn, expect: OK, reason: Reason.ReadInsideGrant, accept: MediaType.Turtle },
    { actor: Actor.TaxApp, method: HttpMethod.Get, path: PodPath.MatterIndex, expect: refused, reason: Reason.AuthenticatedOutsideGrant },
    { actor: Actor.Owner, method: HttpMethod.Delete, path: PodPath.MatterAcl, expect: REMOVED, reason: Reason.OwnerRevokes },
    { actor: Actor.LegalApp, method: HttpMethod.Get, path: PodPath.MatterIndex, expect: refused, reason: Reason.NextRequestRefused },
    { actor: Actor.Owner, method: HttpMethod.Put, path: PodPath.MatterAcl, expect: WRITTEN, reason: Reason.OwnerRestores, grant: GrantFile.Matter },
  ];
}

export function storyFor(mode: KitMode): readonly Step[] {
  switch (mode) {
    case KitMode.Today:
      return todayStory();
    case KitMode.After88:
      return after88Story();
  }
}
