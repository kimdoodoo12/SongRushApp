// The in-site feedback request (cycle 082, D-096). The server turns a post
// into a GitHub issue; the client only learns the outcome. The global fetch
// is read at call time (not App's fetchFn, which stays the audio fetch).

export const FEEDBACK_URL = '/api/feedback'

// The error codes the server answers with, to the outcome the dialog shows.
const OUTCOME_BY_ERROR = {
  'feedback-disabled': 'disabled',
  'rate-limited': 'rateLimited',
  'bad-feedback': 'bad',
  'feedback-failed': 'failed',
}

// The server's error code, or null when the body is not the expected JSON
// (a proxy's HTML error page, for example).
async function errorCode(response) {
  try {
    const body = await response.json()
    return body !== null && typeof body === 'object' && typeof body.error === 'string' ? body.error : null
  } catch (err) {
    console.warn('feedback: unreadable error body', response.status, err)
    return null
  }
}

// Posts {category, title, body, lang, page, website}. Resolves to 'ok',
// 'disabled', 'rateLimited', 'bad', 'failed' or 'network'; never rejects.
// Only a 503 that says feedback-disabled is 'disabled': another 503 is a
// server or proxy fault, so the form stays and offers a retry.
export async function sendFeedback(payload) {
  let response
  try {
    response = await globalThis.fetch(FEEDBACK_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
  } catch (err) {
    console.warn('feedback: request failed', err)
    return 'network'
  }
  if (response.status === 201) return 'ok'
  const code = await errorCode(response)
  if (code !== null && Object.hasOwn(OUTCOME_BY_ERROR, code)) return OUTCOME_BY_ERROR[code]
  if (response.status === 429) return 'rateLimited'
  if (response.status === 400) return 'bad'
  return 'failed'
}
