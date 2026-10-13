import { Fragment, useCallback, useEffect, useState } from 'react'
import { api } from '../lib/api'

/**
 * Money waiting for a person.
 *
 * Watch what happens after Approve. The agent's conversation was paused, possibly an hour ago.
 * Approving resumes it on the server, and the line that appears under the row is what the
 * operator was told, written after the manager clicked.
 *
 * <p>That line is held by App rather than here, because switching tabs unmounts this
 * component and the message would vanish with it.
 */
export default function Approvals({ refreshKey, onDecided, resumed, onResumed }) {
  const [rows, setRows] = useState([])
  const [busy, setBusy] = useState(null)

  const load = useCallback(() => {
    api.approvals().then((data) => setRows(data.all)).catch(() => {})
  }, [])

  useEffect(load, [load, refreshKey])

  async function decide(ticket, approved) {
    setBusy(ticket)
    try {
      const result = await api.decide(ticket, approved)
      if (result.operatorWasTold) {
        onResumed((current) => ({ ...current, [ticket]: result.operatorWasTold }))
      }
      load()
      onDecided?.()
    } finally {
      setBusy(null)
    }
  }

  const pending = rows.filter((row) => row.status === 'PENDING').length

  return (
    <>
      <div className="page-head">
        <h1>Approvals</h1>
        <span className="sub">
          {pending} awaiting a decision · threshold 2,000
        </span>
      </div>

      <div className="body">
        <table>
          <thead>
            <tr>
              <th style={{ width: 90 }}>Ticket</th>
              <th style={{ width: 100 }}>Order</th>
              <th className="num" style={{ width: 90 }}>Amount</th>
              <th>Reason</th>
              <th style={{ width: 150 }}>Status</th>
              <th style={{ width: 150 }} />
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={6}>
                  <div className="empty">
                    Nothing waiting. Ask the copilot to compensate a late order.
                  </div>
                </td>
              </tr>
            )}

            {rows.map((row) => (
              // A fragment, because what the agent said afterwards gets its own full width row.
              // Squeezing a paragraph into the status column makes a two line sentence twelve
              // lines tall, which is how a table stops being readable.
              <Fragment key={row.ticket}>
                <tr>
                  <td className="id">{row.ticket}</td>
                  <td className="id">{row.orderId}</td>
                  <td className="num">{row.amountRupees.toLocaleString('en-IN')}</td>
                  <td className="dim">{row.reason}</td>
                  <td>
                    <span className={statusClass(row.status)}>{label(row.status)}</span>
                    {row.decidedBy && <div className="dim">by {row.decidedBy}</div>}
                  </td>
                  <td>
                    {row.status === 'PENDING' && (
                      <>
                        <button
                          className="btn sm primary"
                          disabled={busy === row.ticket}
                          onClick={() => decide(row.ticket, true)}
                        >
                          Approve
                        </button>{' '}
                        <button
                          className="btn sm"
                          disabled={busy === row.ticket}
                          onClick={() => decide(row.ticket, false)}
                        >
                          Reject
                        </button>
                      </>
                    )}
                  </td>
                </tr>

                {resumed[row.ticket] && (
                  <tr>
                    <td />
                    <td colSpan={5} className="resumed">
                      <span className="label">Agent resumed</span>
                      {resumed[row.ticket]}
                    </td>
                  </tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}

function statusClass(status) {
  if (status === 'APPROVED') return 'st ok'
  if (status === 'REJECTED') return 'st bad'
  return 'st warn'
}

function label(status) {
  return status.charAt(0) + status.slice(1).toLowerCase()
}
