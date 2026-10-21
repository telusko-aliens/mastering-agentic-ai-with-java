/*
 * ============================================================================
 * FILE: app.js
 * TEACH ORDER: Step 15 of 15  (optional - with index.html)
 *
 * WHAT THIS FILE DOES:
 *   All the behaviour of the web page, in plain JavaScript (no React/Angular).
 *   Every button calls one of our REST APIs with fetch(), then puts the JSON
 *   result onto the page.
 *
 * MAP OF THIS FILE:
 *   helpers     -> $ (find element), esc (safe text), get/post (call APIs)
 *   tabs        -> switch between the 4 panels
 *   documents   -> Ingest / Force re-ingest buttons
 *   chat        -> Ask button, sample questions
 *   tracing     -> load the tracing status
 *   evaluation  -> Run, Compare, and show saved results
 *   langsmith   -> NEW: links to the experiments in LangSmith's dashboard
 *   start       -> what runs when the page first opens
 * ============================================================================
 */

// Shortcut: $('ask') instead of document.getElementById('ask').
const $ = (id) => document.getElementById(id);

// esc = "escape". Makes text safe to put into HTML.
// If an answer contained <script>, it would be shown as text, not run.
// Trick: put the text into a div as plain text, then read it back as HTML.
// (?? '' means: if s is null/undefined, use an empty string.)
const esc = (s) => { const d = document.createElement('div'); d.textContent = s ?? ''; return d.innerHTML; };

// ------------------------------------------------------------------ tabs

// For every tab button: when clicked, turn everything "off",
// then turn ON this button and the panel whose id matches data-tab.
// (CSS shows only panels with class "on".)
for (const tab of document.querySelectorAll('nav button')) {
  tab.onclick = () => {
    document.querySelectorAll('nav button').forEach((b) => b.classList.remove('on'));
    document.querySelectorAll('.panel').forEach((p) => p.classList.remove('on'));
    tab.classList.add('on');
    $(tab.dataset.tab).classList.add('on');
  };
}

// GET a URL and return the JSON. Throws an error if the status is not 2xx.
// async/await = "wait for the server's reply without freezing the page".
async function get(url) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(url + ' returned ' + r.status);
  return r.json();
}

// POST to a URL. If a body is given, send it as JSON; otherwise send no body.
async function post(url, body) {
  const r = await fetch(url, body
    ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
    : { method: 'POST' });
  if (!r.ok) throw new Error(url + ' returned ' + r.status);
  return r.json();
}

// ------------------------------------------------------------- documents

// Loads the policy text and the "ready / not ingested" badge.
async function loadDocuments() {
  try {
    // Promise.all = call both APIs at the same time and wait for both.
    const [doc, health] = await Promise.all([
      get('/api/rag/documents'),
      get('/api/rag/health'),
    ]);
    $('docText').textContent = doc.text;
    $('docMeta').textContent = doc.source + '  ·  ' + doc.chunksStored + ' chunks stored';
    $('ingestStatus').textContent = health.status;
    // Green badge if chunks are stored, orange if not.
    $('ingestStatus').className = 'badge ' + (health.chunksStored > 0 ? 'ok' : 'off');
  } catch (e) {
    $('docText').textContent = 'Could not reach the backend: ' + e.message;
  }
}

// Runs ingestion. force=false for "Ingest", force=true for "Force re-ingest".
async function runIngest(force) {
  const buttons = [$('ingest'), $('reingest')];
  // Disable both buttons while working, so nobody double-clicks.
  buttons.forEach((b) => (b.disabled = true));
  $('ingestResult').textContent = 'working...';
  try {
    const result = await post('/api/rag/ingest?force=' + force);
    // Pretty-print the JSON (2 spaces indent).
    $('ingestResult').textContent = JSON.stringify(result, null, 2);
    // Refresh the badge and chunk count.
    await loadDocuments();
  } catch (e) {
    $('ingestResult').textContent = 'failed: ' + e.message;
  } finally {
    // Always turn the buttons back on, success or failure.
    buttons.forEach((b) => (b.disabled = false));
  }
}

// Wire up the two buttons.
$('ingest').onclick = () => runIngest(false);
$('reingest').onclick = () => runIngest(true);

// ------------------------------------------------------------------ chat

// Sample question buttons: copy their text into the box, then "click" Ask.
for (const b of document.querySelectorAll('.sample')) {
  b.onclick = () => { $('question').value = b.textContent.trim(); $('ask').click(); };
}

