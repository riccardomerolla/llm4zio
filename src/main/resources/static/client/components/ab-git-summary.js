import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

/**
 * ab-git-summary — inline timeline git change summary
 *
 * Attributes:
 *   status-url   — API endpoint for git status JSON
 *   diff-url     — API endpoint for git diffstat JSON
 *   panel-target — panel-id to open for diff view (default: context-panel)
 *
 * Renders an ab-timeline-event with:
 *   - title: "N files changed  +X -Y"
 *   - expandable file list with per-file change stats
 *   - clicking a file opens its diff in the side panel via ab-panel-open
 */
class AbGitSummary extends LitElement {
  static properties = {
    'status-url':   { type: String },
    'diff-url':     { type: String },
    'panel-target': { type: String },
    _loading:       { type: Boolean, state: true },
    _files:         { type: Array,   state: true },
    _insertions:    { type: Number,  state: true },
    _deletions:     { type: Number,  state: true },
    _expanded:      { type: Boolean, state: true },
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
  }

  createRenderRoot() { return this; }

  connectedCallback() {
    super.connectedCallback();
    this._load();
  }

  get statusUrl()   { return this['status-url']   || ''; }
  get diffUrl()     { return this['diff-url']      || ''; }
  get panelTarget() { return this['panel-target']  || 'context-panel'; }

  async _load() {
    if (!this.statusUrl || !this.diffUrl) return;
    this._loading = true;
    try {
      const [status, diffStat] = await Promise.all([
        this._fetchJson(this.statusUrl),
        this._fetchJson(this.diffUrl),
      ]);
      if (!status && !diffStat) {
        this._loading = false;
        return;
      }
      this._buildFileList(status, diffStat);
    } catch (err) {
      console.warn('ab-git-summary: failed to load git data:', err.message);
    }
    this._loading = false;
  }

  _buildFileList(status, diffStat) {
    const statsByPath = new Map((diffStat?.files || []).map((f) => [f.path, f]));

    const allEntries = [
      ...(status?.staged   || []).map((e) => ({ path: e.path, code: this._statusCode(e.status), group: 'staged'    })),
      ...(status?.unstaged || []).map((e) => ({ path: e.path, code: this._statusCode(e.status), group: 'unstaged'  })),
      ...(status?.untracked|| []).map((p) => ({ path: p,      code: 'A',                        group: 'untracked' })),
    ];

    // Deduplicate by path (prefer staged)
    const seen = new Map();
    for (const entry of allEntries) {
      if (!seen.has(entry.path)) seen.set(entry.path, entry);
    }

    const files = [];
    let totalInsertions = 0;
    let totalDeletions  = 0;

    for (const entry of seen.values()) {
      const stat       = statsByPath.get(entry.path);
      const additions  = Number(stat?.additions || 0);
      const deletions  = Number(stat?.deletions  || 0);
      totalInsertions += additions;
      totalDeletions  += deletions;
      files.push({ ...entry, additions, deletions });
    }

    this._files      = files;
    this._insertions = totalInsertions;
    this._deletions  = totalDeletions;
  }

  _statusCode(status) {
    const s = String(status || '').toLowerCase();
    if (s === 'modified')  return 'M';
    if (s === 'added')     return 'A';
    if (s === 'deleted')   return 'D';
    if (s === 'renamed')   return 'R';
    return '?';
  }

  _statusColor(code) {
    if (code === 'M') return 'text-blue-300';
    if (code === 'A') return 'text-emerald-300';
    if (code === 'D') return 'text-rose-300';
    if (code === 'R') return 'text-amber-300';
    return 'text-gray-400';
  }

  _handleHeaderClick() {
    if (this._files.length > 0) {
      this._expanded = !this._expanded;
    } else {
      // No files loaded yet — just open the git panel
      window.dispatchEvent(new CustomEvent('ab-panel-open', {
        detail: {
          panelId: this.panelTarget,
          title:   'Git Changes',
        },
      }));
    }
  }

