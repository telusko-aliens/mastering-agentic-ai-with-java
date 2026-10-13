/**
 * The frame: a top bar and a sidebar, like every operations tool.
 *
 * Counts next to the nav items are the small thing that makes a tool feel real. An operator
 * should be able to tell there is work waiting without opening the tab.
 */
const PAGES = [
  { id: 'incidents', label: 'Incidents' },
  { id: 'copilot', label: 'Copilot' },
  { id: 'approvals', label: 'Approvals' },
  { id: 'traces', label: 'Traces' },
  { id: 'catalog', label: 'Orders' },
]

export default function Shell({ page, onPage, connected, counts, children }) {
  return (
    <div className="app">
      <header className="topbar">
        <span className="mark">NovaCart</span>
        <span className="env">Operations</span>

        <span className="spacer" />

        <span className="status">
          <span className={connected ? 'st run' : 'st bad'}>
            {connected ? 'Connected' : 'Reconnecting'}
          </span>
        </span>

        <span className="who" title="ops@novacart.com">
          OP
        </span>
      </header>

      <nav className="sidebar">
        {PAGES.map((item) => (
          <button
            key={item.id}
            className={page === item.id ? 'on' : ''}
            onClick={() => onPage(item.id)}
          >
            <span>{item.label}</span>
            {counts[item.id] > 0 && <span className="count">{counts[item.id]}</span>}
          </button>
        ))}
      </nav>

      <div className="content">{children}</div>
    </div>
  )
}
