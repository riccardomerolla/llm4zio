class GitPanel {
  constructor(root) {
    this.root = root;
    this.workspaceId = root?.dataset?.workspaceId || '';
    this.runId = root?.dataset?.runId || '';
    this.topic = root?.dataset?.topic || `runs:${this.runId}:git`;
    this.statusEndpoint = root?.dataset?.statusEndpoint || '';
    this.diffEndpoint = root?.dataset?.diffEndpoint || '';
    this.logEndpoint = root?.dataset?.logEndpoint || '';
    this.branchEndpoint = root?.dataset?.branchEndpoint || '';

    this.summaryEl = root.querySelector('[data-role="summary"]');
    this.filesGroupsEl = root.querySelector('[data-role="files-groups"]');
    this.diffViewerEl = root.querySelector('[data-role="diff-viewer"]');
    this.diffTitleEl = root.querySelector('[data-role="diff-title"]');
    this.diffContentEl = root.querySelector('[data-role="diff-content"]');
    this.branchCurrentEl = root.querySelector('[data-role="branch-current"]');
    this.aheadBehindEl = root.querySelector('[data-role="ahead-behind"]');
    this.commitLogEl = root.querySelector('[data-role="commit-log"]');

    this.ws = null;
    this.currentDiffPath = null;
    this.changedFiles = new Set();
    this.status = null;
    this.diffStat = null;
    this.branch = null;

    this.bind();
    this.loadAll();
    this.connectWs();
  }

  bind() {
    this.filesGroupsEl?.addEventListener('click', (event) => {
      const button = event.target.closest('[data-role="file"]');
      if (!button) return;
      event.preventDefault();
      const path = button.dataset.filePath || '';
      if (!path) return;
      this.toggleDiff(path);
    });

    this.diffContentEl?.addEventListener('htmx:afterSwap', () => {
      this.renderDiffText(this.diffContentEl.textContent || '');
    });

    window.addEventListener('beforeunload', () => this.disconnectWs());
  }

  async loadAll() {
    await Promise.all([this.refreshFiles(false), this.refreshBranchAndCommits()]);
  }

  async refreshFiles(shouldFlash) {
    const [status, diffStat] = await Promise.all([
      this.fetchJson(this.statusEndpoint),
      this.fetchJson(this.diffEndpoint),
    ]);
    if (!status || !diffStat) return;

    const previous = this.changedFiles;
    this.status = status;
    this.diffStat = diffStat;

    const nextChanged = this.computeChangedFiles(status);
    this.changedFiles = nextChanged;

    const flash = shouldFlash
      ? new Set(Array.from(nextChanged).filter((entry) => !previous.has(entry)))
      : new Set();

    this.renderFiles(status, diffStat, flash);
  }

  async refreshBranchAndCommits() {
    const branch = await this.fetchJson(this.branchEndpoint);
    if (!branch) return;
    this.branch = branch;
    this.renderBranch(branch);

    const ahead = Number(branch?.aheadBehind?.ahead || 0);
    if (ahead <= 0) {
      this.renderCommits([]);
      return;
    }
    const commitLimit = Math.max(Math.min(ahead, 30), 5);
    const commits = await this.fetchJson(`${this.logEndpoint}?limit=${commitLimit}`);
    if (Array.isArray(commits)) {
      this.renderCommits(commits.slice(0, ahead));
    }
  }

  computeChangedFiles(status) {
    const key = (entry) => `${entry.status}:${entry.path}`;
    const tracked = [
      ...(status?.staged || []).map((entry) => key(entry)),
      ...(status?.unstaged || []).map((entry) => key(entry)),
      ...(status?.untracked || []).map((path) => `Untracked:${path}`),
    ];
    return new Set(tracked);
  }

  renderFiles(status, diffStat, flashEntries) {
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
    const untracked = (status?.untracked || []).map((path) => ({
      path,
      status: 'Untracked',
      group: 'Untracked',
      key: `Untracked:${path}`,
    }));

    const grouped = [
      { label: 'Staged', rows: staged },
      { label: 'Unstaged', rows: unstaged },
      { label: 'Untracked', rows: untracked },
    ];

    const uniquePaths = new Set([...staged, ...unstaged, ...untracked].map((entry) => entry.path));
    const insertions = (diffStat?.files || []).reduce((total, item) => total + Number(item.additions || 0), 0);
    const deletions = (diffStat?.files || []).reduce((total, item) => total + Number(item.deletions || 0), 0);

    if (this.summaryEl) {
      this.summaryEl.textContent = `${uniquePaths.size} files changed, ${insertions} insertions, ${deletions} deletions`;
    }

    if (!this.filesGroupsEl) return;

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
            const badge = this.renderStatusBadge(entry.status);
            const diffUrl = `${this.diffEndpoint}/${encodeURIComponent(entry.path)}`;
            return `<button type="button" data-role="file" data-file-path="${this.escapeAttr(entry.path)}" hx-get="${this.escapeAttr(diffUrl)}" hx-target="this" class="git-file-row${flashClass} w-full text-left rounded-md border border-white/10 bg-white/5 px-2 py-1.5 hover:bg-white/10">
              <div class="flex items-center justify-between gap-2">
                <div class="min-w-0 flex items-center gap-2">
                  ${badge}
                  <span class="truncate text-xs text-gray-100 font-mono">${this.escapeHtml(entry.path)}</span>
                </div>
                <span class="text-[11px] text-gray-400">+${additions} / -${deletions}</span>
              </div>
            </button>`;
          })
          .join('');
        return `<section><h4 class="text-xs font-semibold text-gray-300 mb-1">${group.label}</h4><div class="space-y-1">${rows}</div></section>`;
      })
      .join('');

    this.filesGroupsEl.innerHTML = renderedGroups;
  }

  renderStatusBadge(status) {
    const normalized = String(status || 'Untracked').toLowerCase();
    if (normalized === 'modified') return '<span class="inline-flex h-5 w-5 items-center justify-center rounded bg-blue-500/30 text-blue-200 text-[10px] font-bold">M</span>';
    if (normalized === 'added') return '<span class="inline-flex h-5 w-5 items-center justify-center rounded bg-emerald-500/30 text-emerald-200 text-[10px] font-bold">A</span>';
    if (normalized === 'deleted') return '<span class="inline-flex h-5 w-5 items-center justify-center rounded bg-rose-500/30 text-rose-200 text-[10px] font-bold">D</span>';
    return '<span class="inline-flex h-5 w-5 items-center justify-center rounded bg-slate-500/30 text-slate-200 text-[10px] font-bold">?</span>';
  }

  renderBranch(branchView) {
    const current = branchView?.branch?.current || 'unknown';
    const ahead = Number(branchView?.aheadBehind?.ahead || 0);
    const behind = Number(branchView?.aheadBehind?.behind || 0);
    const base = branchView?.baseBranch || 'main';

    if (this.branchCurrentEl) {
      const safeCurrent = this.escapeHtml(current);
      const safeCurrentAttr = this.escapeAttr(current);
      this.branchCurrentEl.innerHTML = `<div class="flex items-center gap-2">
        <span class="font-mono text-gray-100">${safeCurrent}</span>
        <button type="button" data-role="copy-branch" data-branch="${safeCurrentAttr}" class="rounded bg-white/10 px-2 py-0.5 text-[11px] text-gray-200 hover:bg-white/20">Copy</button>
      </div>`;
      const copyBtn = this.branchCurrentEl.querySelector('[data-role="copy-branch"]');
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
    if (this.aheadBehindEl) {
      this.aheadBehindEl.textContent = `${ahead} commits ahead of ${base}, ${behind} behind`;
    }
  }

  renderCommits(commits) {
    if (!this.commitLogEl) return;
    if (!commits.length) {
      this.commitLogEl.innerHTML = '<p class="text-xs text-gray-500">No commits ahead of main.</p>';
      return;
    }

    this.commitLogEl.innerHTML = commits
      .map((entry) => {
        const hash = this.escapeHtml(entry.shortHash || '').slice(0, 12);
        const message = this.truncate(this.escapeHtml(entry.message || ''), 74);
        const author = this.escapeHtml(entry.author || 'unknown');
        const relative = this.relativeTime(entry.date);
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

  toggleDiff(path) {
    if (!this.diffViewerEl || !this.diffTitleEl || !this.diffContentEl) return;
    if (this.currentDiffPath === path && !this.diffViewerEl.classList.contains('hidden')) {
      this.diffViewerEl.classList.add('hidden');
      this.currentDiffPath = null;
      return;
    }

    this.currentDiffPath = path;
    this.diffViewerEl.classList.remove('hidden');
    this.diffTitleEl.textContent = path;
    this.diffContentEl.textContent = 'Loading diff...';

    const url = `${this.diffEndpoint}/${encodeURIComponent(path)}`;
    if (window.htmx?.ajax) {
      Promise.resolve(window.htmx.ajax('GET', url, {
        target: this.diffContentEl,
        swap: 'innerHTML',
      })).then(() => {
        this.renderDiffText(this.diffContentEl.textContent || '');
      });
    } else {
      fetch(url)
        .then((response) => response.ok ? response.text() : Promise.reject(new Error('Failed to load diff')))
        .then((text) => {
          this.renderDiffText(text);
        })
        .catch(() => {
          this.diffContentEl.textContent = 'Unable to load diff.';
        });
    }
  }

  renderDiffText(rawDiff) {
    if (!this.diffContentEl) return;
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
          return `<div class="git-diff-line"><span class="line-no"></span><span class="text-amber-200">${this.escapeHtml(line)}</span></div>`;
        }

        let cls = 'git-diff-line';
        let displayNo = '';
        if (line.startsWith('+') && !line.startsWith('+++')) {
          cls += ' added';
          displayNo = String(newNo || '');
          newNo += 1;
        } else if (line.startsWith('-') && !line.startsWith('---')) {
          cls += ' deleted';
          displayNo = String(oldNo || '');
          oldNo += 1;
        } else {
          if (!line.startsWith('diff --git') && !line.startsWith('index ') && !line.startsWith('---') && !line.startsWith('+++')) {
            displayNo = oldNo > 0 ? String(oldNo) : '';
            oldNo += oldNo > 0 ? 1 : 0;
            newNo += newNo > 0 ? 1 : 0;
          }
        }

        return `<div class="${cls}"><span class="line-no">${displayNo}</span><span>${this.escapeHtml(line)}</span></div>`;
      })
      .join('');

    this.diffContentEl.innerHTML = html;
  }

  connectWs() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    this.ws = new WebSocket(`${protocol}//${window.location.host}/ws/console`);
    this.ws.onopen = () => {
      this.ws?.send(JSON.stringify({ Subscribe: { topic: this.topic, params: {} } }));
    };
    this.ws.onmessage = (event) => this.onWsMessage(event.data);
  }

  disconnectWs() {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ Unsubscribe: { topic: this.topic } }));
      this.ws.close();
    }
  }

  onWsMessage(raw) {
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch (_ignored) {
      return;
    }

    const event = parsed?.Event;
    if (!event || event.topic !== this.topic) return;

    if (event.eventType === 'git-status-update') {
      this.refreshFiles(true);
      this.refreshBranchAndCommits();
      return;
    }

    if (event.eventType === 'git-new-commit') {
      this.refreshBranchAndCommits();
    }
  }

  async fetchJson(url) {
    if (!url) return null;
    try {
      const response = await fetch(url, { headers: { Accept: 'application/json' } });
      if (!response.ok) return null;
      return await response.json();
    } catch (_ignored) {
      return null;
    }
  }

  truncate(text, max) {
    if (text.length <= max) return text;
    return `${text.slice(0, Math.max(0, max - 3))}...`;
  }

  relativeTime(isoTimestamp) {
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

  escapeHtml(value) {
    return String(value || '')
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  }

  escapeAttr(value) {
    return this.escapeHtml(value);
  }
}

document.querySelectorAll('[data-role="git-panel"]').forEach((root) => {
  if (!root.__gitPanel) {
    root.__gitPanel = new GitPanel(root);
  }
});
