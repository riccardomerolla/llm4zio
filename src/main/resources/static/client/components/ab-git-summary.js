import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

/**
 * ab-git-summary — inline git change summary with full diff viewer
 *
 * Attributes:
 *   status-url   — API endpoint for git status JSON
 *   diff-url     — API endpoint for git diffstat JSON
 *   panel-target — panel-id to open for diff view (default: context-panel)
 *
 * When expanded shows a two-panel diff viewer:
 *   - Left sidebar: file tree with per-file +/- stats
 *   - Right panel: unified diffs with green/red highlighting and line numbers
 */
class AbGitSummary extends LitElement {
  static properties = {
    'status-url':     { type: String },
    'diff-url':       { type: String },
    'panel-target':   { type: String },
    _loading:         { type: Boolean, state: true },
    _files:           { type: Array,   state: true },
    _insertions:      { type: Number,  state: true },
    _deletions:       { type: Number,  state: true },
    _expanded:        { type: Boolean, state: true },
    _diffsLoaded:     { type: Boolean, state: true },
    _diffsLoading:    { type: Boolean, state: true },
    _activePath:      { type: String,  state: true },
  };

  constructor() {
    super();
    this['status-url']   = '';
    this['diff-url']     = '';
    this['panel-target'] = 'context-panel';
    this._loading        = true;
    this._files          = [];
    this._insertions     = 0;
    this._deletions      = 0;
    this._expanded       = false;
    this._diffsLoaded    = false;
    this._diffsLoading   = false;
    this._activePath     = '';
    /** @type {Map<string, string>} path to sanitised diff HTML */
    this._diffHtmlCache  = new Map();
  }

  createRenderRoot() { return this; }

  connectedCallback() {
    super.connectedCallback();
    this._load();
  }

  get statusUrl()   { return this['status-url']   || ''; }
  get diffUrl()     { return this['diff-url']      || ''; }
  get panelTarget() { return this['panel-target']  || 'context-panel'; }

  // ── Data loading ────────────────────────────────────────────────

  async _load() {
    if (!this.statusUrl || !this.diffUrl) return;
    this._loading = true;
    try {
      const [status, diffStat] = await Promise.all([
        this._fetchJson(this.statusUrl),
        this._fetchJson(this.diffUrl),
      ]);
      if (!status && !diffStat) { this._loading = false; return; }
      this._buildFileList(status, diffStat);
    } catch (err) {
      console.warn('ab-git-summary: failed to load git data:', err.message);
    }
    this._loading = false;
  }

  _buildFileList(status, diffStat) {
    const statsByPath = new Map((diffStat?.files || []).map((f) => [f.path, f]));
    const allEntries = [
      ...(status?.staged    || []).map((e) => ({ path: e.path, code: this._statusCode(e.status), group: 'staged'    })),
      ...(status?.unstaged  || []).map((e) => ({ path: e.path, code: this._statusCode(e.status), group: 'unstaged'  })),
      ...(status?.untracked || []).map((p) => ({ path: p,      code: 'A',                        group: 'untracked' })),
    ];
    if (allEntries.length === 0 && diffStat?.files?.length > 0) {
      for (const f of diffStat.files) {
        const code = f.deletions > 0 && f.additions > 0 ? 'M' : f.additions > 0 ? 'A' : 'D';
        allEntries.push({ path: f.path, code, group: 'committed' });
      }
    }
    const seen = new Map();
    for (const entry of allEntries) { if (!seen.has(entry.path)) seen.set(entry.path, entry); }
    const files = [];
    let totalIns = 0, totalDel = 0;
    for (const entry of seen.values()) {
      const stat = statsByPath.get(entry.path);
      const additions = Number(stat?.additions || 0);
      const deletions = Number(stat?.deletions || 0);
      totalIns += additions;
      totalDel += deletions;
      files.push({ ...entry, additions, deletions });
    }
    this._files      = files;
    this._insertions = totalIns;
    this._deletions  = totalDel;
  }

