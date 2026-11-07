// The owner's feedback admin requests (cycle 083, D-097). Nginx guards
// /admin with Basic Auth and adds the admin key; the client only sends the
// X-Requested-With header the server requires. The global fetch is read at
// call time (not App's fetchFn, which stays the audio fetch).

export const ADMIN_API = '/admin/api/feedback'
const ADMIN_HEADER = 'songrush-admin'

// The server's error code, or null when the body is not the expected JSON
// (a proxy's HTML error page, for example).
async function errorCode(response) {
  try {
    const body = await response.json()
    return body !== null && typeof body === 'object' && typeof body.error === 'string' ? body.error : null
  } catch (err) {
    console.warn('admin: unreadable error body', response.status, err)
    return null
  }
}

// Every record, newest first. Resolves to {ok: true, items} or
// {ok: false, error}, where error is 'off' (a 404: no admin access),
// 'network' or 'failed'; never rejects.
export async function loadFeedback() {
  let response
  try {
    response = await globalThis.fetch(`${ADMIN_API}?status=all`, {
      headers: { 'X-Requested-With': ADMIN_HEADER },
    })
  } catch (err) {
    console.warn('admin: list request failed', err)
    return { ok: false, error: 'network' }
  }
  if (response.status === 404) return { ok: false, error: 'off' }
  if (!response.ok) {
    console.warn('admin: list failed', response.status)
    return { ok: false, error: 'failed' }
  }
  try {
    const body = await response.json()
    if (body === null || typeof body !== 'object' || !Array.isArray(body.items)) throw new Error('no items array')
    return { ok: true, items: body.items }
  } catch (err) {
    console.warn('admin: unreadable list', err)
    return { ok: false, error: 'failed' }
  }
}

// One item action: 'send' (payload {title}), 'hide' or 'unhide'. Resolves to
// {ok: true, record} with the updated record, or {ok: false, error}, where
// error is the server's code ('github-disabled', 'already-sent', ...),
// 'network', or 'failed' when the answer carries no code; never rejects.
export async function feedbackAction(id, action, payload = {}) {
  let response
  try {
    response = await globalThis.fetch(`${ADMIN_API}/${encodeURIComponent(id)}/${action}`, {
      method: 'POST',
      headers: { 'X-Requested-With': ADMIN_HEADER, 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
  } catch (err) {
    console.warn('admin: action request failed', action, err)
    return { ok: false, error: 'network' }
  }
  if (!response.ok) {
    const code = await errorCode(response)
    return { ok: false, error: code ?? 'failed' }
  }
  try {
    const record = await response.json()
    if (record === null || typeof record !== 'object' || typeof record.id !== 'string') throw new Error('no record')
    return { ok: true, record }
  } catch (err) {
    console.warn('admin: unreadable action answer', action, err)
    return { ok: false, error: 'failed' }
  }
}
