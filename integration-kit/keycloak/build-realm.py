#!/usr/bin/env python3
"""Builds the kit's realm on a blank Keycloak through the admin REST API.

Called by build-realm.sh, which then stops the server and runs `kc.sh export` so that
realm-cistern.json is a genuine Keycloak export (ground rule 6: real-first). Every identity
comes from identities.env (exported into the environment by lib/kit.sh); nothing is
invented here. Standard library only.
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from enum import Enum


# ---- environment (identities.env) --------------------------------------------------------
class Env(str, Enum):
    BASE = "KEYCLOAK_BUILD_BASE"          # where the throwaway server listens
    REALM = "KEYCLOAK_REALM"
    ADMIN_USER = "KEYCLOAK_ADMIN_USER"
    ADMIN_PASSWORD = "KEYCLOAK_ADMIN_PASSWORD"
    AUDIENCE = "KEYCLOAK_AUDIENCE"
    ALICE = "KEYCLOAK_USER_ALICE"
    ALICE_PASSWORD = "KEYCLOAK_USER_ALICE_PASSWORD"
    ALICE_WEBID = "KEYCLOAK_USER_ALICE_WEBID"
    BOB = "KEYCLOAK_USER_BOB"
    BOB_PASSWORD = "KEYCLOAK_USER_BOB_PASSWORD"
    BOB_WEBID = "KEYCLOAK_USER_BOB_WEBID"
    LEGAL_ID = "KEYCLOAK_CLIENT_LEGAL_ID"
    LEGAL_SECRET = "KEYCLOAK_CLIENT_LEGAL_SECRET"
    LEGAL_WEBID = "KEYCLOAK_CLIENT_LEGAL_WEBID"
    TAX_ID = "KEYCLOAK_CLIENT_TAX_ID"
    TAX_SECRET = "KEYCLOAK_CLIENT_TAX_SECRET"
    TAX_WEBID = "KEYCLOAK_CLIENT_TAX_WEBID"
    FIXTURE_ID = "KEYCLOAK_CLIENT_FIXTURE_ID"
    FIXTURE_SECRET = "KEYCLOAK_CLIENT_FIXTURE_SECRET"
    FIXTURE_WEBID = "KEYCLOAK_CLIENT_FIXTURE_WEBID"
    FIXTURE_LIFESPAN = "KEYCLOAK_CLIENT_FIXTURE_TOKEN_LIFESPAN_SECONDS"


def env(name: Env) -> str:
    value = os.environ.get(name.value)
    if not value:
        sys.exit(f"{name.value} is not set — source lib/kit.sh first")
    return value


# ---- Keycloak vocabulary ---------------------------------------------------------------
class Path(str, Enum):
    ADMIN_TOKEN = "/realms/master/protocol/openid-connect/token"
    REALMS = "/admin/realms"


class Grant(str, Enum):
    PASSWORD = "password"


class Protocol(str, Enum):
    OIDC = "openid-connect"


class Mapper(str, Enum):
    AUDIENCE = "oidc-audience-mapper"
    USER_ATTRIBUTE = "oidc-usermodel-attribute-mapper"


class Claim(str, Enum):
    WEBID = "webid"


class ClientAttribute(str, Enum):
    ACCESS_TOKEN_LIFESPAN = "access.token.lifespan"


ADMIN_CLI_CLIENT = "admin-cli"
CLIENT_SCOPE_NAME = "cistern"
CLIENT_SCOPE_DESCRIPTION = "Cistern: aud=cistern plus the webid claim every principal carries"
JSON = "application/json"
FORM = "application/x-www-form-urlencoded"
HTTP_CREATED = 201
HTTP_NO_CONTENT = 204
HTTP_CONFLICT = 409


@dataclass(frozen=True)
class Human:
    username: str
    password: str
    webid: str
    first_name: str
    last_name: str
    email: str


@dataclass(frozen=True)
class ServiceClient:
    client_id: str
    secret: str
    webid: str
    name: str
    description: str
    token_lifespan_seconds: int | None = None


# ---- a very small admin client ---------------------------------------------------------
class Admin:
    def __init__(self, base: str, realm: str) -> None:
        self.base = base.rstrip("/")
        self.realm = realm
        self.token = self._admin_token()

    def _admin_token(self) -> str:
        form = urllib.parse.urlencode({
            "grant_type": Grant.PASSWORD.value,
            "client_id": ADMIN_CLI_CLIENT,
            "username": env(Env.ADMIN_USER),
            "password": env(Env.ADMIN_PASSWORD),
        }).encode()
        req = urllib.request.Request(self.base + Path.ADMIN_TOKEN.value, data=form,
                                     headers={"Content-Type": FORM})
        with urllib.request.urlopen(req) as res:
            return json.load(res)["access_token"]

    def call(self, method: str, path: str, body: dict | list | None = None,
             expect: tuple[int, ...] = (200, HTTP_CREATED, HTTP_NO_CONTENT)) -> tuple[int, dict | list | None, dict]:
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method, headers={
            "Authorization": f"Bearer {self.token}",
            "Content-Type": JSON,
            "Accept": JSON,
        })
        try:
            with urllib.request.urlopen(req) as res:
                raw = res.read()
                parsed = json.loads(raw) if raw else None
                return res.status, parsed, dict(res.headers)
        except urllib.error.HTTPError as e:
            if e.code in expect:
                return e.code, None, dict(e.headers)
            sys.exit(f"{method} {path} -> {e.code}: {e.read().decode(errors='replace')}")

    def realm_path(self, suffix: str = "") -> str:
        return f"{Path.REALMS.value}/{self.realm}{suffix}"


# ---- the realm ------------------------------------------------------------------------
def create_realm(admin: Admin) -> None:
    status, _, _ = admin.call("POST", Path.REALMS.value, {
        "realm": admin.realm,
        "enabled": True,
        "displayName": "Cistern integration kit",
        "registrationAllowed": False,
        # 5 minutes: Keycloak's default, kept on purpose so tokens behave like real ones.
        "accessTokenLifespan": 300,
    }, expect=(HTTP_CREATED, HTTP_CONFLICT))
    if status == HTTP_CONFLICT:
        sys.exit(f"realm '{admin.realm}' already exists on {admin.base}: build against a blank server")
    print(f"realm {admin.realm}: created")


def declare_webid_attribute(admin: Admin) -> None:
    """Keycloak 24+ user profiles are managed: an undeclared attribute is dropped on write."""
    _, profile, _ = admin.call("GET", admin.realm_path("/users/profile"))
    assert isinstance(profile, dict)
    if not any(a.get("name") == Claim.WEBID.value for a in profile["attributes"]):
        profile["attributes"].append({
            "name": Claim.WEBID.value,
            "displayName": "WebID",
            "validations": {"uri": {}},
            "permissions": {"view": ["admin", "user"], "edit": ["admin"]},
            "multivalued": False,
        })
        admin.call("PUT", admin.realm_path("/users/profile"), profile)
    print(f"user profile: attribute '{Claim.WEBID.value}' declared")


def create_client_scope(admin: Admin) -> str:
    audience = env(Env.AUDIENCE)
    _, _, headers = admin.call("POST", admin.realm_path("/client-scopes"), {
        "name": CLIENT_SCOPE_NAME,
        "description": CLIENT_SCOPE_DESCRIPTION,
        "protocol": Protocol.OIDC.value,
        "attributes": {"include.in.token.scope": "true", "display.on.consent.screen": "false"},
        "protocolMappers": [
            {
                "name": f"audience {audience}",
                "protocol": Protocol.OIDC.value,
                "protocolMapper": Mapper.AUDIENCE.value,
                "config": {
                    "included.custom.audience": audience,
                    "access.token.claim": "true",
                    "id.token.claim": "false",
                    "introspection.token.claim": "true",
                },
            },
            {
                "name": Claim.WEBID.value,
                "protocol": Protocol.OIDC.value,
                "protocolMapper": Mapper.USER_ATTRIBUTE.value,
                "config": {
                    "user.attribute": Claim.WEBID.value,
                    "claim.name": Claim.WEBID.value,
                    "jsonType.label": "String",
                    "access.token.claim": "true",
                    "id.token.claim": "true",
                    "userinfo.token.claim": "true",
                    "introspection.token.claim": "true",
                },
            },
        ],
    })
    scope_id = headers["Location"].rstrip("/").rsplit("/", 1)[-1]
    print(f"client scope {CLIENT_SCOPE_NAME}: created ({scope_id}); aud={audience}, claim={Claim.WEBID.value}")
    return scope_id


def create_service_client(admin: Admin, scope_id: str, client: ServiceClient) -> None:
    attributes: dict[str, str] = {}
    if client.token_lifespan_seconds is not None:
        attributes[ClientAttribute.ACCESS_TOKEN_LIFESPAN.value] = str(client.token_lifespan_seconds)
    _, _, headers = admin.call("POST", admin.realm_path("/clients"), {
        "clientId": client.client_id,
        "name": client.name,
        "description": client.description,
        "enabled": True,
        "protocol": Protocol.OIDC.value,
        "publicClient": False,
        "secret": client.secret,
        "serviceAccountsEnabled": True,       # client-credentials: the app as its own principal
        "directAccessGrantsEnabled": True,    # password grant: a human via this app (fixtures)
        "standardFlowEnabled": False,
        "implicitFlowEnabled": False,
        "attributes": attributes,
    })
    client_uuid = headers["Location"].rstrip("/").rsplit("/", 1)[-1]
    admin.call("PUT", admin.realm_path(f"/clients/{client_uuid}/default-client-scopes/{scope_id}"))
    # The service-account user is a real user: give it the same webid attribute humans have,
    # so the claim is produced by one mapper for every kind of principal.
    _, sa_user, _ = admin.call("GET", admin.realm_path(f"/clients/{client_uuid}/service-account-user"))
    assert isinstance(sa_user, dict)
    sa_user["attributes"] = {**sa_user.get("attributes", {}), Claim.WEBID.value: [client.webid]}
    admin.call("PUT", admin.realm_path(f"/users/{sa_user['id']}"), sa_user)
    lifespan = f", access tokens live {client.token_lifespan_seconds}s" if client.token_lifespan_seconds else ""
    print(f"client {client.client_id}: created, webid={client.webid}{lifespan}")


def create_human(admin: Admin, human: Human) -> None:
    admin.call("POST", admin.realm_path("/users"), {
        "username": human.username,
        "enabled": True,
        "emailVerified": True,
        "firstName": human.first_name,
        "lastName": human.last_name,
        "email": human.email,
        "attributes": {Claim.WEBID.value: [human.webid]},
        "credentials": [{"type": "password", "value": human.password, "temporary": False}],
    })
    print(f"user {human.username}: created, webid={human.webid}")


def main() -> None:
    admin = Admin(env(Env.BASE), env(Env.REALM))
    create_realm(admin)
    declare_webid_attribute(admin)
    scope_id = create_client_scope(admin)
    for client in (
        ServiceClient(env(Env.LEGAL_ID), env(Env.LEGAL_SECRET), env(Env.LEGAL_WEBID),
                      "ValueDocs Legal", "The legal application, as its own principal"),
        ServiceClient(env(Env.TAX_ID), env(Env.TAX_SECRET), env(Env.TAX_WEBID),
                      "ValueDocs Tax", "The tax application, as its own principal"),
        ServiceClient(env(Env.FIXTURE_ID), env(Env.FIXTURE_SECRET), env(Env.FIXTURE_WEBID),
                      "Fixture: short-lived tokens", "Exists only to mint the expired-token fixture",
                      int(env(Env.FIXTURE_LIFESPAN))),
    ):
        create_service_client(admin, scope_id, client)
    for human in (
        Human(env(Env.ALICE), env(Env.ALICE_PASSWORD), env(Env.ALICE_WEBID),
              "Alice", "Advocate", "alice@acme-law.example"),
        Human(env(Env.BOB), env(Env.BOB_PASSWORD), env(Env.BOB_WEBID),
              "Bob", "Client", "bob@acme-law.example"),
    ):
        create_human(admin, human)


if __name__ == "__main__":
    main()
