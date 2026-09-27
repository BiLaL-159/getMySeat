// Every failed API call becomes an ApiError carrying one of these, so screens switch on `kind`
// instead of reading status codes. The kinds follow the ProblemDetail `type`s in docs/api.md.
export type Problem =
  | { kind: 'validation'; status: number; detail?: string; fieldErrors: Record<string, string[]> }
  // A Hold that failed because some of what it asked for is gone.
  | ({ kind: 'inventory-unavailable'; status: number; detail?: string } & Lost)
  | { kind: KnownKind; status: number; detail?: string }
  // A type this client doesn't know yet, or a body that isn't a ProblemDetail.
  | { kind: 'unknown'; status: number; detail?: string }
  // No response at all: the backend is down or the network failed.
  | { kind: 'unreachable' }

const knownKinds = ['conflict', 'not-found', 'forbidden', 'upstream-unavailable', 'unauthorized', 'idempotency-key-reused'] as const
type KnownKind = (typeof knownKinds)[number]

// What an inventory-unavailable problem says is gone: Seats someone else holds, and General
// Admission Sections without enough places left. Both are empty when a race with other Holds was
// the cause, so it's worth trying again.
export type Lost = { unavailableSeats: string[]; unavailableSections: UnavailableSection[] }
export type UnavailableSection = { sectionId: string; available: number }

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

// Whether a failed Hold is worth sending again with the same Idempotency-Key: nothing answered,
// the server failed, or it lost a race with other Holds (a plain conflict, or inventory-unavailable
// naming nothing). Anything actually gone won't come back by asking again.
export function shouldRetryHold(failures: number, error: unknown) {
  if (failures >= maxHoldRetries || !(error instanceof ApiError)) return false
  const { problem } = error
  if (problem.kind === 'inventory-unavailable') return isRace(problem)
  return problem.kind === 'conflict' || isRetryable(error)
}

const maxHoldRetries = 3

// Whether an inventory-unavailable problem came from a race rather than anything being gone.
export function isRace(lost: Lost) {
  return lost.unavailableSeats.length === 0 && lost.unavailableSections.length === 0
}

export function problemFrom(status: number, body: unknown): Problem {
  const { type, detail, errors, unavailableSeats, unavailableSections } = (typeof body === 'object' && body !== null ? body : {}) as {
    type?: unknown
    detail?: unknown
    errors?: unknown
    unavailableSeats?: unknown
    unavailableSections?: unknown
  }
  const kind = typeof type === 'string' && type.startsWith(typePrefix) ? type.slice(typePrefix.length) : undefined
  const common = { status, detail: typeof detail === 'string' ? detail : undefined }

  if (kind === 'validation') return { kind, ...common, fieldErrors: fieldErrorsOf(errors) }
  if (kind === 'inventory-unavailable') {
    return {
      kind,
      ...common,
      unavailableSeats: Array.isArray(unavailableSeats) ? unavailableSeats.filter((id) => typeof id === 'string') : [],
      unavailableSections: unavailableSectionsOf(unavailableSections),
    }
  }
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

function unavailableSectionsOf(sections: unknown): UnavailableSection[] {
  if (!Array.isArray(sections)) return []
  return (sections as { sectionId?: unknown; available?: unknown }[]).flatMap(({ sectionId, available }) =>
    typeof sectionId === 'string' && typeof available === 'number' ? [{ sectionId, available }] : [],
  )
}