  /** Load every file diff in parallel and cache the rendered HTML. */
  async _loadAllDiffs() {
    if (this._diffsLoaded || this._diffsLoading) return;
    this._diffsLoading = true;
    await Promise.all(this._files.map(async (file) => {
      const safePath = this._safeFilePath(file.path);
      if (!safePath) return;
      try {
        const url  = this._fileDiffUrl(safePath);
        const resp = await fetch(url);
        if (resp.ok) {
          const raw = await resp.text();
          this._diffHtmlCache.set(file.path, this._buildDiffHtml(file.path, raw));
        }
      } catch (err) {
        console.warn('ab-git-summary: diff fetch failed for', file.path, err.message);
      }
    }));
    this._diffsLoaded  = true;
    this._diffsLoading = false;
    this._populateDiffSlots();
  }

  // ── URL helpers ─────────────────────────────────────────────────

  _fileDiffUrl(filePath) {
    try {
      const base = new URL(this.diffUrl, window.location.origin);
      base.pathname = base.pathname.replace(/\/$/, '') + '/' + encodeURIComponent(filePath);
      return base.toString();
    } catch { return `${this.diffUrl}/${encodeURIComponent(filePath)}`; }
  }

  // ── Interactions ────────────────────────────────────────────────

  _handleHeaderClick() {
    if (this._files.length > 0) {
      this._expanded = !this._expanded;
      if (this._expanded && !this._diffsLoaded) this._loadAllDiffs();
    }
  }

  _scrollToFile(path) {
    this._activePath = path;
    const id  = this._diffSlotId(path);
    const el  = this.querySelector('#' + CSS.escape(id));
    const scr = this.querySelector('[data-role="diff-scroll"]');
    if (el && scr) scr.scrollTo({ top: el.offsetTop - scr.offsetTop, behavior: 'smooth' });
  }

  // ── DOM helpers ─────────────────────────────────────────────────

  _diffSlotId(path) { return 'gds-' + path.replace(/[^a-zA-Z0-9]/g, '-'); }

  /**
   * Fill cached diff HTML into placeholder slots after Lit render.
   * All values in _diffHtmlCache were built by _buildDiffHtml which
   * sanitises every interpolated value through _escHtml, so the
   * content is safe to assign to innerHTML.
   */
  _populateDiffSlots() {
    for (const [path, safeHtml] of this._diffHtmlCache) {
      const slot = this.querySelector('#' + CSS.escape(this._diffSlotId(path)) + ' [data-role="diff-body"]');
      if (slot && !slot._filled) {
        slot.innerHTML = safeHtml;
        slot._filled = true;
      }
    }
  }

  updated() { if (this._expanded && this._diffsLoaded) this._populateDiffSlots(); }

  // ── Status helpers ──────────────────────────────────────────────

  _statusCode(status) {
    const s = String(status || '').toLowerCase();
    if (s === 'modified') return 'M'; if (s === 'added') return 'A';
    if (s === 'deleted')  return 'D'; if (s === 'renamed') return 'R';
    return '?';
  }
  _statusColor(code) {
    if (code === 'M') return 'text-blue-300';  if (code === 'A') return 'text-emerald-300';
    if (code === 'D') return 'text-rose-300';  if (code === 'R') return 'text-amber-300';
    return 'text-gray-400';
  }
  _statusBg(code) {
    if (code === 'M') return 'bg-blue-400/15 border-blue-400/30 text-blue-200';
    if (code === 'A') return 'bg-emerald-400/15 border-emerald-400/30 text-emerald-200';
    if (code === 'D') return 'bg-rose-400/15 border-rose-400/30 text-rose-200';
    if (code === 'R') return 'bg-amber-400/15 border-amber-400/30 text-amber-200';
    return 'bg-gray-400/15 border-gray-400/30 text-gray-300';
  }

  // ── Diff HTML builder (all values sanitised via _escHtml) ───────

