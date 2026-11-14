// Thin client for the Spring Boot API on the same origin.
// - Session: HttpOnly cookie, sent automatically (same origin).
// - CSRF: the server sets a readable XSRF-TOKEN cookie; every write echoes it in X-XSRF-TOKEN.
// - Errors: problem+json with a stable `code`, surfaced as ApiError.

export interface FieldError {
  field: string
  message: string
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors: FieldError[]
  readonly retryAfterSeconds?: number
  /** Extra problem+json fields, e.g. existingId for DUPLICATE_MEDICINE. */
  readonly extra: Record<string, unknown>

  constructor(
    status: number,
    code: string,
    message: string,
    fieldErrors: FieldError[] = [],
    retryAfterSeconds?: number,
    extra: Record<string, unknown> = {},
  ) {
    super(message)
    this.extra = extra
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
    this.retryAfterSeconds = retryAfterSeconds
  }

  /** The server is asleep/starting (free hosting) or unreachable: worth retrying. */
  get retryable(): boolean {
    return this.status === 0 || this.status === 502 || this.status === 503 || this.status === 504
  }
}

export interface Me {
  user: { id: string; email: string; displayName: string }
  household: { id: string; name: string; timezone: string; defaultWarnDays: number }
}

export interface SetupRequest {
  setupToken: string
  householdName: string
  displayName: string
  email: string
  password: string
  timezone?: string
}

function readCookie(name: string): string | undefined {
  const prefix = name + '='
  const found = document.cookie.split('; ').find((c) => c.startsWith(prefix))
  return found ? decodeURIComponent(found.slice(prefix.length)) : undefined
}

async function ensureCsrfCookie(): Promise<void> {
  if (!readCookie('XSRF-TOKEN')) {
    await request('GET', '/api/v1/auth/csrf')
  }
}

export async function request<T>(
  method: string,
  path: string,
  body?: unknown,
  extraHeaders: Record<string, string> = {},
  retriedCsrf = false,
): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', ...extraHeaders }
  if (method !== 'GET') {
    await ensureCsrfCookie()
    const token = readCookie('XSRF-TOKEN')
    if (token) headers['X-XSRF-TOKEN'] = token
    headers['Content-Type'] = 'application/json'
  }

  let res: Response
  try {
    res = await fetch(path, {
      method,
      headers,
      credentials: 'same-origin',
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError(0, 'NETWORK', 'Cannot reach the server.')
  }

  if (res.ok) {
    return (res.status === 204 ? undefined : await res.json()) as T
  }

  let problem: { code?: string; detail?: string; errors?: FieldError[]; [key: string]: unknown } = {}
  try {
    problem = await res.json()
  } catch {
    // not JSON (e.g. the hosting proxy's own error page while the server wakes up)
  }

  // Stale CSRF cookie (e.g. after logout or a server restart): drop it, fetch a fresh one, retry once.
  if (res.status === 403 && problem.code === 'CSRF_TOKEN_INVALID' && !retriedCsrf) {
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/; Secure; SameSite=Lax'
    return request<T>(method, path, body, extraHeaders, true)
  }

  const retryAfter = Number(res.headers.get('Retry-After'))
  throw new ApiError(
    res.status,
    problem.code ?? `HTTP_${res.status}`,
    problem.detail ?? res.statusText,
    problem.errors ?? [],
    Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined,
    problem,
  )
}

export const api = {
  setupStatus: () => request<{ setupRequired: boolean }>('GET', '/api/v1/auth/setup-status'),
  me: () => request<Me>('GET', '/api/v1/auth/me'),
  setup: (body: SetupRequest) => request<Me>('POST', '/api/v1/auth/setup', body),
  login: (email: string, password: string) => request<Me>('POST', '/api/v1/auth/login', { email, password }),
  logout: () => request<void>('POST', '/api/v1/auth/logout'),
}
