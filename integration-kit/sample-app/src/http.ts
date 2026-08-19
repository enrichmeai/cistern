/**
 * The HTTP vocabulary the app uses against Cistern, and one function that sends a request.
 * Plain global fetch (Node 20+); no SDK, because docs/INTEGRATION.md step 4 promises none is
 * needed.
 */

export enum HttpMethod {
  Get = 'GET',
  Post = 'POST',
  Put = 'PUT',
  Delete = 'DELETE',
}

/** Header names, in the lower-case form fetch's Headers reports them. */
export enum HeaderName {
  Authorization = 'authorization',
  ContentType = 'content-type',
  Accept = 'accept',
  WacAllow = 'wac-allow',
  WwwAuthenticate = 'www-authenticate',
}

export enum MediaType {
  Turtle = 'text/turtle',
  Pdf = 'application/pdf',
  Form = 'application/x-www-form-urlencoded',
  Json = 'application/json',
}

/** The statuses this story expects to see (docs/INTEGRATION.md §8). */
export enum HttpStatus {
  Ok = 200,
  Created = 201,
  NoContent = 204,
  Unauthorized = 401,
  Forbidden = 403,
  NotFound = 404,
}

const BEARER_PREFIX = 'Bearer ';
/** Cistern never redirects; a redirect would be a surprise worth seeing as its own status. */
const REDIRECT_POLICY = 'manual' satisfies NonNullable<RequestInit['redirect']>;
/** Statuses below this are success (2xx). */
export const FIRST_NON_SUCCESS_STATUS = 300;

/** How a request proves who it is — or does not. */
export type Credential =
  | { readonly kind: 'none' }
  | { readonly kind: 'bearer'; readonly token: string };

export const NO_CREDENTIAL: Credential = { kind: 'none' };
export function bearer(token: string): Credential {
  return { kind: 'bearer', token };
}

export interface HttpRequest {
  readonly method: HttpMethod;
  /** Pod path, starting with '/'. */
  readonly path: string;
  readonly credential: Credential;
  readonly body?: { readonly mediaType: MediaType; readonly text: string };
  readonly accept?: MediaType;
}

/** What came back, reduced to what the story prints and checks. */
export interface Outcome {
  readonly status: number;
  readonly wacAllow: string | undefined;
  readonly contentType: string | undefined;
  readonly bytes: number;
}

export async function send(base: URL, request: HttpRequest): Promise<Outcome> {
  const headers = new Headers();
  if (request.credential.kind === 'bearer') {
    headers.set(HeaderName.Authorization, BEARER_PREFIX + request.credential.token);
  }
  if (request.body) {
    headers.set(HeaderName.ContentType, request.body.mediaType);
  }
  if (request.accept) {
    headers.set(HeaderName.Accept, request.accept);
  }
  const response = await fetch(new URL(request.path, base), {
    method: request.method,
    headers,
    redirect: REDIRECT_POLICY,
    ...(request.body ? { body: request.body.text } : {}),
  });
  const payload = await response.arrayBuffer();
  return {
    status: response.status,
    wacAllow: response.headers.get(HeaderName.WacAllow) ?? undefined,
    contentType: response.headers.get(HeaderName.ContentType) ?? undefined,
    bytes: payload.byteLength,
  };
}
