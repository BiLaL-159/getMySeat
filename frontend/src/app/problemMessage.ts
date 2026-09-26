import { ApiError } from '@/api/problem.ts'

// A sentence for the visitor about what went wrong. Screens with more to say (a form showing its
// field errors, say) switch on the problem's kind themselves.
export function problemMessage(error: unknown) {
  const problem = error instanceof ApiError ? error.problem : undefined
  switch (problem?.kind) {
    case 'unreachable':
      return 'We can’t reach getMySeat right now. Check your connection and try again.'
    case 'upstream-unavailable':
      return 'A service we rely on is unavailable. It’s safe to try again.'
    case 'forbidden':
      return 'You’re not allowed to do that.'
    case 'not-found':
      return 'We couldn’t find what you were looking for.'
    case 'conflict':
      return 'Something changed in the meantime. Reload and try again.'
    case 'validation':
      return 'Some details need fixing.'
    case 'unauthorized':
      return 'Your session has expired. Sign in again.'
    default:
      return 'Try again in a moment.'
  }
}
