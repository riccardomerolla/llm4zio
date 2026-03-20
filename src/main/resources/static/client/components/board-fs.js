class BoardFs {
  constructor(root) {
    this.root = root;
    this.workspaceId = root?.dataset?.workspaceId || '';
    this.workspacePath = root?.dataset?.workspacePath || '';
    this.fragmentUrl = root?.dataset?.fragmentUrl || '';
    this.dragIssueId = null;
    this.ws = null;
    this._dragBound = false;
    this._wsBound = false;
  }

  init() {
    if (!this.root || !this.workspaceId || !this.fragmentUrl) return;
    this.bindDragDrop();
    this.bindDeleteButtons();
    this.bindCreateForm();
    this.bindDispatchButton();
    this.bindWs();

    this.root.addEventListener('htmx:afterSwap', (event) => {
      if (event.target === this.root) {
        this.bindDragDrop();
        this.bindDeleteButtons();
        this.bindCreateForm();
        this.bindDispatchButton();
      }
    });
  }

  bindDragDrop() {
    if (this._dragBound) return;
    this._dragBound = true;

    this.root.addEventListener('dragstart', (event) => {
      const card = event.target?.closest?.('[data-board-issue-id]');
      if (!card) return;
      this.dragIssueId = card.dataset.boardIssueId || null;
      event.dataTransfer?.setData('text/plain', this.dragIssueId || '');
      event.dataTransfer.effectAllowed = 'move';
    });

    this.root.addEventListener('dragover', (event) => {
      const column = event.target?.closest?.('[data-column-drop]');
      if (!column || !this.dragIssueId) return;
      event.preventDefault();
      event.dataTransfer.dropEffect = 'move';
    });

    this.root.addEventListener('drop', async (event) => {
      const column = event.target?.closest?.('[data-column-drop]');
      if (!column) return;
      event.preventDefault();
      const issueId = this.dragIssueId || event.dataTransfer?.getData('text/plain') || '';
      const toColumn = column.dataset.columnDrop || '';
      this.dragIssueId = null;
      if (!issueId || !toColumn) return;
      await this.moveIssue(issueId, toColumn);
    });

    this.root.addEventListener('dragend', () => {
      this.dragIssueId = null;
    });
  }

  bindDeleteButtons() {
    this.root.querySelectorAll('[data-board-delete]').forEach((btn) => {
      if (btn.dataset.boundDelete === 'true') return;
      btn.dataset.boundDelete = 'true';
      btn.addEventListener('click', async () => {
        const issueId = btn.dataset.boardDelete;
        if (!issueId) return;
        const ok = window.confirm(`Delete issue ${issueId}?`);
        if (!ok) return;
        await this.deleteIssue(issueId);
      });
    });
  }

  bindWs() {
    if (this._wsBound) return;
    this._wsBound = true;

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    this.ws = new WebSocket(`${protocol}//${window.location.host}/ws/console`);

    this.ws.addEventListener('open', () => {
      this.ws?.send(JSON.stringify({ Subscribe: { topic: 'activity:feed', params: {} } }));
    });

    this.ws.addEventListener('message', (msg) => {
      try {
        const parsed = JSON.parse(msg.data);
        const event = parsed?.Event;
        if (!event || event.topic !== 'activity:feed') return;
        const payload = JSON.parse(event.payload || '{}');
        if (payload?.source !== 'git-watcher-board') return;
        const details = typeof payload.payload === 'string' ? JSON.parse(payload.payload || '{}') : (payload.payload || {});
        const eventPath = details.worktreePath || '';
        if (!eventPath || eventPath !== this.workspacePath) return;
        this.refreshBoard();
      } catch (_) {
        // ignore malformed websocket payloads
      }
    });

    this.ws.addEventListener('close', () => {
      setTimeout(() => {
        this._wsBound = false;
        this.bindWs();
      }, 1000);
    });
  }

  async moveIssue(issueId, toColumn) {
    try {
      const response = await fetch(`/board/${encodeURIComponent(this.workspaceId)}/issues/${encodeURIComponent(issueId)}/move`, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'HX-Request': 'true',
        },
        body: JSON.stringify({ toColumn }),
      });
      if (!response.ok) {
        return;
      }
      this.refreshBoard();
    } catch (_) {
      // ignore and let periodic refresh reconcile UI
    }
  }

  async deleteIssue(issueId) {
    try {
      const response = await fetch(`/board/${encodeURIComponent(this.workspaceId)}/issues/${encodeURIComponent(issueId)}`, {
        method: 'DELETE',
        headers: {
          'HX-Request': 'true',
        },
      });
      if (!response.ok) {
        return;
      }
      this.refreshBoard();
    } catch (_) {
      // ignore and let periodic refresh reconcile UI
    }
  }

  bindCreateForm() {
    const form = this.root.closest('main')?.querySelector?.('[data-board-create]');
    if (!form || form.dataset.boundCreate === 'true') return;
    form.dataset.boundCreate = 'true';
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      const title = String(form.querySelector('[name="title"]')?.value || '').trim();
      const body = String(form.querySelector('[name="body"]')?.value || '').trim();
      if (!title || !body) return;
      await this.createIssue({ title, body, column: 'backlog', priority: 'medium' });
      form.reset();
    });
  }

  bindDispatchButton() {
    const button = this.root.closest('main')?.querySelector?.('[data-board-dispatch]');
    if (!button || button.dataset.boundDispatch === 'true') return;
    button.dataset.boundDispatch = 'true';
    button.addEventListener('click', async () => {
      await this.dispatchCycle();
    });
  }

  async createIssue(payload) {
    try {
      const response = await fetch(`/board/${encodeURIComponent(this.workspaceId)}/issues`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'HX-Request': 'true',
        },
        body: JSON.stringify(payload),
      });
      if (!response.ok) return;
      this.refreshBoard();
    } catch (_) {
      // ignore and let periodic refresh reconcile UI
    }
  }

  async dispatchCycle() {
    try {
      const response = await fetch(`/board/${encodeURIComponent(this.workspaceId)}/dispatch`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'HX-Request': 'true',
        },
        body: '{}',
      });
      if (!response.ok) return;
      this.refreshBoard();
    } catch (_) {
      // ignore and let periodic refresh reconcile UI
    }
  }

  refreshBoard() {
    if (!window.htmx || !this.root) return;
    window.htmx.ajax('GET', this.fragmentUrl, { target: this.root, swap: 'innerHTML' });
  }
}

document.querySelectorAll('#fs-board-root').forEach((root) => {
  if (!root.__boardFs) {
    root.__boardFs = new BoardFs(root);
    root.__boardFs.init();
  }
});