// The Ask button.
$('ask').onclick = async () => {
  // Don't send an empty question.
  if (!$('question').value.trim()) {
    $('answer').textContent = 'Type a question first.';
    return;
  }
  // Reset the screen and show "thinking...".
  $('ask').disabled = true;
  $('answer').textContent = 'thinking...';
  $('answerMeta').textContent = '';
  $('chunks').innerHTML = '';
  try {
    // Call POST /api/rag/ask with {question, topK}. Number() turns "3" into 3.
    const d = await post('/api/rag/ask', {
      question: $('question').value,
      topK: Number($('topK').value),
    });

    // Show the answer text.
    $('answer').textContent = d.answer;

    // The info line under the answer: refused tag, topK, chunk count, time, model, sources.
    $('answerMeta').innerHTML =
      (d.refused ? '<span class="fail">refused</span>  ·  ' : '') +
      'topK ' + d.topK + '  ·  ' + d.chunks.length + ' chunks  ·  ' +
      d.millis + ' ms  ·  ' + esc(d.model) +
      '  ·  sources: ' + (d.sources.length ? esc(d.sources.join(', ')) : 'none');

    // Show each chunk as a collapsible box: "Chunk 1 (512 characters)".
    // If there are no chunks, explain why (nothing passed the 0.35 threshold).
    $('chunks').innerHTML = d.chunks.length
      ? d.chunks.map((c, i) =>
          '<details><summary>Chunk ' + (i + 1) + '  (' + c.length + ' characters)</summary>' +
          '<pre>' + esc(c) + '</pre></details>').join('')
      : '<p class="note">Nothing cleared the similarity threshold, so no model was called.</p>';
  } catch (e) {
    $('answer').textContent = 'failed: ' + e.message;
  } finally {
    $('ask').disabled = false;
  }
};

// --------------------------------------------------------------- tracing

// Fills the Tracing tab from GET /api/tracing/status.
async function loadTracing() {
  try {
    const t = await get('/api/tracing/status');
    $('traceBadge').textContent = t.enabled ? 'tracing on  ·  ' + t.project : 'tracing off';
    $('traceBadge').className = 'badge ' + (t.enabled ? 'ok' : 'off');
    $('traceNote').textContent = t.note;
    $('traceLink').href = t.dashboardUrl;
    // One row per span: name on the left, what it does + its tags on the right.
    $('spans').innerHTML = t.spans.map((s) =>
      '<div class="stage"><span class="name">' + esc(s.name) + '</span>' +
      '<span>' + esc(s.what) + '<div class="tags">' + esc(s.tags) + '</div></span></div>').join('');
  } catch (e) {
    $('traceNote').textContent = 'Could not read tracing status: ' + e.message;
  }
}

// ------------------------------------------------------------ evaluation

// Builds the 4 summary cards from one Experiment (report card).
// toFixed(2) = show 2 decimals, e.g. 0.92
function cards(e) {
  const card = (label, value) =>
    '<div class="card"><div class="label">' + label + '</div><div class="value">' + value + '</div></div>';
  return '<div class="cards">' +
    card('Passed', e.passed + ' / ' + e.cases) +
    card('Correctness', e.correctness.toFixed(2)) +
    card('Faithfulness', e.faithfulness.toFixed(2)) +
    card('Avg latency', e.averageMillis + ' ms') +
    '</div>';
}

// Builds the "Every case" table. For each case, 2 rows:
//   row 1: id, category, PASS/FAIL, 3 scores, ms, note
//   row 2: the question (Q) and the app's actual answer (A)
function caseTable(e) {
  return '<table><thead><tr>' +
    '<th>Case</th><th>Category</th><th>Result</th>' +
    '<th class="num">Correct</th><th class="num">Faithful</th><th class="num">Relevant</th>' +
    '<th class="num">ms</th><th>Note</th></tr></thead><tbody>' +
    e.details.map((c) => {
      const s = c.scores;
      return '<tr>' +
        '<td class="mono">' + esc(c.id) + '</td>' +
        '<td class="muted">' + esc(c.category) + '</td>' +
        '<td class="' + (s.passed ? 'pass' : 'fail') + '">' + (s.passed ? 'PASS' : 'FAIL') + '</td>' +
        '<td class="num">' + s.correctness.toFixed(1) + '</td>' +
        '<td class="num">' + s.faithfulness.toFixed(1) + '</td>' +
        '<td class="num">' + s.relevance.toFixed(1) + '</td>' +
        '<td class="num">' + s.millis + '</td>' +
        '<td class="muted">' + esc(s.note) + '</td></tr>' +
        '<tr><td></td><td colspan="7" class="muted" style="font-size:12.5px">' +
        '<b>Q:</b> ' + esc(c.question) + '<br><b>A:</b> ' + esc(c.actual) + '</td></tr>';
    }).join('') + '</tbody></table>';
}

