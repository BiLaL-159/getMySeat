import { useQuery } from '@tanstack/react-query'
import { api } from './api.ts'

// The cities with an upcoming published Show, sorted, the same for everyone.
export function useCities() {
  return useQuery({
    queryKey: ['cities'],
    queryFn: async () => {
      // A failure rejects with an ApiError, so data is there.
      const { data } = await api.GET('/api/v1/cities')
      return data!
    },
  })
}
