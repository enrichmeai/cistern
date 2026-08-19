#!/usr/bin/env bash
# The refusal demo: an agent may read /notes/ and nothing else, until the owner takes it
# back — enforced at the store, revoked on the very next request.
#
# Against a local cluster:
#   docker build -t cistern:local .
#   kubectl config use-context docker-desktop        # never a shared cluster
#   kubectl apply -k k8s/
#   kubectl create secret generic cistern-owner -n cistern \
#     --from-literal=web-id='https://you.example/profile/card#me' \
#     --from-literal=token="$(openssl rand -hex 32)"
#   kubectl port-forward -n cistern svc/cistern 3737:3000 &
#   CISTERN_TOKEN=<the token> ./k8s/demo.sh
#
# Against the bare jar (no cluster needed):
#   CISTERN_OWNER_WEBID=… CISTERN_OWNER_TOKEN=… java -jar cistern-app/target/cistern-app-*.jar --server.port=3737 &
#   CISTERN_TOKEN=… ./k8s/demo.sh
#
# What it shows is a NEGATIVE, which is the whole point: an agent tried something and was
# stopped by a rule the owner wrote, held in the owner's own storage, enforced at the
# store, revoked in one request. A demo whose climax is successful access is a file
# browser. See docs/demo/walkthrough.md.
#
# "The agent" is any caller without the owner's credential; the grant is class-based
# (foaf:Agent) because the per-agent principal (T4.3 decision, Phase 6) is not built yet.
set -euo pipefail

BASE="${CISTERN_BASE:-http://127.0.0.1:3737}"
TOKEN="${CISTERN_TOKEN:?set CISTERN_TOKEN to the value in the cistern-owner secret}"
OWNER="${CISTERN_OWNER_WEBID:-https://you.example/profile/card#me}"
AUTH="Authorization: Bearer ${TOKEN}"

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

say "1. The owner stores a note and a private client matter"
printf '   owner PUT /notes/week      -> %s\n' "$(code -X PUT -H "$AUTH" -H 'Content-Type: text/turtle' \
  --data-raw '<#n> <http://purl.org/dc/terms/title> "Weekly notes" .' "$BASE/notes/week")"
printf '   owner PUT /private/matter  -> %s\n' "$(code -X PUT -H "$AUTH" -H 'Content-Type: text/turtle' \
  --data-raw '<#s> <http://purl.org/dc/terms/title> "Client matter 2026-114" .' "$BASE/private/matter")"

say "2. An agent with no grant asks for the notes"
printf '   agent GET /notes/week -> %s   (denied: no rule permits it)\n' "$(code "$BASE/notes/week")"

say "3. The owner writes the rule — a file in the pod: read /notes/, nothing else"
ACL="@prefix acl: <http://www.w3.org/ns/auth/acl#> . @prefix foaf: <http://xmlns.com/foaf/0.1/> .
<#owner> a acl:Authorization ; acl:agent <${OWNER}> ;
  acl:accessTo </notes/> ; acl:default </notes/> ;
  acl:mode acl:Read, acl:Write, acl:Append, acl:Control .
<#agent> a acl:Authorization ; acl:agentClass foaf:Agent ;
  acl:accessTo </notes/> ; acl:default </notes/> ;
  acl:mode acl:Read ."
printf '   owner PUT /notes/.acl -> %s\n' "$(code -X PUT -H "$AUTH" -H 'Content-Type: text/turtle' --data-raw "$ACL" "$BASE/notes/.acl")"

say "4. The agent tries again — inside the grant, and outside it"
printf '   agent GET    /notes/week     -> %s   ' "$(code "$BASE/notes/week")"
curl -si "$BASE/notes/week" | grep -i '^wac-allow' | tr -d '\r'
printf '   agent DELETE /notes/week     -> %s   (read is not write)\n' "$(code -X DELETE "$BASE/notes/week")"
printf '   agent GET    /private/matter -> %s   (outside the grant)\n' "$(code "$BASE/private/matter")"

say "5. The owner revokes — delete the rule. No restart. No token reissued."
printf '   owner DELETE /notes/.acl -> %s\n' "$(code -X DELETE -H "$AUTH" "$BASE/notes/.acl")"
printf '   agent GET    /notes/week -> %s   (the very next request)\n' "$(code "$BASE/notes/week")"
printf '   owner GET    /notes/week -> %s   (the owner is unaffected)\n' "$(code -H "$AUTH" "$BASE/notes/week")"

say "6. The receipt — what happened to /notes/week, under which rule (T5.9)"
echo   "   owner GET /notes/week?receipts   (Control on the resource; the agent gets 401)"
curl -s -H "$AUTH" "$BASE/notes/week?receipts" | sed 's/^/   /'
printf '   agent GET /notes/week?receipts -> %s   (receipts need Control, not Read)\n' "$(code "$BASE/notes/week?receipts")"

say "Before T5.3 the agent's DELETE returned 204 and the note was gone. Now every decision is a receipt."
