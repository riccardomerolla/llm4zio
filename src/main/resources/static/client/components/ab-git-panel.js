import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

class AbGitPanel extends LitElement {
  static properties = {
    workspaceId:    { type: String, attribute: 'data-workspace-id' },
    runId:          { type: String, attribute: 'data-run-id' },
    topic:          { type: String, attribute: 'data-topic' },
    statusEndpoint: { type: String, attribute: 'data-status-endpoint' },
    diffEndpoint:   { type: String, attribute: 'data-diff-endpoint' },
    logEndpoint:    { type: String, attribute: 'data-log-endpoint' },
    branchEndpoint: { type: String, attribute: 'data-branch-endpoint' },
    applyEndpoint:  { type: String, attribute: 'data-apply-endpoint' },
  };

  constructor() {
    super();
    this.workspaceId    = '';
    this.runId          = '';
    this.topic          = '';

    this._resolvedTopic   = '';
    this.statusEndpoint = '';
    this.diffEndpoint   = '';
    this.logEndpoint    = '';
    this.branchEndpoint = '';
    this.applyEndpoint  = '';

    this._summaryEl       = null;
    this._filesGroupsEl   = null;
    this._diffViewerEl    = null;
    this._diffTitleEl     = null;
    this._diffContentEl   = null;
    this._branchCurrentEl = null;
    this._aheadBehindEl   = null;
    this._applyButtonEl   = null;
    this._applyFeedbackEl = null;
    this._commitLogEl     = null;

    this._ws              = null;
    this._currentDiffPath = null;
    this._changedFiles    = new Set();
    this._status          = null;
    this._diffStat        = null;
    this._branch          = null;
  }

  createRenderRoot() { return this; }

  connectedCallback() {
    super.connectedCallback();
    // Derive topic from data-run-id only when data-topic was not provided explicitly
    if (!this.topic && this.runId) {
      this._resolvedTopic = `runs:${this.runId}:git`;
    } else {
      this._resolvedTopic = this.topic || '';
    }

    this._summaryEl       = this.querySelector('[data-role="summary"]');
    this._filesGroupsEl   = this.querySelector('[data-role="files-groups"]');
    this._diffViewerEl    = this.querySelector('[data-role="diff-viewer"]');
    this._diffTitleEl     = this.querySelector('[data-role="diff-title"]');
    this._diffContentEl   = this.querySelector('[data-role="diff-content"]');
    this._branchCurrentEl = this.querySelector('[data-role="branch-current"]');
    this._aheadBehindEl   = this.querySelector('[data-role="ahead-behind"]');
    this._applyButtonEl   = this.querySelector('[data-role="apply-button"]');
    this._applyFeedbackEl = this.querySelector('[data-role="apply-feedback"]');
    this._commitLogEl     = this.querySelector('[data-role="commit-log"]');

    this._bind();
    this._loadAll();
    this._connectWs();
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this._disconnectWs();
  }

  render() { return html``; }

  _bind() {
    this._filesGroupsEl?.addEventListener('click', (event) => {
      const button = event.target.closest('[data-role="file"]');
      if (!button) return;
      event.preventDefault();
      const path = button.dataset.filePath || '';
      if (!path) return;
      this._toggleDiff(path);
    });

    this._diffContentEl?.addEventListener('htmx:afterSwap', () => {
      this._renderDiffText(this._diffContentEl.textContent || '');
    });
    this._applyButtonEl?.addEventListener('click', () => this._applyToRepo());

    window.addEventListener('beforeunload', () => this._disconnectWs());
  }

  async _loadAll() {
    await Promise.all([this._refreshFiles(false), this._refreshBranchAndCommits()]);
  }

  async _refreshFiles(shouldFlash) {
    const [status, diffStat] = await Promise.all([
      this._fetchJson(this.statusEndpoint),
      // Always compare against main so only committed, tracked changes are shown —
      // build artefacts and other gitignored files are excluded naturally.
      this._fetchJson(`${this.diffEndpoint}?base=main`),
    ]);
    if (!status || !diffStat) return;

    const previous = this._changedFiles;
    this._status = status;
    this._diffStat = diffStat;

    const nextChanged = this._computeChangedFiles(status, diffStat);
    this._changedFiles = nextChanged;

    const flash = shouldFlash
      ? new Set(Array.from(nextChanged).filter((entry) => !previous.has(entry)))
      : new Set();

    this._renderFiles(status, diffStat, flash);
  }

