import { describe, expect, it } from 'vitest'
import { ApiError, isRetryable, problemFrom } from './problem.ts'

const problem = (name: string, status: number, extra: object = {}) => ({
  type: `urn:getmyseat:problem:${name}`,
  title: 'Title',
  status,
  detail: `The ${name} detail.`,
  ...extra,
})

describe('problemFrom', () => {
  it('keeps the per-field errors of a validation problem', () => {
    const body = problem('validation', 400, {
      errors: [
        { field: 'name', message: 'must not be blank' },
        { field: 'city', message: 'must not be blank' },
        { field: 'name', message: 'size must be at most 100' },
      ],
    })

    expect(problemFrom(400, body)).toEqual({
      kind: 'validation',
      status: 400,
      detail: 'The validation detail.',
      fieldErrors: { name: ['must not be blank', 'size must be at most 100'], city: ['must not be blank'] },
    })
  })

  it.each([
    ['conflict', 409],
    ['not-found', 404],
    ['forbidden', 403],
    ['upstream-unavailable', 503],
    ['unauthorized', 401],
  ] as const)('maps a %s problem by its type', (kind, status) => {
    expect(problemFrom(status, problem(kind, status))).toEqual({ kind, status, detail: `The ${kind} detail.` })
  })

  it.each(['inventory-unavailable', 'idempotency-key-reused'])('treats %s as a conflict', (type) => {
    expect(problemFrom(409, problem(type, 409))).toMatchObject({ kind: 'conflict', status: 409 })
  })

  it('falls back for a type it does not know, keeping the status and detail', () => {
    expect(problemFrom(500, problem('internal-error', 500))).toEqual({
      kind: 'unknown',
      status: 500,
      detail: 'The internal-error detail.',
    })
  })

  it('falls back for a body that is not a ProblemDetail', () => {
    expect(problemFrom(502, '<html>Bad Gateway</html>')).toEqual({ kind: 'unknown', status: 502, detail: undefined })
  })
})

describe('isRetryable', () => {
  it.each([
    [{ kind: 'unreachable' }, true],
    [{ kind: 'upstream-unavailable', status: 503 }, true],
    [{ kind: 'unknown', status: 500 }, true],
    [{ kind: 'unknown', status: 405 }, false],
    [{ kind: 'not-found', status: 404 }, false],
    [{ kind: 'forbidden', status: 403 }, false],
    [{ kind: 'unauthorized', status: 401 }, false],
  ] as const)('retries %j: %s', (problem, retryable) => {
    expect(isRetryable(new ApiError(problem))).toBe(retryable)
  })

  it('does not retry an error that did not come from the API', () => {
    expect(isRetryable(new Error('bug'))).toBe(false)
  })
})
