import { useEffect, useState } from 'react'
import { api } from '../lib/api'

/**
 * What the agents actually did, from ADK's six callbacks.
 *
 * With one agent this is a luxury. With four agents, a parallel block and another company in the
 * middle, it is the only way to answer "why did it say that".
 *
 * The input token count is the one to read. It dwarfs output because every turn resends the
 * whole conversation.
 */
export default function Traces({ refreshKey }) {
  const [runs, setRuns] = useState([])

  useEffect(() => {
    api.traces().then(setRuns).catch(() => {})
  }, [refreshKey])

  return (
    <>
      <div className="page-head">
        <h1>Traces</h1>
        <span className="sub">last {runs.length} agent runs</span>
      </div>

      <div className="body">
        {runs.length === 0 && <div className="empty">Nothing yet. Ask the copilot something.</div>}

        {runs.map((run) => (
          <div className="section" key={run.id}>
            <h2>
              {new Date(run.startedAt).toLocaleTimeString('en-GB')}
            </h2>
            <p className="note">
              {run.modelCalls} model calls · {run.toolCalls} tool calls ·{' '}
              {run.tokensIn.toLocaleString()} tokens in · {run.tokensOut.toLocaleString()} out
            </p>
            <pre>{run.lines.map(line => '  '.repeat(line.depth) + line.kind.padEnd(13) + line.detail).join('\n')}</pre>
          </div>
        ))}
      </div>
    </>
  )
}
