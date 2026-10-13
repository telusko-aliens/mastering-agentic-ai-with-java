/**
 * Everything the UI knows about the backend, in one file.
 *
 * No component builds a URL or reads a response shape. When the API changes, this is the file
 * that changes, and the components carry on.
 */

const OPERATOR = 'ops@novacart.com'

/** One place that turns a failed response into an error worth reading. */
async function request(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })

  if (!response.ok) {
    const body = await response.text()
    throw new Error(`${options.method || 'GET'} ${path} failed: ${response.status} ${body}`)
  }

  return response.status === 204 ? null : response.json()
}

export const api = {
  operator: OPERATOR,

  /** Ask the copilot. sessionId is null on the first message and comes back in the reply. */
  ask: (message, sessionId) =>
    request('/api/copilot', {
      method: 'POST',
      body: JSON.stringify({ operator: OPERATOR, sessionId, message }),
    }),

  catalog: () => request('/api/catalog'),
  events: () => request('/api/events'),
  incidents: () => request('/api/incidents'),
  approvals: () => request('/api/approvals'),
  traces: () => request('/api/traces'),

  simulate: (kind) =>
    request(`/api/incidents/simulate?kind=${encodeURIComponent(kind)}`, { method: 'POST' }),

  decide: (ticket, approved) =>
    request(`/api/approvals/${ticket}?approved=${approved}&by=manager`, { method: 'POST' }),
}
