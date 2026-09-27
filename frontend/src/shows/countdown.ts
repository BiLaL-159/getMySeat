// A countdown in minutes and seconds, rounded up so it reads 0:00 only once time is up. The last
// minute is when it reads under 1:00.
export function countdown(msLeft: number) {
  const seconds = Math.max(0, Math.ceil(msLeft / 1000))
  return {
    text: `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`,
    lastMinute: seconds < 60,
    over: seconds === 0,
  }
}