  async _refreshBranchAndCommits() {
    const branch = await this._fetchJson(this.branchEndpoint);
    if (!branch) return;
    this._branch = branch;
    this._renderBranch(branch);

    const ahead = Number(branch?.aheadBehind?.ahead || 0);
    if (ahead <= 0) {
      this._renderCommits([]);
      return;
    }
    const commitLimit = Math.max(Math.min(ahead, 30), 5);
    const commits = await this._fetchJson(`${this.logEndpoint}?limit=${commitLimit}`);
    if (Array.isArray(commits)) {
      this._renderCommits(commits.slice(0, ahead));
    }
  }

  _computeChangedFiles(status, diffStat) {
    const key = (entry) => `${entry.status}:${entry.path}`;
    const tracked = [
      ...(status?.staged || []).map((entry) => key(entry)),
      ...(status?.unstaged || []).map((entry) => key(entry)),
      ...(diffStat?.files || []).map((file) => `Committed:${file.path}`),
    ];
    return new Set(tracked);
  }

  _renderFiles(status, diffStat, flashEntries) {
    const statsByPath = new Map((diffStat?.files || []).map((file) => [file.path, file]));
    const staged = (status?.staged || []).map((entry) => ({
      path: entry.path,
      status: entry.status,
      group: 'Staged',
      key: `${entry.status}:${entry.path}`,
    }));
    const unstaged = (status?.unstaged || []).map((entry) => ({
      path: entry.path,
      status: entry.status,
      group: 'Unstaged',
      key: `${entry.status}:${entry.path}`,
    }));
    // Committed files from git diff main...HEAD — only tracked changes, gitignore respected.
    const committed = (diffStat?.files || []).map((file) => ({
      path: file.path,
      status: 'Committed',
      group: 'Committed',
      key: `Committed:${file.path}`,
    }));

    const grouped = [
      { label: 'Committed vs main', rows: committed },
      { label: 'Staged', rows: staged },
      { label: 'Unstaged', rows: unstaged },
    ];

    const uniquePaths = new Set([...committed, ...staged, ...unstaged].map((entry) => entry.path));
    const insertions = (diffStat?.files || []).reduce((total, item) => total + Number(item.additions || 0), 0);
    const deletions = (diffStat?.files || []).reduce((total, item) => total + Number(item.deletions || 0), 0);

    if (this._summaryEl) {
      this._summaryEl.textContent = `${uniquePaths.size} files changed, ${insertions} insertions, ${deletions} deletions`;
    }

    if (!this._filesGroupsEl) return;

    const renderedGroups = grouped
      .map((group) => {
        if (!group.rows.length) {
          return `<section><h4 class="text-xs font-semibold text-gray-300 mb-1">${group.label}</h4><p class="text-xs text-gray-500">No files</p></section>`;
        }
        const rows = group.rows
          .map((entry) => {
            const stat = statsByPath.get(entry.path);
            const additions = Number(stat?.additions || 0);
            const deletions = Number(stat?.deletions || 0);
            const flashClass = flashEntries.has(entry.key) ? ' flash' : '';
            const badge = this._renderStatusBadge(entry.status);
            // Committed-group files request diff against main so the viewer shows the full branch diff
            const diffUrl = entry.group === 'Committed'
              ? `${this.diffEndpoint}/${encodeURIComponent(entry.path)}?base=main`
              : `${this.diffEndpoint}/${encodeURIComponent(entry.path)}`;
            return `<button type="button" data-role="file" data-file-path="${this._escapeAttr(entry.path)}" hx-get="${this._escapeAttr(diffUrl)}" hx-target="this" class="git-file-row${flashClass} w-full text-left rounded-md border border-white/10 bg-white/5 px-2 py-1.5 hover:bg-white/10">
              <div class="flex items-center justify-between gap-2">
                <div class="min-w-0 flex items-center gap-2">
                  ${badge}
                  <span class="truncate text-xs text-gray-100 font-mono">${this._escapeHtml(entry.path)}</span>
                </div>
                <span class="text-[11px] text-gray-400">+${additions} / -${deletions}</span>
              </div>
            </button>`;
          })
          .join('');
        return `<section><h4 class="text-xs font-semibold text-gray-300 mb-1">${group.label}</h4><div class="space-y-1">${rows}</div></section>`;
      })
      .join('');

    this._filesGroupsEl.innerHTML = renderedGroups;
  }

  _renderStatusBadge(status) {
    const normalized = String(status || '').toLowerCase();
    if (normalized === 'modified') return '<span class="inline-flex h-5 w-5 items-center justify-center rounded bg-blue-500/30 text-blue-200 text-[10px] font-bold">M</span>';
    if (normalized === 'added') return '<span class="inline-flex h-5 w-5 items-center justify-center rounded bg-emerald-500/30 text-emerald-200 text-[10px] font-bold">A</span>';
    if (normalized === 'deleted') return '<span class="inline-flex h-5 w-5 items-center justify-center rounded bg-rose-500/30 text-rose-200 text-[10px] font-bold">D</span>';
    if (normalized === 'committed') return '<span class="inline-flex h-5 w-5 items-center justify-center rounded bg-violet-500/30 text-violet-200 text-[10px] font-bold">C</span>';
    return '<span class="inline-flex h-5 w-5 items-center justify-center rounded bg-slate-500/30 text-slate-200 text-[10px] font-bold">?</span>';
  }

