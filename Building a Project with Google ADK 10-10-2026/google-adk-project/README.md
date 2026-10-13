# NovaCart

The capstone for the Google ADK for Java course.

Agents that investigate a problem nobody reported, ask another company for help, and stop to get
a manager's signature before they spend money.

```
   React dashboard            Spring Boot + ADK                Partner Logistics
      :5180          ───────►       :8092          ──A2A──►         :9100
                                      │                        (another company,
                                      ▼                         another process,
                                   H2 sessions                  another repository)
```

This file is the reference you keep open
while the code is running.

---

## Run it

Three terminals, in this order. Each needs `GOOGLE_API_KEY` in its environment.

```bat
:: 1. the partner company, first
cd partner-warehouse
mvn compile exec:java

:: 2. NovaCart
cd novacart-backend
mvn spring-boot:run

:: 3. the dashboard
cd novacart-ui
npm install
npm run dev
```

Open <http://localhost:5180>.

NovaCart starts even if the partner is down. A partner outage degrades one feature, it does not
stop the application booting.

No Gemini key? Point it at a local model instead:

```
mvn spring-boot:run -Dspring-boot.run.arguments="--novacart.model.provider=ollama --novacart.model.name=llama3.2"
```

---

## What to click, in order

| # | Page | Do this | What it proves |
|---|---|---|---|
| 1 | Incidents | **Simulate event → Stock out at one warehouse** | An investigation runs with nobody asking. The plan moves stock from Pune. |
| 2 | Incidents | **Simulate event → Stock out everywhere** | Now there is nothing to move, so the agents ask Partner Logistics over A2A. |
| 3 | Orders | look at the stock table | The numbers in the plan are real. |
| 4 | Copilot | *"Order NC-98377 is 12 days late. Work out the compensation and issue it."* | The agent stops and asks for a manager. |
| 5 | Approvals | **Approve** | The conversation resumes on its own and the operator is told. |
| 6 | Traces | read the last run | Every agent, model call and tool call, with tokens. |
| 7 | Copilot | **Injection attempt** | Blocked before the model is called. Zero tokens. |

Steps 1 and 2 are the same event with different stock. The system reaches outside the company
only in the second. Run both, in that order, or the partner console stays empty and it looks
broken when it is not.

---

## The agents

```
copilot                       talks to an operator, and the only one that can spend money
  ├── investigation           a pipeline, run on an event or on request
  │     ├── triage            custom BaseAgent: no model, deterministic, free
  │     ├── parallel          order-check | stock-check | impact-check
  │     ├── partner-check     A2A, and only when we are short
  │     └── resolution-planner
  ├── replacement-finder      a loop: propose, test, try again
  └── partner-warehouse       RemoteA2AAgent, another company
```

`triage` has no model on purpose. Severity decides whether a human is paged, and that must not
be arguable.

---

## Where every ADK topic landed

| Topic | Read |
|---|---|
| `LlmAgent`, instructions, config | `agent/AgentConfig.java` |
| Model providers behind one interface | `config/AdkConfig.java` |
| Function tools, reads and writes split by risk | `tools/` |
| Sessions on a database | `session/JdbcSessionService.java` |
| `SequentialAgent`, `ParallelAgent` | `AgentConfig.investigation()` |
| `LoopAgent` + `ExitLoopTool` | `AgentConfig.replacementFinder()` |
| Custom `BaseAgent` | `agent/TriageAgent.java` |
| `AgentTool` | `AgentConfig.copilot()` |
| `LongRunningFunctionTool` | `ResolutionTools.issueCompensation` |
| A2A | `a2a/PartnerWarehouse.java`, `partner-warehouse/` |
| The six callbacks | `ops/TraceStore.java` |
| Guardrails, in three hooks | `ops/Guardrails.java` |
| Your own API, replacing `AdkWebServer` | `web/OpsController.java` |

---

## Human in the loop

```
1. the planner works out 3,600 rupees from the compensation ladder
2. issueCompensation writes a ticket, returns PENDING_APPROVAL, and the turn ENDS
3. OpsController records the session against the ticket
4. ... a manager opens the Approvals tab. Minutes, or tomorrow. Nothing is held open.
5. OpsController finds the unanswered issueCompensation call in the stored session
6. and sends back a FunctionResponse with THAT SAME id
7. the agent carries on and tells the operator
```

