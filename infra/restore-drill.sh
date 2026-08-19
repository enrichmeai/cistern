#!/usr/bin/env bash
# The restore drill (ADR 0002 condition 5): prove that a backup of cistern.storage.root —
# including .cistern/, the decision log — can be brought up as a working, enforcing pod.
#
#   snapshot the source  →  restore into a NEW volume  →  boot Cistern on it  →  smoke test
#
# A backup that has never been restored is a hypothesis. Run this before real documents go
# in, and again after any change to storage or to the image.
#
# Usage
#   infra/restore-drill.sh [--seed] [--keep]
#
#   --seed   the source volume does not exist (or is empty): create it and put a note and a
#            receipt into it first, so there is something to restore. For a first run.
#   --keep   leave the restored volume and container running afterwards, for inspection.
#
# Environment (all optional)
#   CISTERN_SOURCE       docker volume name, or an absolute path to a mounted storage root
#                        (e.g. a disk restored from a snapshot on a GCE instance, or a PVC's
#                        hostPath).                              default: cistern-drill-source
#   CISTERN_IMAGE        the image to boot the restored pod with — the SAME tag production
#                        runs, that is the point.                default: ghcr.io/enrichmeai/cistern:0.1.0
#   CISTERN_BASE_URL     production's cistern.base-url. It must be the ORIGINAL: every ACL
#                        and every receipt names absolute IRIs under it, so a restore under a
#                        different base URL would have ACLs that match nothing. The drill
#                        instance listens on loopback regardless — the base URL is what
#                        requests are resolved against, not what is dialled.
#                                                                 default: http://localhost:3737
#   CISTERN_OWNER_WEBID  the owner of the restored storage root (its /.acl names them). The
#                        drill authenticates as them with a throwaway token that exists only
#                        for this loopback instance; production has no owner token (ADR 0002).
#                                                                 default: https://you.example/profile/card#me
#   CISTERN_DRILL_DIR    where the snapshot archive is written.   default: a temp dir
#
# What PASS means, on the restored copy
#   anonymous GET /  → 401                      (enforcement is on, on the restored data)
#   owner GET /      → 200                      (the root ACL came across and names this owner)
#   /.acl byte-identical to the source's after boot   (the seeder did not re-seed it)
#   decisions present on the volume BEFORE boot       (.cistern/ came across in the backup)
#   GET /?receipts lists the decision just made, and the log grew   (the log is live)
#   with --seed: the seeded note reads 200, and its receipts include the pre-snapshot one
#
# On GCE: create a disk from a snapshot, attach and mount it on an instance, then
#   CISTERN_SOURCE=/mnt/disks/restore CISTERN_BASE_URL=https://pod.example.org \
#   CISTERN_OWNER_WEBID=… CISTERN_IMAGE=ghcr.io/enrichmeai/cistern:<tag> infra/restore-drill.sh
# On Kubernetes: a VolumeSnapshot → a PVC → a Job that runs this script against the mount,
# or copy the volume to a machine with docker and run it there.
set -euo pipefail
trap 'printf "\n\033[31mFAIL\033[0m the drill aborted at line %s (%s)\n" "$LINENO" "$BASH_COMMAND" >&2' ERR

SEED=false
KEEP=false
for arg in "$@"; do
  case "$arg" in
    --seed) SEED=true ;;
    --keep) KEEP=true ;;
    -h|--help) sed -n '2,50p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

