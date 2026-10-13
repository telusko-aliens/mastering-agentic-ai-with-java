import { useEffect, useState } from 'react'

/**
 * The live connection to the backend's event stream.
 *
 * This hook is the reason the dashboard updates when nobody is typing. A shipment is held on the
 * server, an agent investigates, and the browser finds out because of this.
 *
 * @param onEvent optional callback for a caller that wants to react to an event rather than just
 *                display it, for example by refreshing the approvals list.
 */
export function useOpsStream(onEvent) {
  const [events, setEvents] = useState([])
  const [connected, setConnected] = useState(false)

  useEffect(() => {
    // Seed from history, so a browser that opens late is not looking at an empty screen.
    fetch('/api/events')
      .then((response) => response.json())
      .then(setEvents)
      .catch(() => {})

    const source = new EventSource('/api/stream')

    source.addEventListener('hello', () => setConnected(true))

    source.addEventListener('ops', (message) => {
      const event = JSON.parse(message.data)

      // Newest first, and bounded. An operations screen left open all day would otherwise grow
      // until the tab is unusable.
      setEvents((current) => [event, ...current].slice(0, 100))

      if (onEvent) {
        onEvent(event)
      }
    })

    source.onerror = () => setConnected(false)

    // EventSource reconnects on its own, so the only thing to clean up is the connection
    // itself. Skip this and every hot reload leaves another open stream behind.
    return () => source.close()

    // Deliberately empty: the stream is opened once for the life of the page. Including
    // onEvent here would reopen the connection on every render of the parent.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return { events, connected }
}
