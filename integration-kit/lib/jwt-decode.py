#!/usr/bin/env python3
"""Print a JWT's header and claims as JSON (no verification — this is for reading fixtures).

    python3 lib/jwt-decode.py < token.jwt
    python3 lib/jwt-decode.py fixtures/token-valuedocs-legal.jwt
"""
import base64
import json
import sys

HEADER, PAYLOAD = 0, 1
BASE64_BLOCK = 4


def b64url_json(segment: str) -> dict:
    padded = segment + "=" * (-len(segment) % BASE64_BLOCK)
    return json.loads(base64.urlsafe_b64decode(padded))


def main() -> None:
    raw = (open(sys.argv[1]).read() if len(sys.argv) > 1 else sys.stdin.read()).strip()
    parts = raw.split(".")
    if len(parts) != 3:
        sys.exit("not a compact JWS (expected header.payload.signature)")
    print(json.dumps({"header": b64url_json(parts[HEADER]), "claims": b64url_json(parts[PAYLOAD])}, indent=2))


if __name__ == "__main__":
    main()