  _buildDiffHtml(_filePath, rawDiff) {
    const lines = String(rawDiff || '').split('\n');
    let oldNo = 0, newNo = 0;
    const rows = lines.map((line) => {
      if (line.startsWith('@@')) {
        const m = /@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line);
        if (m) { oldNo = Number(m[1]); newNo = Number(m[2]); }
        return `<div class="gd-line gd-hunk"><span class="gd-ln"></span><span class="gd-ln"></span><span class="gd-code">${this._escHtml(line)}</span></div>`;
      }
      const isMeta = line.startsWith('diff --git') || line.startsWith('index ') ||
        line.startsWith('--- ') || line.startsWith('+++ ') ||
        line.startsWith('new file mode ') || line.startsWith('deleted file mode ');
      if (isMeta) return `<div class="gd-line gd-meta"><span class="gd-ln"></span><span class="gd-ln"></span><span class="gd-code">${this._escHtml(line)}</span></div>`;

      let cls = 'gd-line gd-ctx', oldD = '', newD = '';
      if (line.startsWith('+') && !line.startsWith('+++')) {
        cls = 'gd-line gd-add'; newD = String(newNo || ''); newNo++;
      } else if (line.startsWith('-') && !line.startsWith('---')) {
        cls = 'gd-line gd-del'; oldD = String(oldNo || ''); oldNo++;
      } else if (oldNo > 0 || newNo > 0) {
        oldD = oldNo > 0 ? String(oldNo) : ''; newD = newNo > 0 ? String(newNo) : '';
        if (oldNo > 0) oldNo++; if (newNo > 0) newNo++;
      }
      return `<div class="${cls}"><span class="gd-ln">${oldD}</span><span class="gd-ln">${newD}</span><span class="gd-code">${this._escHtml(line)}</span></div>`;
    }).join('');
    return `<pre class="gd-pre">${rows}</pre>`;
  }

  _escHtml(v) {
    return String(v || '').replaceAll('&','&amp;').replaceAll('<','&lt;')
      .replaceAll('>','&gt;').replaceAll('"','&quot;').replaceAll("'",'&#39;');
  }
  _safeFilePath(p) {
    if (!p || typeof p !== 'string') return null;
    const d = p.replace(/%[0-9a-fA-F]{2}/g, c => decodeURIComponent(c));
    if (d.includes('..') || d.startsWith('/')) return null;
    return p;
  }

  // ── File tree builder ───────────────────────────────────────────

  _buildTree() {
    const root = { dirs: new Map(), files: [] };
    for (const f of this._files) {
      const parts = f.path.split('/');
      let node = root;
      for (let i = 0; i < parts.length - 1; i++) {
        if (!node.dirs.has(parts[i])) node.dirs.set(parts[i], { dirs: new Map(), files: [] });
        node = node.dirs.get(parts[i]);
      }
      node.files.push({ ...f, name: parts[parts.length - 1] });
    }
    return root;
  }

  _renderTree(node, depth = 0) {
    const items = [];
    for (const [name, child] of [...node.dirs.entries()].sort(([a],[b]) => a.localeCompare(b))) {
      items.push(html`
        <div class="flex items-center gap-1.5 py-0.5 text-[11px] text-gray-500 select-none"
             style="padding-left:${depth * 14 + 8}px">
          <svg class="w-3 h-3 text-gray-600 flex-shrink-0" viewBox="0 0 20 20" fill="currentColor">
            <path d="M3.75 3A1.75 1.75 0 0 0 2 4.75v3.26a3.235 3.235 0 0 1 1.75-.51h12.5c.644 0 1.245.188 1.75.51V6.75A1.75 1.75 0 0 0 16.25 5h-4.836a.25.25 0 0 1-.177-.073L9.823 3.513A1.75 1.75 0 0 0 8.586 3H3.75ZM3.75 9A1.75 1.75 0 0 0 2 10.75v4.5c0 .966.784 1.75 1.75 1.75h12.5A1.75 1.75 0 0 0 18 15.25v-4.5A1.75 1.75 0 0 0 16.25 9H3.75Z"/>
          </svg>
          <span>${name}</span>
        </div>
      `);
      items.push(this._renderTree(child, depth + 1));
    }
    for (const f of node.files.sort((a,b) => a.name.localeCompare(b.name))) {
      const active = this._activePath === f.path;
      items.push(html`
        <button type="button"
          class="w-full text-left flex items-center gap-1.5 py-1 rounded-md text-[11px] font-mono transition-colors
                 ${active ? 'bg-white/10 text-white' : 'text-gray-300 hover:bg-white/5'}"
          style="padding-left:${depth * 14 + 8}px; padding-right:8px"
          @click=${(e) => { e.stopPropagation(); this._scrollToFile(f.path); }}
          title="${f.path}">
          <span class="flex-shrink-0 w-5 text-center text-[10px] font-bold border rounded px-0.5 ${this._statusBg(f.code)}">${f.code}</span>
          <span class="flex-1 truncate">${f.name}</span>
          <span class="flex-shrink-0 text-[10px] tabular-nums space-x-0.5">
            ${f.additions > 0 ? html`<span class="text-emerald-400">+${f.additions}</span>` : html``}
            ${f.deletions  > 0 ? html`<span class="text-rose-400">-${f.deletions}</span>` : html``}
          </span>
        </button>
      `);
    }
    return html`${items}`;
  }

  // ── Render ──────────────────────────────────────────────────────

  _renderTitle() {
    if (this._loading) return 'Git changes \u2014 loading\u2026';
    const c = this._files.length;
    if (c === 0) return 'Git changes';
    const p = [`${c} file${c === 1 ? '' : 's'} changed`];
    if (this._insertions > 0) p.push(`+${this._insertions}`);
    if (this._deletions  > 0) p.push(`-${this._deletions}`);
    return p.join('  ');
  }

  _renderDiffViewer() {
    if (!this._expanded || this._files.length === 0) return html``;
    const tree = this._buildTree();
    return html`
      <div class="mt-2 rounded-xl border border-white/10 bg-gray-950/80 overflow-hidden">
        <!-- viewer header -->
        <div class="flex items-center gap-3 px-4 py-2 border-b border-white/10 text-xs bg-gray-900/60">
          <span class="font-semibold text-gray-200">Diff</span>
          ${this._insertions > 0 ? html`<span class="font-mono text-emerald-400">+${this._insertions}</span>` : html``}
          ${this._deletions  > 0 ? html`<span class="font-mono text-rose-400">-${this._deletions}</span>` : html``}
          <span class="text-gray-500 ml-auto">Files Changed ${this._files.length}</span>
        </div>
        <!-- two-panel layout -->
        <div class="flex" style="max-height:70vh">
          <!-- file sidebar -->
          <div class="w-72 flex-shrink-0 border-r border-white/10 overflow-y-auto bg-gray-950/50 py-1.5">
            ${this._renderTree(tree)}
          </div>
          <!-- diff scroll panel -->
          <div data-role="diff-scroll" class="flex-1 overflow-y-auto">
            ${this._diffsLoading && !this._diffsLoaded
              ? html`<p class="text-xs text-gray-400 animate-pulse p-6">Loading diffs\u2026</p>`
              : html``}
            ${this._files.map((f) => html`
              <div id="${this._diffSlotId(f.path)}" class="border-b border-white/10">
                <!-- file diff header -->
                <div class="sticky top-0 z-10 flex items-center gap-2 px-4 py-1.5 text-[11px] font-mono bg-gray-900/95 border-b border-white/5 backdrop-blur-sm">
                  <span class="flex-shrink-0 w-5 text-center font-bold border rounded px-0.5 text-[10px] ${this._statusBg(f.code)}">${f.code}</span>
                  <span class="text-gray-300 truncate flex-1" title="${f.path}">${f.path}</span>
                  ${f.additions > 0 ? html`<span class="text-emerald-400">+${f.additions}</span>` : html``}
                  ${f.deletions  > 0 ? html`<span class="text-rose-400">-${f.deletions}</span>` : html``}
                </div>
                <!-- diff content placeholder (populated via DOM after render) -->
                <div data-role="diff-body" class="overflow-x-auto"></div>
              </div>
            `)}
          </div>
        </div>
      </div>
    `;
  }

  render() {
    const hasFiles   = this._files.length > 0;
    const chevronRot = this._expanded ? 'rotate-180' : '';

    return html`
      ${this._expanded ? html`
      <style>
        .gd-pre{margin:0;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;line-height:20px;background:transparent}
        .gd-line{display:flex;min-width:fit-content}
        .gd-ln{display:inline-block;width:3.5em;text-align:right;padding-right:.75em;color:rgba(148,163,184,.35);user-select:none;flex-shrink:0}
        .gd-code{flex:1;padding-left:.5em;padding-right:1em;white-space:pre;tab-size:4}
        .gd-add{background:rgba(34,197,94,.1)}.gd-add .gd-code{color:#86efac}
        .gd-del{background:rgba(239,68,68,.1)}.gd-del .gd-code{color:#fca5a5}
        .gd-hunk{background:rgba(56,189,248,.06);color:#7dd3fc}
        .gd-meta{color:rgba(148,163,184,.45)}
        .gd-ctx .gd-code{color:#cbd5e1}
      </style>` : html``}
      <div>
        <div
          class="relative flex items-center gap-2 rounded-md border border-emerald-400/40 bg-emerald-500/8 px-3 py-1.5 my-1 text-[11px] cursor-pointer hover:bg-white/5 transition-colors"
          @click="${this._handleHeaderClick}"
          role="button" tabindex="0"
          @keydown="${(e) => (e.key === 'Enter' || e.key === ' ') && this._handleHeaderClick()}"
        >
          <div class="absolute left-0 top-0 bottom-0 w-0.5 rounded-l-md bg-emerald-400"></div>
          <svg xmlns="http://www.w3.org/2000/svg" class="w-3.5 h-3.5 flex-shrink-0 text-emerald-400"
               viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"
               stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M7.5 3.75a.75.75 0 0 1 .75.75v1.5a.75.75 0 0 1-1.5 0v-1.5a.75.75 0 0 1 .75-.75Zm4.5 0a.75.75 0 0 1 .75.75v1.5a.75.75 0 0 1-1.5 0v-1.5a.75.75 0 0 1 .75-.75ZM6 7.5a.75.75 0 0 1 .75-.75h10.5a.75.75 0 0 1 0 1.5H6.75A.75.75 0 0 1 6 7.5Zm3.75 8.25a.75.75 0 0 1 .75-.75h3a.75.75 0 0 1 0 1.5h-3a.75.75 0 0 1-.75-.75Zm-3 3a.75.75 0 0 1 .75-.75h9a.75.75 0 0 1 0 1.5h-9a.75.75 0 0 1-.75-.75Z"/>
          </svg>
          <span class="flex-1 text-gray-300 truncate font-mono">${this._renderTitle()}</span>
          ${hasFiles ? html`
            <svg xmlns="http://www.w3.org/2000/svg"
                 class="w-3 h-3 text-gray-500 flex-shrink-0 transition-transform ${chevronRot}"
                 viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                 stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M19.5 8.25l-7.5 7.5-7.5-7.5"/>
            </svg>` : html``}
        </div>
        ${this._renderDiffViewer()}
      </div>
    `;
  }

  async _fetchJson(url) {
    if (!url) return null;
    try {
      const r = await fetch(url, { headers: { Accept: 'application/json' } });
      if (!r.ok) { console.warn(`ab-git-summary: HTTP ${r.status} for ${url}`); return null; }
      return await r.json();
    } catch (err) { console.error(`ab-git-summary: fetch error for ${url}:`, err.message); return null; }
  }
}

if (!customElements.get('ab-git-summary')) {
  customElements.define('ab-git-summary', AbGitSummary);
}