  async _waitForPanelContent(panelId, timeout = 2000) {
    const id = `panel-${panelId}-content`;
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      const el = document.getElementById(id);
      if (el) return el;
      await new Promise(r => setTimeout(r, 40));
    }
    return null;
  }

  async _handleFileClick(file) {
    // Validate file path before proceeding
    const safePath = this._safeFilePath(file.path);
    if (!safePath) {
      console.warn('ab-git-summary: invalid file path', file.path);
      return;
    }

    // Open the panel immediately with a loading placeholder
    window.dispatchEvent(new CustomEvent('ab-panel-open', {
      detail: { panelId: this.panelTarget, title: `Git Diff — ${file.path}` },
    }));

    // Poll for the panel content to be available
    const panelContent = await this._waitForPanelContent(this.panelTarget);
    if (!panelContent) {
      console.warn('ab-git-summary: panel content not found');
      return;
    }

    // Show loading state with safe DOM method
    const loadingEl = document.createElement('p');
    loadingEl.className = 'text-xs text-gray-400 animate-pulse p-4';
    loadingEl.textContent = 'Loading diff\u2026';
    panelContent.replaceChildren(loadingEl);

    const url = `${this.diffUrl}/${encodeURIComponent(safePath)}`;
    try {
      const response = await fetch(url);
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      const rawDiff = await response.text();

      // Build diff wrapper with escaped content
      const diffWrapper = document.createElement('div');
      diffWrapper.className = 'p-3';
      diffWrapper.innerHTML = this._buildDiffHtml(file.path, rawDiff);
      panelContent.replaceChildren(diffWrapper);
    } catch (err) {
      console.error('ab-git-summary: failed to fetch diff for', safePath, ':', err.message);
      const errorEl = document.createElement('p');
      errorEl.className = 'text-xs text-rose-400 p-4';
      errorEl.textContent = 'Failed to load diff.';
      panelContent.replaceChildren(errorEl);
    }
  }

  // Render a unified diff as highlighted HTML (same approach as git-panel.js)
  _buildDiffHtml(filePath, rawDiff) {
    const lines = String(rawDiff || '').split('\n');
    let oldNo = 0;
    let newNo = 0;

    const rows = lines.map((line) => {
      if (line.startsWith('@@')) {
        const m = /@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line);
        if (m) { oldNo = Number(m[1]); newNo = Number(m[2]); }
        return `<div class="git-diff-line git-diff-hunk"><span class="line-no old"></span><span class="line-no new"></span><span class="code">${this._escHtml(line)}</span></div>`;
      }
      const isMeta =
        line.startsWith('diff --git') || line.startsWith('index ') ||
        line.startsWith('--- ')       || line.startsWith('+++ ') ||
        line.startsWith('new file mode ') || line.startsWith('deleted file mode ');
      if (isMeta) {
        return `<div class="git-diff-line git-diff-meta"><span class="line-no old"></span><span class="line-no new"></span><span class="code">${this._escHtml(line)}</span></div>`;
      }
      let cls = 'git-diff-line git-diff-context';
      let oldDisplay = '';
      let newDisplay = '';
      if (line.startsWith('+') && !line.startsWith('+++')) {
        cls = 'git-diff-line git-diff-added';
        newDisplay = String(newNo || '');
        newNo += 1;
      } else if (line.startsWith('-') && !line.startsWith('---')) {
        cls = 'git-diff-line git-diff-deleted';
        oldDisplay = String(oldNo || '');
        oldNo += 1;
      } else if (oldNo > 0 || newNo > 0) {
        oldDisplay = oldNo > 0 ? String(oldNo) : '';
        newDisplay = newNo > 0 ? String(newNo) : '';
        if (oldNo > 0) oldNo += 1;
        if (newNo > 0) newNo += 1;
      }
      return `<div class="${cls}"><span class="line-no old">${oldDisplay}</span><span class="line-no new">${newDisplay}</span><span class="code">${this._escHtml(line)}</span></div>`;
    }).join('');

    const safeTitle = this._escHtml(filePath);
    return `<h4 class="text-xs font-semibold text-gray-300 font-mono mb-2 truncate" title="${safeTitle}">${safeTitle}</h4>`
         + `<pre class="git-diff-lines text-xs overflow-auto rounded bg-black/40 p-2">${rows}</pre>`;
  }

  _escHtml(value) {
    return String(value || '')
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  }

  _safeFilePath(path) {
    if (!path || typeof path !== 'string') return null;
    const decoded = path.replace(/%[0-9a-fA-F]{2}/g, c => decodeURIComponent(c));
    if (decoded.includes('..') || decoded.startsWith('/')) return null;
    return path;
  }

  _renderTitle() {
    if (this._loading) return 'Git changes — loading…';
    const count = this._files.length;
    if (count === 0) return 'Git changes';
    const parts = [`${count} file${count === 1 ? '' : 's'} changed`];
    if (this._insertions > 0) parts.push(`+${this._insertions}`);
    if (this._deletions  > 0) parts.push(`-${this._deletions}`);
    return parts.join('  ');
  }

  _renderFileList() {
    if (!this._expanded || this._files.length === 0) return html``;
    return html`
      <div class="mt-1 ml-6 pl-2 border-l-2 border-emerald-400 space-y-0.5">
        ${this._files.map((file) => html`
          <button
            type="button"
            class="w-full text-left flex items-center gap-2 px-1 py-0.5 rounded text-[11px] font-mono hover:bg-white/5 transition-colors group/file"
            @click="${(e) => { e.stopPropagation(); this._handleFileClick(file); }}"
            title="View diff for ${file.path}"
          >
            <span class="flex-shrink-0 w-4 font-bold ${this._statusColor(file.code)}">${file.code}</span>
            <span class="flex-1 text-gray-300 truncate">${file.path}</span>
            <span class="flex-shrink-0 text-gray-500 text-[10px]">
              ${file.additions > 0 ? html`<span class="text-emerald-400">+${file.additions}</span>` : html``}${file.additions > 0 && file.deletions > 0 ? html` ` : html``}${file.deletions > 0 ? html`<span class="text-rose-400">-${file.deletions}</span>` : html``}
            </span>
          </button>
        `)}
      </div>
    `;
  }

  render() {
    const hasFiles   = this._files.length > 0;
    const expandable = hasFiles;
    const chevronRot = this._expanded ? 'rotate-180' : '';

    return html`
      <div>
        <div
          class="relative flex items-center gap-2 rounded-md border border-emerald-400/40 bg-emerald-500/8 px-3 py-1.5 my-1 text-[11px] cursor-pointer hover:bg-white/5 transition-colors"
          @click="${this._handleHeaderClick}"
          role="button"
          tabindex="0"
          @keydown="${(e) => (e.key === 'Enter' || e.key === ' ') && this._handleHeaderClick()}"
        >
          <!-- left accent line -->
          <div class="absolute left-0 top-0 bottom-0 w-0.5 rounded-l-md bg-emerald-400"></div>

          <!-- git icon -->
          <svg
            xmlns="http://www.w3.org/2000/svg"
            class="w-3.5 h-3.5 flex-shrink-0 text-emerald-400"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="M7.5 3.75a.75.75 0 0 1 .75.75v1.5a.75.75 0 0 1-1.5 0v-1.5a.75.75 0 0 1 .75-.75Zm4.5 0a.75.75 0 0 1 .75.75v1.5a.75.75 0 0 1-1.5 0v-1.5a.75.75 0 0 1 .75-.75ZM6 7.5a.75.75 0 0 1 .75-.75h10.5a.75.75 0 0 1 0 1.5H6.75A.75.75 0 0 1 6 7.5Zm3.75 8.25a.75.75 0 0 1 .75-.75h3a.75.75 0 0 1 0 1.5h-3a.75.75 0 0 1-.75-.75Zm-3 3a.75.75 0 0 1 .75-.75h9a.75.75 0 0 1 0 1.5h-9a.75.75 0 0 1-.75-.75Z" />
          </svg>

          <!-- title -->
          <span class="flex-1 text-gray-300 truncate font-mono">${this._renderTitle()}</span>

          <!-- expand chevron (only when files are available) -->
          ${expandable ? html`
            <svg
              xmlns="http://www.w3.org/2000/svg"
              class="w-3 h-3 text-gray-500 flex-shrink-0 transition-transform ${chevronRot}"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <path d="M19.5 8.25l-7.5 7.5-7.5-7.5" />
            </svg>
          ` : html``}
        </div>

        <!-- expanded file list -->
        ${this._renderFileList()}
      </div>
    `;
  }

  async _fetchJson(url) {
    if (!url) return null;
    try {
      const response = await fetch(url, { headers: { Accept: 'application/json' } });
      if (!response.ok) {
        console.warn(`ab-git-summary: HTTP ${response.status} for ${url}`);
        return null;
      }
      return await response.json();
    } catch (err) {
      console.error(`ab-git-summary: fetch error for ${url}:`, err.message);
      return null;
    }
  }
}

if (!customElements.get('ab-git-summary')) {
  customElements.define('ab-git-summary', AbGitSummary);
}
