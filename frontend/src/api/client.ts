import { getAccessToken } from '../auth/oidc';

export class HttpError extends Error {
  override readonly name = 'HttpError';

  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

/** Error bodies from the Spring services carry an optional `message`. */
interface ErrorBody {
  readonly message?: string;
}

async function errorFrom(response: Response): Promise<HttpError> {
  const body = (await response.json().catch(() => ({}))) as ErrorBody;
  return new HttpError(response.status, body.message ?? `HTTP ${response.status}`);
}

/**
 * Authenticated JSON request.
 *
 * This is the app's single trust boundary for server data: `response.json()` returns `any`, and the
 * `as T` cast below is the only place it becomes a typed value. Types are erased at runtime, so this
 * is a promise that the backend honours the contract in `src/types`, not a check. If that promise
 * ever needs enforcing, add a schema validator (e.g. Zod) here, and nowhere else.
 */
export async function request<T>(baseUrl: string, path: string, init: RequestInit = {}): Promise<T> {
  const response = await send(baseUrl, path, init);
  return (await response.json()) as T;
}

/** For endpoints that answer 204 No Content. A separate function, so no caller can ask for a `T` that never arrives. */
export async function requestNoContent(baseUrl: string, path: string, init: RequestInit = {}): Promise<void> {
  await send(baseUrl, path, init);
}

async function send(baseUrl: string, path: string, init: RequestInit): Promise<Response> {
  const token = await getAccessToken();
  const headers = new Headers(init.headers);
  headers.set('Authorization', `Bearer ${token}`);

  const response = await fetch(`${baseUrl}${path}`, { ...init, headers });
  if (!response.ok) throw await errorFrom(response);
  return response;
}

/** JSON body helper: sets the header and serializes in one place. */
export function jsonBody(body: unknown): RequestInit {
  return { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
}
