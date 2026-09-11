#!/usr/bin/env python3
"""Builds the kit's realm on a blank Keycloak through the admin REST API.

Called by build-realm.sh, which then stops the server and runs `kc.sh export` so that
realm-cistern.json is a genuine Keycloak export (ground rule 6: real-first). Every identity
comes from identities.env (exported into the environment by lib/kit.sh); nothing is
invented here. Standard library only.

Brokered sign-in (T7.16) adds three steps after the identities: a first-broker-login flow
that never asks the person anything, the Google Workspace provider when its three values
are set, and — always, when its values are set — a second realm that stands in for the
external identity provider so the mechanics are proven without a Google account.
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
    ISSUER = "KEYCLOAK_ISSUER"            # the realm's issuer as every party sees it
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
    LEGAL_REDIRECT_URI = "KEYCLOAK_CLIENT_LEGAL_REDIRECT_URI"
    TAX_ID = "KEYCLOAK_CLIENT_TAX_ID"
    TAX_SECRET = "KEYCLOAK_CLIENT_TAX_SECRET"
    TAX_WEBID = "KEYCLOAK_CLIENT_TAX_WEBID"
    FIXTURE_ID = "KEYCLOAK_CLIENT_FIXTURE_ID"
    FIXTURE_SECRET = "KEYCLOAK_CLIENT_FIXTURE_SECRET"
    FIXTURE_WEBID = "KEYCLOAK_CLIENT_FIXTURE_WEBID"
    FIXTURE_LIFESPAN = "KEYCLOAK_CLIENT_FIXTURE_TOKEN_LIFESPAN_SECONDS"
    GOOGLE_CLIENT_ID = "GOOGLE_CLIENT_ID"
    GOOGLE_CLIENT_SECRET = "GOOGLE_CLIENT_SECRET"
    GOOGLE_HOSTED_DOMAIN = "GOOGLE_HOSTED_DOMAIN"
    BROKER_REALM = "KEYCLOAK_BROKER_REALM"
    BROKER_CAROL = "KEYCLOAK_BROKER_USER_CAROL"
    BROKER_CAROL_PASSWORD = "KEYCLOAK_BROKER_USER_CAROL_PASSWORD"
    BROKER_CLIENT_ID = "KEYCLOAK_BROKER_CLIENT_ID"
    BROKER_CLIENT_SECRET = "KEYCLOAK_BROKER_CLIENT_SECRET"


def env(name: Env) -> str:
    value = os.environ.get(name.value)
    if not value:
        sys.exit(f"{name.value} is not set — source lib/kit.sh first")
    return value


def optional_env(name: Env) -> str:
    """Blank and unset are the same thing: the feature the value configures is off."""
    return os.environ.get(name.value, "")


def all_or_none(names: tuple[Env, ...]) -> bool:
    """True when every value is set, False when every value is blank; a partial set exits.

    A partial set is a misconfiguration that would otherwise turn into a provider with a
    missing secret or an unrestricted hosted domain — refused here, not discovered later.
    """
    values = {name: optional_env(name) for name in names}
    if all(values.values()):
        return True
    if not any(values.values()):
        return False
    missing = ", ".join(name.value for name, value in values.items() if not value)
    present = ", ".join(name.value for name, value in values.items() if value)
    sys.exit(f"{present} set but {missing} blank: set all of them or none")


# ---- Keycloak vocabulary ---------------------------------------------------------------
class Path(str, Enum):
    ADMIN_TOKEN = "/realms/master/protocol/openid-connect/token"
    REALMS = "/admin/realms"


class RealmPath(str, Enum):
    """Suffixes under /admin/realms/{realm}."""
    USERS = "/users"
    USER_PROFILE = "/users/profile"
    CLIENTS = "/clients"
    CLIENT_SCOPES = "/client-scopes"
    IDENTITY_PROVIDERS = "/identity-provider/instances"
    FLOWS = "/authentication/flows"


class Endpoint(str, Enum):
    """OIDC endpoints of a realm, relative to its issuer."""
    AUTHORIZATION = "/protocol/openid-connect/auth"
    TOKEN = "/protocol/openid-connect/token"
    JWKS = "/protocol/openid-connect/certs"


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


class IdentityProviderType(str, Enum):
    """Keycloak's providerId for an identity provider."""
    OIDC = "oidc"
    GOOGLE = "google"


class Authenticator(str, Enum):
    """providerId of an execution in an authentication flow."""
    REVIEW_PROFILE = "idp-review-profile"