  _renderBranch(branchView) {
    const current = branchView?.branch?.current || 'unknown';
    const ahead = Number(branchView?.aheadBehind?.ahead || 0);
    const behind = Number(branchView?.aheadBehind?.behind || 0);
    const base = branchView?.baseBranch || 'main';

    if (this._branchCurrentEl) {
      const safeCurrent = this._escapeHtml(current);
      const safeCurrentAttr = this._escapeAttr(current);
      this._branchCurrentEl.innerHTML = `<div class="flex items-center gap-2">
        <span class="font-mono text-gray-100">${safeCurrent}</span>
        <button type="button" data-role="copy-branch" data-branch="${safeCurrentAttr}" class="rounded bg-white/10 px-2 py-0.5 text-[11px] text-gray-200 hover:bg-white/20">Copy</button>
      </div>`;
      const copyBtn = this._branchCurrentEl.querySelector('[data-role="copy-branch"]');
      copyBtn?.addEventListener('click', async () => {
        try {
          await navigator.clipboard.writeText(current);
          copyBtn.textContent = 'Copied';
          setTimeout(() => {
            copyBtn.textContent = 'Copy';
          }, 900);
        } catch (_ignored) {
          copyBtn.textContent = 'Unavailable';
          setTimeout(() => {
            copyBtn.textContent = 'Copy';
          }, 1200);
        }
      });
    }
    if (this._aheadBehindEl) {
      this._aheadBehindEl.textContent = `${ahead} commits ahead of ${base}, ${behind} behind`;
    }
  }

  _renderCommits(commits) {
    if (!this._commitLogEl) return;
    if (!commits.length) {
      this._commitLogEl.innerHTML = '<p class="text-xs text-gray-500">No commits ahead of main.</p>';
      return;
    }

    this._commitLogEl.innerHTML = commits
      .map((entry) => {
        const hash = this._escapeHtml(entry.shortHash || '').slice(0, 12);
        const message = this._truncate(this._escapeHtml(entry.message || ''), 74);
        const author = this._escapeHtml(entry.author || 'unknown');
        const relative = this._relativeTime(entry.date);
        return `<article class="rounded border border-white/10 bg-white/5 px-2 py-1.5">
          <div class="flex items-center justify-between gap-2 text-[11px] text-gray-400">
            <span class="font-mono text-indigo-200">${hash}</span>
            <span>${relative}</span>
          </div>
          <p class="text-xs text-gray-100 mt-1">${message}</p>
          <p class="text-[11px] text-gray-400 mt-1">${author}</p>
        </article>`;
      })
      .join('');
  }

  _toggleDiff(path) {
    if (!this._diffViewerEl || !this._diffTitleEl || !this._diffContentEl) return;
    if (this._currentDiffPath === path && !this._diffViewerEl.classList.contains('hidden')) {
      this._diffViewerEl.classList.add('hidden');
      this._currentDiffPath = null;
      return;
    }

    this._currentDiffPath = path;
    this._diffViewerEl.classList.remove('hidden');
    this._diffTitleEl.textContent = path;
    this._diffContentEl.textContent = 'Loading diff...';

    const url = `${this.diffEndpoint}/${encodeURIComponent(path)}`;
    fetch(url)
      .then((response) => response.ok ? response.text() : Promise.reject(new Error('Failed to load diff')))
      .then((text) => {
        this._renderDiffText(text);
      })
      .catch(() => {
        this._diffContentEl.textContent = 'Unable to load diff.';
      });
  }

  _renderDiffText(rawDiff) {
    if (!this._diffContentEl) return;
    const lines = String(rawDiff || '').split('\n');
    let oldNo = 0;
    let newNo = 0;

    const html = lines
      .map((line) => {
        if (line.startsWith('@@')) {
          const match = /@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line);
          if (match) {
            oldNo = Number(match[1]);
            newNo = Number(match[2]);
          }
          return `<div class="git-diff-line git-diff-hunk"><span class="line-no old"></span><span class="line-no new"></span><span class="code">${this._escapeHtml(line)}</span></div>`;
        }

        const isMeta =
          line.startsWith('diff --git') ||
          line.startsWith('index ') ||
          line.startsWith('--- ') ||
          line.startsWith('+++ ') ||
          line.startsWith('new file mode ') ||
          line.startsWith('deleted file mode ');
        if (isMeta) {
          return `<div class="git-diff-line git-diff-meta"><span class="line-no old"></span><span class="line-no new"></span><span class="code">${this._escapeHtml(line)}</span></div>`;
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
        } else {
          if (oldNo > 0 || newNo > 0) {
            oldDisplay = oldNo > 0 ? String(oldNo) : '';
            newDisplay = newNo > 0 ? String(newNo) : '';
            if (oldNo > 0) oldNo += 1;
            if (newNo > 0) newNo += 1;
          }
        }

        return `<div class="${cls}"><span class="line-no old">${oldDisplay}</span><span class="line-no new">${newDisplay}</span><span class="code">${this._escapeHtml(line)}</span></div>`;
      })
      .join('');

