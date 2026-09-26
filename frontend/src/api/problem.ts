// Every failed API call becomes an ApiError carrying one of these, so screens switch on `kind`
// instead of reading status codes. The kinds follow the ProblemDetail `type`s in docs/api.md; the
// Hold-specific 409s count as conflicts until a screen needs their extra fields.
export type Problem =
  | { kind: 'validation'; status: number; detail?: string; fieldErrors: Record<string, string[]> }
  | { kind: KnownKind; status: number; detail?: string }
  // A type this client doesn't know yet, or a body that isn't a ProblemDetail.
  | { kind: 'unknown'; status: number; detail?: string }
  // No response at all: the backend is down or the network failed.
  | { kind: 'unreachable' }

const knownKinds = ['conflict', 'not-found', 'forbidden', 'upstream-unavailable', 'unauthorized'] as const
type KnownKind = (typeof knownKinds)[number]

const conflictKinds = ['inventory-unavailable', 'idempotency-key-reused']

const typePrefix = 'urn:getmyseat:problem:'

export class ApiError extends Error {
  readonly problem: Problem

  constructor(problem: Problem, options?: ErrorOptions) {
    super('status' in problem ? `API call failed: ${problem.kind} (${problem.status})` : 'API unreachable', options)
    this.name = 'ApiError'
    this.problem = problem
  }
}

// Worth another go: nothing answered, or the server failed. A 4xx will only fail the same way again.
export function unreachable(cause: unknown) {
  return new ApiError({ kind: 'unreachable' }, { cause })
}

export function isRetryable(error: unknown) {
  if (!(error instanceof ApiError)) return false
  const { problem } = error
  return problem.kind === 'unreachable' || problem.status >= 500
}

export function problemFrom(status: number, body: unknown): Problem {
  const { type, detail, errors } = (typeof body === 'object' && body !== null ? body : {}) as {
    type?: unknown
    detail?: unknown
    errors?: unknown
  }
  const kind = typeof type === 'string' && type.startsWith(typePrefix) ? type.slice(typePrefix.length) : undefined
  const common = { status, detail: typeof detail === 'string' ? detail : undefined }

  if (kind === 'validation') return { kind, ...common, fieldErrors: fieldErrorsOf(errors) }
  if (conflictKinds.includes(kind!)) return { kind: 'conflict', ...common }
  if (knownKinds.includes(kind as KnownKind)) return { kind: kind as KnownKind, ...common }
  return { kind: 'unknown', ...common }
}

function fieldErrorsOf(errors: unknown) {
  const fieldErrors: Record<string, string[]> = {}
  if (!Array.isArray(errors)) return fieldErrors
  for (const { field, message } of errors as { field?: unknown; message?: unknown }[]) {
    if (typeof field === 'string' && typeof message === 'string') (fieldErrors[field] ??= []).push(message)
  }
  return fieldErrors
}