Step 6 is the trick. Plain text, or a new id, leaves a tool call nobody ever resolved.

Step 5 only works because sessions are in a database. In memory, a manager taking a lunch break
would end the conversation.

Three spending limits, different on purpose:

```
under 2,000        the agent settles it
2,000 to 25,000    a manager decides
over 25,000        this system does not do it, approved or not
```

---

## The API

| Method | Path | Does |
|---|---|---|
| POST | `/api/copilot` | ask the copilot |
| GET | `/api/stream` | live events, server-sent |
| GET | `/api/incidents` | investigations the system ran on its own |
| POST | `/api/incidents/simulate?kind=` | fire a realistic incident |
| GET | `/api/approvals` | money waiting for a manager |
| POST | `/api/approvals/{ticket}?approved=` | decide, and resume the conversation |
| GET | `/api/traces` | what the agents did |
| GET | `/api/catalog` | orders, products and stock |

---

## Things that will go wrong

| Symptom | Cause |
|---|---|
| `Address already in use` | An earlier run still holds the port. `netstat -ano \| findstr :9100`, then `taskkill /PID <pid> /F` |
| UI loads but says not connected | `server.port` and the Vite proxy target disagree |
| Nothing in the partner console | You ran the one warehouse scenario. It is meant to be solved internally |
| `GOOGLE_API_KEY is not set` | Not set in that terminal, or missing from the IntelliJ run config |
| Partner questions always fail | `partner-warehouse` is not running |
| An investigation never finishes | A remote A2A agent inside a `ParallelAgent`. Give it its own sequential step |
| Every incident after the first is stuck | Same cause. Incidents run on one thread, so one stuck run blocks the rest. Hence the timeout |
| `a2aClient cannot be null` | `RemoteA2AAgent` needs a built `Client`, not just the card |
| A2A answers once then hangs | The partner returned a message, not a task with `state: completed` |
| Approval never resumes the agent | The tool is behind an `AgentTool`, so its call id is not in this session |
| The reply is `FunctionCall{...}` | Tool events are not being filtered out of the answer |
| Agent forgets between messages | The browser is not sending `sessionId` back |
| Agent replies with an empty string | `maxOutputTokens` too low. Thinking tokens count against it |
| `Unsupported type: ToolContext` | `-parameters` missing, or the parameter is not named `toolContext` |
| Two Spring versions on the classpath | `google-adk-dev` was added. It is Spring Boot 4 |

---

## Not done here, and said out loud

A teaching project. Before it faces real operators:

- **Authentication.** `operator` comes from the request body. It must come from a verified token.
- **A role check on the approvals endpoint.** Anyone can approve money right now.
- **A real database.** H2 file mode is one process. Postgres for sessions, a table for approvals.
- **A real event bus.** In memory means events are lost on restart, and two instances would both
  investigate the same shortage.
- **Artifacts outside the JVM.** `GcsArtifactService`, or a saved plan is lost on restart.
- **Rate limiting and a cost ceiling.** Read the token counts on the Traces page.
- **Compaction.** A long operator conversation resends its whole history on every turn.

---

## Layout

```
google-adk-project/
  partner-warehouse/              another company. No Spring, no NovaCart imports.
        WarehouseServer.java      its agent, its tools, its data
        A2AServer.java            the A2A protocol on the JDK's HTTP server

  novacart-backend/
        domain/Catalog.java           orders, products, stock
        tools/OrderTools.java         reads: safe, no approval
        tools/InventoryTools.java     reads: our warehouses only
        tools/ResolutionTools.java    writes: guarded, and one waits for a human
        agent/TriageAgent.java        custom BaseAgent, no model
        agent/AgentConfig.java        every agent in the system
        a2a/PartnerWarehouse.java     the connection to another company
        ops/EventBus.java             what happened, and who wants to know
        ops/IncidentService.java      the part that makes it agentic
        ops/ApprovalQueue.java        money waiting for a person
        ops/Guardrails.java           three guardrails, three hooks
        ops/TraceStore.java           the six callbacks
        session/JdbcSessionService.java
        web/OpsController.java        the API that replaces AdkWebServer

  novacart-ui/
        lib/api.js                every URL, in one file
        lib/useOpsStream.js       the live connection
        components/               Shell, Incidents, Copilot, Approvals, Traces, Catalog
```
