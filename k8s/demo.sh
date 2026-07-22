#!/usr/bin/env bash
# The refusal demo, on a local Kubernetes cluster.
#
#   docker build -t cistern:local .
#   kubectl apply -k k8s/
#   kubectl create secret generic cistern-owner -n cistern \
#     --from-literal=web-id='https://you.example/profile/card#me' \
#     --from-literal=token="$(openssl rand -hex 32)"
#   kubectl port-forward -n cistern svc/cistern 3737:3000 &
#   CISTERN_TOKEN=<the token> ./k8s/demo.sh
#
# What it shows is a NEGATIVE, which is the whole point: an agent tried something and was
# stopped by a rule the owner wrote, enforced at the store. A demo whose climax is
# successful access is a file browser. See docs/demo/walkthrough.md.
set -euo pipefail

BASE="${CISTERN_BASE:-http://127.0.0.1:3737}"
TOKEN="${CISTERN_TOKEN:?set CISTERN_TOKEN to the value in the cistern-owner secret}"
AUTH="Authorization: Bearer ${TOKEN}"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }

say "1. The owner stores a private note"
curl -s -X PUT -H "$AUTH" -H 'Content-Type: text/turtle' \
  --data '<#note> <http://purl.org/dc/terms/title> "Client matter 2026-114" .' \
  -o /dev/null -w '   PUT  /notes/matter -> %{http_code}\n' "$BASE/notes/matter"

say "2. The owner reads it, and is told what they may do"
curl -s -H "$AUTH" -o /dev/null -w '   GET  /notes/matter -> %{http_code}\n' "$BASE/notes/matter"
curl -si -H "$AUTH" "$BASE/notes/matter" | grep -i '^wac-allow' | sed 's/^/   /'

say "3. An agent holding no grant tries the same things"
curl -s -o /dev/null -w '   GET    -> %{http_code}   (401: authenticate and it may work)\n' \
  "$BASE/notes/matter"
curl -s -X DELETE -o /dev/null -w '   DELETE -> %{http_code}   (refused at the store)\n' \
  "$BASE/notes/matter"

say "4. The note is untouched"
curl -s -H "$AUTH" "$BASE/notes/matter" | sed 's/^/   /'

say "Before T5.3 that DELETE returned 204 and the note was gone."
