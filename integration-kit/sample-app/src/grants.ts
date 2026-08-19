/**
 * The grant files under ../grants/<mode>/ are the single source of truth for what the owner
 * writes — seed.sh applies them and so does this app's "owner" actor, from the same bytes.
 * The only edit is the owner placeholder from docs/INTEGRATION.md §9.
 */
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { KitMode } from './config.js';

export enum GrantFile {
  Matter = 'matters-2026-114.acl.ttl',
  Tax = 'tax-FY2025-26.acl.ttl',
}

const OWNER_PLACEHOLDER = '<OWNER-WEBID>';

export async function loadGrant(grantsDir: string, mode: KitMode, file: GrantFile, ownerWebId: URL): Promise<string> {
  const path = join(grantsDir, mode, file);
  const template = await readFile(path, { encoding: 'utf8' });
  return template.replaceAll(OWNER_PLACEHOLDER, `<${ownerWebId.href}>`);
}