SOURCE="${CISTERN_SOURCE:-cistern-drill-source}"
IMAGE="${CISTERN_IMAGE:-ghcr.io/enrichmeai/cistern:0.1.0}"
BASE_URL="${CISTERN_BASE_URL:-http://localhost:3737}"
OWNER="${CISTERN_OWNER_WEBID:-https://you.example/profile/card#me}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
WORK="${CISTERN_DRILL_DIR:-$(mktemp -d -t cistern-drill)}"
ARCHIVE="$WORK/cistern-snapshot-$STAMP.tgz"
RESTORED="cistern-restore-$STAMP"
CONTAINER="cistern-restore-$STAMP"
SEED_CONTAINER="cistern-seed-$STAMP"
DRILL_TOKEN="$(openssl rand -hex 24)"
SEED_REQUEST_ID="drill-seed-$STAMP"
RESTORE_REQUEST_ID="drill-restore-$STAMP"
NOTE_PATH="/drill/note"
# The image's non-root user; a restored volume must be handed to it, as the Dockerfile and
# the k8s fsGroup do.
CISTERN_UID=10001
READY_TIMEOUT_S=120

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
step() { printf '   %-58s %s\n' "$1" "$2"; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
fail() { printf '\n\033[31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

# Run a one-off command in the image as root with the source (read-only) and/or the
# restored volume mounted. tar, chown and sha256sum come from the image's Ubuntu base, so
# the drill needs no second image. `-v "$SOURCE":/from` mounts a volume by name or an
# absolute path as a bind mount — docker tells them apart by the leading slash.
in_image() { docker run --rm --user root --entrypoint /bin/bash "$@"; }

# The storage root's ACL on disk. The file backend encodes a leading dot as %2E so that
# nothing a client names can collide with what the store owns (.meta/, .tmp-*), and so
# that .cistern/ — the decision log — is structurally outside the pod's URI space.
ROOT_ACL_FILE='%2Eacl'

wait_ready() {  # $1 = host:port
  local deadline=$((SECONDS + READY_TIMEOUT_S))
  until [ "$(code "http://$1/")" != "000" ]; do
    if (( SECONDS > deadline )); then return 1; fi
    sleep 1
  done
}

# Boot a Cistern container on a volume; prints host:port. Loopback only, random port.
boot() {  # $1 = container name, $2 = volume
  docker run -d --name "$1" \
    -p 127.0.0.1::3000 \
    --read-only --tmpfs /tmp:rw,exec,nosuid,size=128m \
    --cap-drop=ALL --security-opt=no-new-privileges \
    -v "$2":/data \
    -e CISTERN_STORAGE_ROOT=/data \
    -e CISTERN_BASE_URL="$BASE_URL" \
    -e CISTERN_OWNER_WEBID="$OWNER" \
    -e CISTERN_OWNER_TOKEN="$DRILL_TOKEN" \
    "$IMAGE" >/dev/null
  local hostport
  hostport="$(docker port "$1" 3000/tcp | head -1)"
  wait_ready "$hostport" || { docker logs "$1" | tail -20 >&2; fail "$1 did not come up in ${READY_TIMEOUT_S}s"; }
  printf '%s' "$hostport"
}

cleanup() {
  docker rm -f "$SEED_CONTAINER" >/dev/null 2>&1 || true
  if [ "$KEEP" = false ]; then
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    docker volume rm "$RESTORED" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

command -v docker >/dev/null || fail "docker is required"
command -v curl   >/dev/null || fail "curl is required"

say "Restore drill $STAMP"
step "image" "$IMAGE"
step "source" "$SOURCE"
step "base URL (the original, by design)" "$BASE_URL"
step "owner" "$OWNER"
step "archive" "$ARCHIVE"

# ---- 0. a source to restore from (first run only) ----------------------------------------
if [ "$SEED" = true ]; then
  say "0. Seeding the source: a note and a receipt, so there is something to restore"
  if [[ "$SOURCE" != /* ]]; then docker volume create "$SOURCE" >/dev/null; fi
  HP="$(boot "$SEED_CONTAINER" "$SOURCE")"
  AUTH="Authorization: Bearer $DRILL_TOKEN"
  step "owner PUT $NOTE_PATH" "$(code -X PUT -H "$AUTH" -H 'Content-Type: text/turtle' \
      --data-raw '<#n> <http://purl.org/dc/terms/title> "Restore drill note" .' "http://$HP$NOTE_PATH")"
  step "owner GET $NOTE_PATH  (X-Request-Id: $SEED_REQUEST_ID)" \
      "$(code -H "$AUTH" -H "X-Request-Id: $SEED_REQUEST_ID" "http://$HP$NOTE_PATH")"
  step "anon  GET $NOTE_PATH" "$(code "http://$HP$NOTE_PATH")"
  docker rm -f "$SEED_CONTAINER" >/dev/null
  step "seed container stopped" "ok"
fi

# ---- 1. snapshot ---------------------------------------------------------------------------
say "1. Snapshot: archive the whole storage root, .cistern/ included"
# Read-only mount: the source may be a live volume — tmp-then-ATOMIC_MOVE means each
# resource is either before or after a write in the archive, never torn (the same property
# a disk snapshot relies on).
in_image -v "$SOURCE":/from:ro -v "$WORK":/backup "$IMAGE" -c \
  "tar czf /backup/$(basename "$ARCHIVE") -C /from ."
step "archive size" "$(du -h "$ARCHIVE" | cut -f1)"
step "files archived" "$(tar tzf "$ARCHIVE" | wc -l | tr -d ' ')"
step "decision-log files in the archive (.cistern/decisions/*.jsonl)" "$(tar tzf "$ARCHIVE" | grep -c '^\./\.cistern/decisions/.*\.jsonl$' || true)"
SOURCE_ACL_SHA="$(in_image -v "$SOURCE":/from:ro "$IMAGE" -c "sha256sum /from/$ROOT_ACL_FILE 2>/dev/null | cut -d' ' -f1 || true")"
[ -n "$SOURCE_ACL_SHA" ] || fail "the source has no /.acl ($ROOT_ACL_FILE) — it was never an enforcing pod (no owner), or it is not a storage root"
step "source /.acl sha256" "${SOURCE_ACL_SHA:0:16}…"

# ---- 2. restore into a NEW volume ------------------------------------------------------------
say "2. Restore: a new volume, never the source"
docker volume create "$RESTORED" >/dev/null
in_image -v "$RESTORED":/to -v "$WORK":/backup:ro "$IMAGE" -c \
  "tar xzf /backup/$(basename "$ARCHIVE") -C /to && chown -R $CISTERN_UID:$CISTERN_UID /to"
step "restored into volume" "$RESTORED"
RESTORED_ACL_SHA="$(in_image -v "$RESTORED":/to:ro "$IMAGE" -c "sha256sum /to/$ROOT_ACL_FILE | cut -d' ' -f1")"
DECISION_FILES="$(in_image -v "$RESTORED":/to:ro "$IMAGE" -c "find /to/.cistern/decisions -name '*.jsonl' 2>/dev/null | wc -l | tr -d ' '")"
step "restored /.acl sha256" "${RESTORED_ACL_SHA:0:16}…"
step "restored .cistern/decisions/*.jsonl" "$DECISION_FILES file(s)"
# Receipts that exist BEFORE the restored pod has answered a single request: these can only
# have come from the backup. Counted on disk, so the check does not depend on which
# resources the source happens to contain.
PRE_LINES="$(in_image -v "$RESTORED":/to:ro "$IMAGE" -c "cat /to/.cistern/decisions/*.jsonl 2>/dev/null | grep -c . || true")"
step "decisions carried over (lines, before boot)" "$PRE_LINES"

# ---- 3. boot on the restored volume -----------------------------------------------------------
say "3. Boot: the same image, the original base URL, the restored volume"
HP="$(boot "$CONTAINER" "$RESTORED")"
step "listening (loopback, random port)" "$HP"
AUTH="Authorization: Bearer $DRILL_TOKEN"

# ---- 4. smoke -------------------------------------------------------------------------------
say "4. Smoke: the restored pod is enforcing, the data is there, the receipts came with it"
# The storage root exists in every enforcing pod, so it is what the generic checks use; the
# drill note is checked too when this run (or an earlier --seed run) put it there.
ANON="$(code "http://$HP/")"
step "anon  GET /" "$ANON   (expect 401: enforcing on the restored data)"
OWNER_ROOT="$(code -H "$AUTH" -H "X-Request-Id: $RESTORE_REQUEST_ID" "http://$HP/")"
step "owner GET /  (X-Request-Id: $RESTORE_REQUEST_ID)" "$OWNER_ROOT   (expect 200: root ACL came across, owner matches)"
OWNER_NOTE="$(code -H "$AUTH" "http://$HP$NOTE_PATH")"
step "owner GET $NOTE_PATH" "$OWNER_NOTE   (200 when the drill note is in the source; 404 otherwise)"
AFTER_BOOT_ACL_SHA="$(in_image -v "$RESTORED":/to:ro "$IMAGE" -c "sha256sum /to/$ROOT_ACL_FILE | cut -d' ' -f1")"
if [ "$AFTER_BOOT_ACL_SHA" = "$SOURCE_ACL_SHA" ]; then ACL_VERDICT="identical (not re-seeded)"; else ACL_VERDICT="DIFFERS"; fi
step "/.acl after boot vs source" "$ACL_VERDICT"

RECEIPTS_HEADERS="$(mktemp)"
RECEIPTS_BODY="$(mktemp)"
RECEIPTS_STATUS="$(curl -s -D "$RECEIPTS_HEADERS" -o "$RECEIPTS_BODY" -w '%{http_code}' -H "$AUTH" "http://$HP/?receipts")"
RECEIPTS_TYPE="$( (grep -i '^content-type:' "$RECEIPTS_HEADERS" || true) | tr -d '\r' | awk '{print $2}' | cut -d';' -f1)"
RECEIPTS_COUNT="$(grep -c . "$RECEIPTS_BODY" || true)"
step "owner GET /?receipts" "$RECEIPTS_STATUS $RECEIPTS_TYPE, $RECEIPTS_COUNT receipt(s) about /"
HAS_RESTORE="$(grep -c "$RESTORE_REQUEST_ID" "$RECEIPTS_BODY" || true)"
step "receipt from AFTER the restore ($RESTORE_REQUEST_ID)" "$([ "$HAS_RESTORE" -gt 0 ] && echo present || echo MISSING)"
rm -f "$RECEIPTS_HEADERS" "$RECEIPTS_BODY"
POST_LINES="$(in_image -v "$RESTORED":/to:ro "$IMAGE" -c "cat /to/.cistern/decisions/*.jsonl 2>/dev/null | grep -c . || true")"
step "decisions on the restored volume (lines, after smoke)" "$POST_LINES   (expect > $PRE_LINES: the log is live)"
if [ "$SEED" = true ]; then
  NOTE_RECEIPTS="$(curl -s -H "$AUTH" "http://$HP$NOTE_PATH?receipts")"
  HAS_SEED="$(printf '%s' "$NOTE_RECEIPTS" | grep -c "$SEED_REQUEST_ID" || true)"
  step "receipt from BEFORE the snapshot ($SEED_REQUEST_ID)" "$([ "$HAS_SEED" -gt 0 ] && echo present || echo MISSING)"
  echo "      receipts for $NOTE_PATH:"
  printf '%s\n' "$NOTE_RECEIPTS" | sed 's/^/      /'
fi

# ---- verdict ------------------------------------------------------------------------------------
say "Verdict"
[ "$ANON" = "401" ]              || fail "anonymous read of the restored root was $ANON, not 401: the restored pod is not enforcing"
[ "$OWNER_ROOT" = "200" ]        || fail "owner read of the restored root was $OWNER_ROOT, not 200: the root ACL did not come across, or CISTERN_OWNER_WEBID is not the owner it names, or CISTERN_BASE_URL is not the original"
[ "$ACL_VERDICT" != "DIFFERS" ]  || fail "/.acl changed across restore+boot: the seeder re-wrote it, which a restart must never do"
[ "$PRE_LINES" -gt 0 ]           || fail "no decisions on the restored volume before boot: .cistern/ was not in the backup"
[ "$RECEIPTS_STATUS" = "200" ] && [ "$RECEIPTS_TYPE" = "application/x-ndjson" ] \
                                 || fail "receipts query answered $RECEIPTS_STATUS $RECEIPTS_TYPE: this image may predate T5.9 (receipts); drill with the tag production runs"
[ "$HAS_RESTORE" -gt 0 ]         || fail "no receipt from after the restore: the decision log is not live on the restored volume"
[ "$POST_LINES" -gt "$PRE_LINES" ] || fail "the decision log did not grow across the smoke test: it is not being written on the restored volume"
if [ "$SEED" = true ]; then
  [ "$OWNER_NOTE" = "200" ]      || fail "the seeded note was not readable after restore ($OWNER_NOTE): the data did not come across"
  [ "$HAS_SEED" -gt 0 ]          || fail "no receipt from before the snapshot for the seeded note: .cistern/ was not in the backup"
fi
printf '\033[32mPASS\033[0m restore drill %s: enforcing, data present, root ACL intact, receipts carried over and live.\n' "$STAMP"
if [ "$KEEP" = true ]; then
  printf '   kept: container %s on http://%s, volume %s\n' "$CONTAINER" "$HP" "$RESTORED"
fi
printf '   archive: %s\n' "$ARCHIVE"