class Requirement(str, Enum):
    DISABLED = "DISABLED"


class SyncMode(str, Enum):
    """What happens to the local profile on later logins: IMPORT copies it once."""
    IMPORT = "IMPORT"


class ClientAuthMethod(str, Enum):
    CLIENT_SECRET_POST = "client_secret_post"


class Component(str, Enum):
    KEY_PROVIDER = "org.keycloak.keys.KeyProvider"


ADMIN_CLI_CLIENT = "admin-cli"
ADMIN_TOKEN_ATTEMPTS = 2                     # the call, and once more with a fresh token after a 401
CLIENT_SCOPE_NAME = "cistern"
CLIENT_SCOPE_DESCRIPTION = "Cistern: aud=cistern plus the webid claim every principal carries"
REALM_DISPLAY_NAME = "Cistern integration kit"
BROKER_REALM_DISPLAY_NAME = "Stand-in for an external identity provider"
BROKER_CLIENT_NAME = "The cistern realm, brokering to this one"
BROKER_PROVIDER_DISPLAY_NAME = "Sign in with the stand-in provider"
BROKER_SCOPES = "openid profile email"       # what the cistern realm asks the provider for
GOOGLE_ALIAS = "google"
GOOGLE_DISPLAY_NAME = "Sign in with Google"
FIRST_BROKER_LOGIN_FLOW = "first broker login"                   # Keycloak's built-in flow
FIRST_BROKER_LOGIN_WITHOUT_REVIEW = "first broker login without review"
REALMS_SEGMENT = "/realms/"
EXPORT_FILE = "realm-{realm}.json"           # build-realm.sh writes the same name
JSON = "application/json"
FORM = "application/x-www-form-urlencoded"
HTTP_CREATED = 201
HTTP_NO_CONTENT = 204
HTTP_UNAUTHORIZED = 401
HTTP_CONFLICT = 409


@dataclass(frozen=True)
class Human:
    username: str
    password: str
    webid: str | None       # None: no attribute — the WebID is built from the token (T7.16)
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
    redirect_uris: tuple[str, ...] = ()      # non-empty: humans sign in through this client in a browser


@dataclass(frozen=True)
class Google:
    client_id: str
    client_secret: str
    hosted_domain: str

    @staticmethod
    def from_env() -> Google | None:
        if not all_or_none((Env.GOOGLE_CLIENT_ID, Env.GOOGLE_CLIENT_SECRET, Env.GOOGLE_HOSTED_DOMAIN)):
            return None
        return Google(env(Env.GOOGLE_CLIENT_ID), env(Env.GOOGLE_CLIENT_SECRET), env(Env.GOOGLE_HOSTED_DOMAIN))


@dataclass(frozen=True)
class BrokerRealm:
    """A second realm acting as the external identity provider; its name is the alias too."""
    realm: str
    client_id: str
    client_secret: str
    carol: Human

    @staticmethod
    def from_env() -> BrokerRealm | None:
        if not all_or_none((Env.BROKER_REALM, Env.BROKER_CAROL, Env.BROKER_CAROL_PASSWORD,
                            Env.BROKER_CLIENT_ID, Env.BROKER_CLIENT_SECRET)):
            return None
        return BrokerRealm(env(Env.BROKER_REALM), env(Env.BROKER_CLIENT_ID), env(Env.BROKER_CLIENT_SECRET),
                           Human(env(Env.BROKER_CAROL), env(Env.BROKER_CAROL_PASSWORD), None,
                                 "Carol", "Counsel", "carol@acme-law.example"))


# ---- a very small admin client ---------------------------------------------------------
class Admin:
    def __init__(self, base: str, realm: str, token: str | None = None) -> None:
        self.base = base.rstrip("/")
        self.realm = realm
        self.token = token or self._admin_token()

    def for_realm(self, realm: str) -> Admin:
        """The same session (one master-realm token) addressing another realm."""
        return Admin(self.base, realm, self.token)

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
        for attempt in range(ADMIN_TOKEN_ATTEMPTS):
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
                if e.code == HTTP_UNAUTHORIZED and attempt == 0:
                    # The master realm's admin token lives a minute; a build on a cold, busy server
                    # can outlive it. Once: a fresh token, the same call again.
                    self.token = self._admin_token()
                    continue
                if e.code in expect:
                    return e.code, None, dict(e.headers)
                sys.exit(f"{method} {path} -> {e.code}: {e.read().decode(errors='replace')}")
        raise AssertionError("unreachable: every attempt returns or exits")

    def realm_path(self, suffix: str = "") -> str:
        return f"{Path.REALMS.value}/{self.realm}{suffix}"

    def flow_path(self, alias: str, suffix: str = "") -> str:
        return self.realm_path(f"{RealmPath.FLOWS.value}/{urllib.parse.quote(alias)}{suffix}")