// Builds the topK 2 vs topK 5 table, plus the verdict sentence from the server.
// a = topK 2 report card, b = topK 5 report card.
function comparisonTable(a, b, verdict) {
  const row = (label, left, right) =>
    '<tr><td>' + label + '</td><td class="num">' + left + '</td><td class="num">' + right + '</td></tr>';

  return '<h2>topK 2 against topK 5</h2>' +
    '<table><thead><tr><th>Metric</th><th class="num">topK 2</th><th class="num">topK 5</th>' +
    '</tr></thead><tbody>' +
    row('Passing cases', a.passed + ' / ' + a.cases, b.passed + ' / ' + b.cases) +
    row('Correctness', a.correctness.toFixed(2), b.correctness.toFixed(2)) +
    row('Faithfulness', a.faithfulness.toFixed(2), b.faithfulness.toFixed(2)) +
    row('Retrieval relevance', a.relevance.toFixed(2), b.relevance.toFixed(2)) +
    row('Refusal accuracy', a.refusalAccuracy.toFixed(2), b.refusalAccuracy.toFixed(2)) +
    row('Average latency', a.averageMillis + ' ms', b.averageMillis + ' ms') +
    '</tbody></table>' +
    '<p class="note" style="margin-top:10px"><b>' + esc(verdict) + '</b></p>';
}

// The Run button: run all 12 questions at the chosen topK.
$('runEval').onclick = async () => {
  const topK = Number($('evalTopK').value);
  // Disable BOTH buttons (a = b = true sets both at once).
  $('runEval').disabled = $('compare').disabled = true;
  $('evalStatus').textContent = 'running ' + topK + '...';
  $('comparison').innerHTML = '';
  try {
    const e = await post('/api/evaluation/run?topK=' + topK);
    $('summary').innerHTML = '<h2>Run at topK ' + e.topK + '</h2>' + cards(e);
    $('caseTable').innerHTML = caseTable(e);
    $('evalStatus').textContent = 'done';
    loadLangSmithLinks();   // NEW: show the new experiment link
  } catch (err) {
    $('evalStatus').textContent = 'failed: ' + err.message;
  } finally {
    $('runEval').disabled = $('compare').disabled = false;
  }
};

// The Compare button: run at topK 2 and topK 5 (takes ~2 minutes).
$('compare').onclick = async () => {
  $('runEval').disabled = $('compare').disabled = true;
  $('evalStatus').textContent = 'running both, this takes a couple of minutes...';
  try {
    const d = await post('/api/evaluation/compare');
    $('summary').innerHTML = '';
    $('comparison').innerHTML = comparisonTable(d.experimentA, d.experimentB, d.verdict);
    // Below the comparison, show every case of the topK 5 run.
    $('caseTable').innerHTML = '<h2 style="margin-top:0">Every case at topK 5</h2>' +
      caseTable(d.experimentB);
    $('evalStatus').textContent = 'done';
    loadLangSmithLinks();   // NEW: show both experiment links (topK 2 and 5)
  } catch (err) {
    $('evalStatus').textContent = 'failed: ' + err.message;
  } finally {
    $('runEval').disabled = $('compare').disabled = false;
  }
};

/*
 * The server remembers the last run of each topK. Show it straight away
 * instead of an empty panel, so reloading the page does not cost another
 * minute (and more API money) of judging.
 * CLASS TIP: run the dataset once before class starts; it will already be on screen.
 */
async function loadLastResults() {
  try {
    const all = await get('/api/evaluation/results');
    const keys = Object.keys(all);
    // Nothing run yet -> leave the panel empty.
    if (!keys.length) return;

    // Prefer the run that matches the selected topK; otherwise show the latest one.
    const wanted = $('evalTopK').value;
    const e = all[keys.includes(wanted) ? wanted : keys[keys.length - 1]];

    $('summary').innerHTML = '<h2>Last run at topK ' + e.topK + '</h2>' + cards(e);
    $('caseTable').innerHTML = caseTable(e);
    $('evalStatus').textContent = 'showing the last run. Press Run for a fresh one';
  } catch (err) {
    // Nothing has been run yet. The empty panel is the correct thing to show.
  }
}

// ------------------------------------------------------------- langsmith

/*
 * NEW: asks GET /api/evaluation/langsmith and shows one link per topK,
 * e.g. "LangSmith experiments:  topK 2  ·  topK 5". Each opens that
 * experiment in LangSmith's compare dashboard.
 */
async function loadLangSmithLinks() {
  try {
    const ls = await get('/api/evaluation/langsmith');
    if (!ls.enabled) {
      $('langsmithLinks').textContent =
        'LangSmith upload is off. Set LANGSMITH_API_KEY (a Personal Access Token) and restart.';
      return;
    }
    const keys = Object.keys(ls.experiments);
    $('langsmithLinks').innerHTML = keys.length
      ? 'LangSmith experiments:  ' + keys.map((k) =>
          '<a href="' + esc(ls.experiments[k]) + '" target="_blank" rel="noreferrer">topK ' +
          esc(k) + '</a>').join('  ·  ')
      : 'Each run will also be uploaded to LangSmith as an experiment.';
  } catch (e) {
    $('langsmithLinks').textContent = '';
  }
}

// ------------------------------------------------------------------ start

// These three run once, as soon as the page opens.
loadDocuments();
loadTracing();
loadLastResults();
loadLangSmithLinks();   // NEW
