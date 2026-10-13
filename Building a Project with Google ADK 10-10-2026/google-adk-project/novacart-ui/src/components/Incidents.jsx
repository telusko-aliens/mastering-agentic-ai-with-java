import { useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../lib/api'

/**
 * The incident queue: list on the left, the selected one on the right.
 *
 * Master and detail rather than a column of cards, because that is how an operator actually
 * works: scan the queue, open one, read it, move on. Cards make you scroll past things you have
 * already dealt with.
 *
 * Nothing on this page was asked for by anybody. Each incident was started by an event.
 */
export default function Incidents({ events }) {
  const [incidents, setIncidents] = useState([])
  const [selected, setSelected] = useState(null)
  const [menuOpen, setMenuOpen] = useState(false)
  const menu = useRef(null)

  useEffect(() => {
    api.incidents().then(setIncidents).catch(() => {})
  }, [events.length])

  useEffect(() => {
    function away(event) {
      if (menu.current && !menu.current.contains(event.target)) {
        setMenuOpen(false)
      }
    }
    document.addEventListener('mousedown', away)
    return () => document.removeEventListener('mousedown', away)
  }, [])

  const running = useMemo(
    () =>
      events.filter(
        (event) =>
          event.kind === 'INVESTIGATION_STARTED' &&
          !events.some(
            (later) =>
              later.kind === 'INVESTIGATION_FINISHED' &&
              later.details?.incidentId === event.details?.incidentId,
          ),
      ),
    [events],
  )

  // Falls back to the newest rather than selecting it in an effect, so a new incident
  // arriving never yanks the operator off what they were reading.
  const open = incidents.find((incident) => incident.id === selected) ?? incidents[0]

  async function fire(kind) {
    setMenuOpen(false)
    await api.simulate(kind)
  }

  return (
    <>
      <div className="page-head">
        <h1>Incidents</h1>
        <span className="sub">
          {incidents.length} investigated
          {running.length > 0 && `, ${running.length} running`}
        </span>

        <span className="spacer" />

        <div className="menu" ref={menu}>
          <button className="btn" onClick={() => setMenuOpen((on) => !on)}>
            Simulate event
          </button>

          {menuOpen && (
            <div className="items">
              <button onClick={() => fire('SHIPMENT_DELAYED')}>Shipment delayed</button>
              <button onClick={() => fire('STOCK_SHORTAGE')}>Stock out at one warehouse</button>
              <button onClick={() => fire('STOCK_CRISIS')}>Stock out everywhere</button>
              <button onClick={() => fire('CUSTOMER_COMPLAINT')}>Customer complaint</button>
              <p className="note">
                Publishes a real event. The investigation runs on the server.
              </p>
            </div>
          )}
        </div>
      </div>

      <div className="split">
        <div className="list">
          <div className="list-head">
            <span>Queue</span>
            {running.length > 0 && <span className="st run">investigating</span>}
          </div>

          {incidents.length === 0 && (
            <div className="empty">
              No incidents yet.
              <br />
              Use Simulate event.
            </div>
          )}

          {incidents.map((incident) => (
            <button
              key={incident.id}
              className={`list-row ${incident.id === open?.id ? 'on' : ''}`}
              onClick={() => setSelected(incident.id)}
            >
              <span className="top">
                <span className="id">{incident.id}</span>
                <span className={incident.status === 'PLANNED' ? 'st ok' : 'st bad'}>
                  {incident.status === 'PLANNED' ? 'Planned' : 'Failed'}
                </span>
                <span className="when">{Math.round(incident.tookMillis / 1000)}s</span>
              </span>
              <span className="line">{incident.trigger}</span>
            </button>
          ))}
        </div>

        <div className="detail">
          {!open && <div className="empty">Select an incident.</div>}

          {open && (
            <>
              <div className="section">
                <h2>{open.id}</h2>
                <dl className="kv" style={{ marginTop: 10 }}>
                  <dt>Trigger</dt>
                  <dd>{open.trigger}</dd>

                  <dt>Started</dt>
                  <dd>{new Date(open.startedAt).toLocaleString('en-GB')}</dd>

                  <dt>Duration</dt>
                  <dd>{(open.tookMillis / 1000).toFixed(1)} seconds</dd>

                  <dt>Status</dt>
                  <dd>
                    <span className={open.status === 'PLANNED' ? 'st ok' : 'st bad'}>
                      {open.status}
                    </span>
                  </dd>
                </dl>
              </div>

              <div className="section">
                <h2>Resolution</h2>
                <p className="note">Written by the planner from four sets of findings.</p>
                <pre>{open.plan || open.summary}</pre>
              </div>
            </>
          )}

          <div className="section">
            <h2>Activity</h2>
            <p className="note">Every event on the bus, newest first.</p>

            {events.length === 0 && <div className="empty">Nothing yet.</div>}

            {events.slice(0, 18).map((event) => (
              <div className="feed-row" key={event.id}>
                <span className="t">{new Date(event.at).toLocaleTimeString('en-GB')}</span>
                <span className="k">{event.kind.replace(/_/g, ' ')}</span>
                <span>{event.summary}</span>
              </div>
            ))}
          </div>
        </div>
      </div>
    </>
  )
}