# ---- where things are --------------------------------------------------------------------
def issuer_of(realm: str) -> str:
    """The issuer of any realm on this server, derived from the kit's one issuer string.

    KEYCLOAK_ISSUER is the origin every party uses (KC_HOSTNAME in docker-compose.yml), so
    the broker URLs the cistern realm stores share it — the same string in a token's `iss`,
    in a redirect the browser follows, and in the server-side call that redeems a code.
    """
    cistern_issuer = env(Env.ISSUER)
    origin, found, _ = cistern_issuer.partition(REALMS_SEGMENT)
    if not found:
        sys.exit(f"{Env.ISSUER.value} must look like <origin>{REALMS_SEGMENT}<realm>, got {cistern_issuer}")
    return f"{origin}{REALMS_SEGMENT}{realm}"


def previous_key_providers(realm: str) -> list | None:
    """The realm's signing keys from the export this build is about to replace, if any.

    A blank server would mint new keys, and every token and JWKS captured in fixtures/
    would stop verifying. Creating the realm with the previous key components keeps the
    keys — a regeneration then changes what the realm contains, never what signs for it.
    """
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), EXPORT_FILE.format(realm=realm))
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as export:
        return json.load(export).get("components", {}).get(Component.KEY_PROVIDER.value)


# ---- the realm ------------------------------------------------------------------------
def create_realm(admin: Admin, display_name: str) -> None:
    representation: dict = {
        "realm": admin.realm,
        "enabled": True,
        "displayName": display_name,
        "registrationAllowed": False,
        # 5 minutes: Keycloak's default, kept on purpose so tokens behave like real ones.
        "accessTokenLifespan": 300,
    }
    keys = previous_key_providers(admin.realm)
    if keys:
        representation["components"] = {Component.KEY_PROVIDER.value: keys}
    status, _, _ = admin.call("POST", Path.REALMS.value, representation, expect=(HTTP_CREATED, HTTP_CONFLICT))
    if status == HTTP_CONFLICT:
        sys.exit(f"realm '{admin.realm}' already exists on {admin.base}: build against a blank server")
    provenance = f"keys kept from {EXPORT_FILE.format(realm=admin.realm)}" if keys else "keys freshly generated"
    print(f"realm {admin.realm}: created ({provenance})")


def declare_webid_attribute(admin: Admin) -> None:
    """Keycloak 24+ user profiles are managed: an undeclared attribute is dropped on write."""
    _, profile, _ = admin.call("GET", admin.realm_path(RealmPath.USER_PROFILE.value))
    assert isinstance(profile, dict)
    if not any(a.get("name") == Claim.WEBID.value for a in profile["attributes"]):
        profile["attributes"].append({
            "name": Claim.WEBID.value,
            "displayName": "WebID",
            "validations": {"uri": {}},
            "permissions": {"view": ["admin", "user"], "edit": ["admin"]},
            "multivalued": False,
        })
        admin.call("PUT", admin.realm_path(RealmPath.USER_PROFILE.value), profile)
    print(f"user profile: attribute '{Claim.WEBID.value}' declared")


