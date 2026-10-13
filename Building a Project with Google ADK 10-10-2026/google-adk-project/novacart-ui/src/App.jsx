import { useCallback, useMemo, useState } from 'react'
import Shell from './components/Shell'
import Incidents from './components/Incidents'
import Copilot from './components/Copilot'
import Approvals from './components/Approvals'
import Traces from './components/Traces'
import Catalog from './components/Catalog'
import { useOpsStream } from './lib/useOpsStream'

/**
 * NovaCart operations.
 *
 * The app owns three things: which page is showing, the live event stream, and the
 * state that has to outlive a page.
 *
 * The stream lives here rather than in the page that displays it because several pages
 * care about it. An approval raised during a chat should make the Approvals tab reload
 * even though nobody has opened it, and the sidebar count should go up.
 */
export default function App() {
  const [page, setPage] = useState('incidents')

  /**
   * The copilot conversation, and what each approval told the operator afterwards.
   *
   * Both live up here rather than inside their pages, and that is not a style choice.
   * Only the open page is mounted, so anything a page holds in its own state is thrown
   * away the moment you click another tab. Keeping it here is what lets an operator ask
   * the copilot something, go to Approvals, approve it, and come back to a conversation
   * that is still there.
   *
   * The session id matters most. Lose it and the next question starts a new
   * conversation, so the agent forgets everything that was just said to it.
   */
  const [chat, setChat] = useState({ messages: [], sessionId: null })
  const [resumed, setResumed] = useState({})

  /**
   * Bumped when something happened that the data-loading pages should notice.
   *
   * A counter rather than the event, because the pages do not care what happened, only
   * that something did. Passing the event would re-render every page on every heartbeat.
   */
  const [refreshKey, setRefreshKey] = useState(0)
  const bump = useCallback(() => setRefreshKey((current) => current + 1), [])

  const onEvent = useCallback(
    (event) => {
      const interesting =
        event.kind === 'APPROVAL_REQUIRED' ||
        event.kind === 'APPROVAL_DECIDED' ||
        event.kind === 'INVESTIGATION_FINISHED' ||
        event.kind === 'STOCK_TRANSFERRED'

      if (interesting) {
        bump()
      }
    },
    [bump],
  )

  const { events, connected } = useOpsStream(onEvent)

  // Sidebar counts: how many approvals are still open, derived from the feed so no extra
  // request is needed. Raised minus decided is close enough for a badge.
  const counts = useMemo(() => {
    const raised = events.filter((event) => event.kind === 'APPROVAL_REQUIRED').length
    const decided = events.filter((event) => event.kind === 'APPROVAL_DECIDED').length
    return { approvals: Math.max(0, raised - decided) }
  }, [events])

  return (
    <Shell page={page} onPage={setPage} connected={connected} counts={counts}>
      {page === 'incidents' && <Incidents events={events} />}

      {page === 'copilot' && (
        <Copilot chat={chat} onChat={setChat} onApprovalRaised={bump} />
      )}

      {page === 'approvals' && (
        <Approvals
          refreshKey={refreshKey}
          onDecided={bump}
          resumed={resumed}
          onResumed={setResumed}
        />
      )}

      {page === 'traces' && <Traces refreshKey={refreshKey} />}
      {page === 'catalog' && <Catalog refreshKey={refreshKey} />}
    </Shell>
  )
}