    this._diffContentEl.innerHTML = html;
  }

  _connectWs() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    this._ws = new WebSocket(`${protocol}//${window.location.host}/ws/console`);
    this._ws.onopen = () => {
      this._ws?.send(JSON.stringify({ Subscribe: { topic: this._resolvedTopic, params: {} } }));
    };
    this._ws.onmessage = (event) => this._onWsMessage(event.data);
  }

  _disconnectWs() {
    if (this._ws && this._ws.readyState === WebSocket.OPEN) {
      this._ws.send(JSON.stringify({ Unsubscribe: { topic: this._resolvedTopic } }));
      this._ws.close();
    }
    this._ws = null;
  }

  _onWsMessage(raw) {
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch (_ignored) {
      return;
    }

    const event = parsed?.Event;
    if (!event || event.topic !== this._resolvedTopic) return;

    if (event.eventType === 'git-status-update') {
      this._refreshFiles(true);
      this._refreshBranchAndCommits();
      return;
    }

    if (event.eventType === 'git-new-commit') {
      this._refreshBranchAndCommits();
    }
  }

  async _fetchJson(url) {
    if (!url) return null;
    try {
      const response = await fetch(url, { headers: { Accept: 'application/json' } });
      if (!response.ok) return null;
      return await response.json();
    } catch (_ignored) {
      return null;
    }
  }

  async _applyToRepo() {
    if (!this.applyEndpoint || !this._applyButtonEl) return;
    if (!window.confirm('Apply this run branch to the workspace repository?')) return;

    const button = this._applyButtonEl;
    const prevText = button.textContent || 'Apply to repo';
    button.disabled = true;
    button.textContent = 'Applying...';
    this._setApplyFeedback('Applying run branch to repository...', false);

    try {
      const response = await fetch(this.applyEndpoint, {
        method: 'POST',
        headers: { Accept: 'application/json' },
      });
      const text = await response.text();
      let payload = null;
      try {
        payload = text ? JSON.parse(text) : null;
      } catch (_ignored) {
        payload = null;
      }

      if (!response.ok) {
        const message = payload?.message || text || 'Apply failed.';
        this._setApplyFeedback(message, true);
        return;
      }

      const message = payload?.message || 'Applied successfully.';
      this._setApplyFeedback(message, false);
      await this._refreshFiles(true);
      await this._refreshBranchAndCommits();
    } catch (_err) {
      this._setApplyFeedback('Apply failed: network error.', true);
    } finally {
      button.disabled = false;
      button.textContent = prevText;
    }
  }

  _setApplyFeedback(message, isError) {
    if (!this._applyFeedbackEl) return;
    this._applyFeedbackEl.textContent = message || '';
    this._applyFeedbackEl.className = isError ? 'text-xs text-rose-300' : 'text-xs text-emerald-300';
  }

  _truncate(text, max) {
    if (text.length <= max) return text;
    return `${text.slice(0, Math.max(0, max - 3))}...`;
  }

  _relativeTime(isoTimestamp) {
    const ms = Date.parse(isoTimestamp || '');
    if (!Number.isFinite(ms)) return 'unknown';

    const deltaSeconds = Math.floor((Date.now() - ms) / 1000);
    if (deltaSeconds < 60) return `${Math.max(deltaSeconds, 0)}s ago`;
    const deltaMinutes = Math.floor(deltaSeconds / 60);
    if (deltaMinutes < 60) return `${deltaMinutes}m ago`;
    const deltaHours = Math.floor(deltaMinutes / 60);
    if (deltaHours < 24) return `${deltaHours}h ago`;
    const deltaDays = Math.floor(deltaHours / 24);
    return `${deltaDays}d ago`;
  }

  _escapeHtml(value) {
    return String(value || '')
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  }

  _escapeAttr(value) {
    return this._escapeHtml(value);
  }
}

if (!customElements.get('ab-git-panel')) {
  customElements.define('ab-git-panel', AbGitPanel);
}