def create_client_scope(admin: Admin) -> str:
    audience = env(Env.AUDIENCE)
    _, _, headers = admin.call("POST", admin.realm_path(RealmPath.CLIENT_SCOPES.value), {
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
    _, _, headers = admin.call("POST", admin.realm_path(RealmPath.CLIENTS.value), {
        "clientId": client.client_id,
        "name": client.name,
        "description": client.description,
        "enabled": True,
        "protocol": Protocol.OIDC.value,
        "publicClient": False,
        "secret": client.secret,
        "serviceAccountsEnabled": True,       # client-credentials: the app as its own principal
        "directAccessGrantsEnabled": True,    # password grant: a human via this app (fixtures)
        "standardFlowEnabled": bool(client.redirect_uris),   # browser: a human via this app, brokered or not
        "redirectUris": list(client.redirect_uris),
        "implicitFlowEnabled": False,
        "attributes": attributes,
    })
    client_uuid = headers["Location"].rstrip("/").rsplit("/", 1)[-1]
    admin.call("PUT", admin.realm_path(f"{RealmPath.CLIENTS.value}/{client_uuid}/default-client-scopes/{scope_id}"))
    # The service-account user is a real user: give it the same webid attribute humans have,
    # so the claim is produced by one mapper for every kind of principal.
    _, sa_user, _ = admin.call("GET", admin.realm_path(f"{RealmPath.CLIENTS.value}/{client_uuid}/service-account-user"))
    assert isinstance(sa_user, dict)
    sa_user["attributes"] = {**sa_user.get("attributes", {}), Claim.WEBID.value: [client.webid]}
    admin.call("PUT", admin.realm_path(f"{RealmPath.USERS.value}/{sa_user['id']}"), sa_user)
    lifespan = f", access tokens live {client.token_lifespan_seconds}s" if client.token_lifespan_seconds else ""
    browser = f", browser sign-in redirects to {', '.join(client.redirect_uris)}" if client.redirect_uris else ""
    print(f"client {client.client_id}: created, webid={client.webid}{lifespan}{browser}")


def create_human(admin: Admin, human: Human) -> None:
    representation: dict = {
        "username": human.username,
        "enabled": True,
        "emailVerified": True,
        "firstName": human.first_name,
        "lastName": human.last_name,
        "email": human.email,
        "credentials": [{"type": "password", "value": human.password, "temporary": False}],
    }
    if human.webid is not None:
        representation["attributes"] = {Claim.WEBID.value: [human.webid]}
    admin.call("POST", admin.realm_path(RealmPath.USERS.value), representation)
    identity = f"webid={human.webid}" if human.webid else "no webid attribute (the WebID is built from the token)"
    print(f"user {human.username}: created in realm {admin.realm}, {identity}")


# ---- brokered sign-in (T7.16) -------------------------------------------------------------
def create_first_broker_login_flow(admin: Admin) -> str:
    """Keycloak's built-in first-broker-login flow with the review-profile step disabled.

    The built-in flow shows a "review your profile" page on a person's first brokered login
    whenever the provider's profile fails validation; the copy never asks. A Workspace
    account always carries a name and an email, and the user is created from those
    (`Create User If Unique`). Copying rather than editing the built-in flow leaves
    Keycloak's own flow as it ships.
    """
    status, _, _ = admin.call("POST", admin.flow_path(FIRST_BROKER_LOGIN_FLOW, "/copy"),
                              {"newName": FIRST_BROKER_LOGIN_WITHOUT_REVIEW}, expect=(HTTP_CREATED, HTTP_CONFLICT))
    _, executions, _ = admin.call("GET", admin.flow_path(FIRST_BROKER_LOGIN_WITHOUT_REVIEW, "/executions"))
    assert isinstance(executions, list)
    for execution in executions:
        if (execution.get("providerId") == Authenticator.REVIEW_PROFILE.value
                and execution["requirement"] != Requirement.DISABLED.value):
            execution["requirement"] = Requirement.DISABLED.value
            admin.call("PUT", admin.flow_path(FIRST_BROKER_LOGIN_WITHOUT_REVIEW, "/executions"), execution)
    outcome = "created" if status == HTTP_CREATED else "already there"
    print(f"flow '{FIRST_BROKER_LOGIN_WITHOUT_REVIEW}': {outcome}; {Authenticator.REVIEW_PROFILE.value} disabled")
    return FIRST_BROKER_LOGIN_WITHOUT_REVIEW


def create_identity_provider(admin: Admin, representation: dict) -> str:
    status, _, _ = admin.call("POST", admin.realm_path(RealmPath.IDENTITY_PROVIDERS.value), representation,
                              expect=(HTTP_CREATED, HTTP_CONFLICT))
    return "created" if status == HTTP_CREATED else "already there"


def create_google_provider(admin: Admin, google: Google, first_login_flow: str) -> None:
    """Keycloak's built-in Google provider: endpoints and issuer are Keycloak's own knowledge.

    `hostedDomain` is sent as Google's `hd` parameter and checked on the returned identity,
    so only accounts of that Workspace domain get in; `trustEmail` skips a verification mail
    the Workspace has already done.
    """
    outcome = create_identity_provider(admin, {
        "alias": GOOGLE_ALIAS,
        "displayName": GOOGLE_DISPLAY_NAME,
        "providerId": IdentityProviderType.GOOGLE.value,
        "enabled": True,
        "trustEmail": True,
        "storeToken": False,
        "firstBrokerLoginFlowAlias": first_login_flow,
        "config": {
            "clientId": google.client_id,
            "clientSecret": google.client_secret,
            "hostedDomain": google.hosted_domain,
            "syncMode": SyncMode.IMPORT.value,
        },
    })
    print(f"identity provider {GOOGLE_ALIAS}: {outcome}, hosted domain {google.hosted_domain}, "
          f"redirect URI {issuer_of(admin.realm)}/broker/{GOOGLE_ALIAS}/endpoint")


def create_broker_realm(admin: Admin, broker: BrokerRealm, first_login_flow: str) -> None:
    """A second realm on the same server plays the external provider.

    The cistern realm is registered in it as an ordinary confidential client whose only
    redirect URI is the cistern realm's broker endpoint, and the cistern realm gets an
    `oidc` identity provider pointing at the second realm's endpoints — the same shape
    Keycloak's Google provider has built in. Carol exists only here.
    """
    broker_admin = admin.for_realm(broker.realm)
    create_realm(broker_admin, BROKER_REALM_DISPLAY_NAME)
    create_human(broker_admin, broker.carol)
    cistern_broker_endpoint = f"{issuer_of(admin.realm)}/broker/{broker.realm}/endpoint"
    status, _, _ = broker_admin.call("POST", broker_admin.realm_path(RealmPath.CLIENTS.value), {
        "clientId": broker.client_id,
        "name": BROKER_CLIENT_NAME,
        "enabled": True,
        "protocol": Protocol.OIDC.value,
        "publicClient": False,
        "secret": broker.client_secret,
        "standardFlowEnabled": True,
        "directAccessGrantsEnabled": False,
        "serviceAccountsEnabled": False,
        "implicitFlowEnabled": False,
        "redirectUris": [cistern_broker_endpoint],
    }, expect=(HTTP_CREATED, HTTP_CONFLICT))
    outcome = "created" if status == HTTP_CREATED else "already there"
    print(f"client {broker.client_id}: {outcome} in realm {broker.realm}, redirects only to {cistern_broker_endpoint}")

    broker_issuer = issuer_of(broker.realm)
    outcome = create_identity_provider(admin, {
        "alias": broker.realm,
        "displayName": BROKER_PROVIDER_DISPLAY_NAME,
        "providerId": IdentityProviderType.OIDC.value,
        "enabled": True,
        "trustEmail": True,
        "storeToken": False,
        "firstBrokerLoginFlowAlias": first_login_flow,
        "config": {
            "clientId": broker.client_id,
            "clientSecret": broker.client_secret,
            "clientAuthMethod": ClientAuthMethod.CLIENT_SECRET_POST.value,
            "authorizationUrl": broker_issuer + Endpoint.AUTHORIZATION.value,
            "tokenUrl": broker_issuer + Endpoint.TOKEN.value,
            "jwksUrl": broker_issuer + Endpoint.JWKS.value,
            "useJwksUrl": "true",
            "validateSignature": "true",
            "issuer": broker_issuer,
            "defaultScope": BROKER_SCOPES,
            "syncMode": SyncMode.IMPORT.value,
        },
    })
    print(f"identity provider {broker.realm}: {outcome} in realm {admin.realm}, issuer {broker_issuer}")


def main() -> None:
    admin = Admin(env(Env.BASE), env(Env.REALM))
    create_realm(admin, REALM_DISPLAY_NAME)
    declare_webid_attribute(admin)
    scope_id = create_client_scope(admin)
    for client in (
        ServiceClient(env(Env.LEGAL_ID), env(Env.LEGAL_SECRET), env(Env.LEGAL_WEBID),
                      "ValueDocs Legal", "The legal application, as its own principal",
                      redirect_uris=(env(Env.LEGAL_REDIRECT_URI),)),
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

    first_login_flow = create_first_broker_login_flow(admin)
    google = Google.from_env()
    if google is not None:
        create_google_provider(admin, google, first_login_flow)
    else:
        print(f"identity provider {GOOGLE_ALIAS}: not configured ({Env.GOOGLE_CLIENT_ID.value} blank), skipped")
    broker = BrokerRealm.from_env()
    if broker is not None:
        create_broker_realm(admin, broker, first_login_flow)
    else:
        print(f"broker realm: not configured ({Env.BROKER_REALM.value} blank), skipped")


if __name__ == "__main__":
    main()
