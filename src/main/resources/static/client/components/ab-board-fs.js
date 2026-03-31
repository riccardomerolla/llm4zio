import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

class AbBoardFs extends LitElement {
  static properties = {
    workspaceId:   { type: String, attribute: 'data-workspace-id' },
    workspacePath: { type: String, attribute: 'data-workspace-path' },
    fragmentUrl:   { type: String, attribute: 'data-fragment-url' },
  };

  constructor() {
    super();
    this.workspaceId   = '';
    this.workspacePath = '';
    this.fragmentUrl   = '';
    this._dragIssueId  = null;
    this._ws           = null;
    this._dragBound    = false;
    this._wsBound      = false;
  }

  createRenderRoot() { return this; }

  connectedCallback() {
    super.connectedCallback();
    if (!this.workspaceId || !this.fragmentUrl) return;
    this._bindDragDrop();
    this._bindDeleteButtons();
    this._bindCreateForm();
    this._bindDispatchButton();
    this._bindWs();

    this.addEventListener('htmx:afterSwap', (event) => {
      if (event.target === this) {
        this._dragBound = false;
        this._bindDragDrop();
        this._bindDeleteButtons();
        this._bindCreateForm();
        this._bindDispatchButton();
      }
    });
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    if (this._ws && this._ws.readyState === WebSocket.OPEN) {
      this._ws.close();
    }
    this._ws     = null;
    this._wsBound = false;
    this._dragBound = false;
  }

  render() { return html``; }

  _bindDragDrop() {
    if (this._dragBound) return;
    this._dragBound = true;

    this.addEventListener('dragstart', (event) => {
      const card = event.target?.closest?.('[data-board-issue-id]');
      if (!card) return;
      this._dragIssueId = card.dataset.boardIssueId || null;
      event.dataTransfer?.setData('text/plain', this._dragIssueId || '');
      event.dataTransfer.effectAllowed = 'move';
    });

    this.addEventListener('dragover', (event) => {
      const column = event.target?.closest?.('[data-column-drop]')
                  || event.target?.closest?.('ab-board-column[data-drop-status]');
      if (!column || !this._dragIssueId) return;
      event.preventDefault();
      event.dataTransfer.dropEffect = 'move';
    });

    this.addEventListener('drop', async (event) => {
      const column = event.target?.closest?.('[data-column-drop]')
                  || event.target?.closest?.('ab-board-column[data-drop-status]');
      if (!column) return;
      event.preventDefault();
      const issueId = this._dragIssueId || event.dataTransfer?.getData('text/plain') || '';
      const toColumn = column.dataset.columnDrop || column.dataset.dropStatus || '';
      this._dragIssueId = null;
      if (!issueId || !toColumn) return;
      await this._moveIssue(issueId, toColumn);
    });

    this.addEventListener('dragend', () => {
      this._dragIssueId = null;
    });
  }

  _bindDeleteButtons() {
    this.querySelectorAll('[data-board-delete]').forEach((btn) => {
      if (btn.dataset.boundDelete === 'true') return;
      btn.dataset.boundDelete = 'true';
      btn.addEventListener('click', async () => {
        const issueId = btn.dataset.boardDelete;
        if (!issueId) return;
        const ok = window.confirm(`Delete issue ${issueId}?`);
        if (!ok) return;
        await this._deleteIssue(issueId);
      });
    });
  }

  _bindWs() {
    if (this._wsBound) return;
    this._wsBound = true;

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    this._ws = new WebSocket(`${protocol}//${window.location.host}/ws/console`);

    this._ws.addEventListener('open', () => {
      this._ws?.send(JSON.stringify({ Subscribe: { topic: 'activity:feed', params: {} } }));
    });

    this._ws.addEventListener('message', (msg) => {
      try {
        const parsed = JSON.parse(msg.data);
        const event = parsed?.Event;
        if (!event || event.topic !== 'activity:feed') return;
        const payload = JSON.parse(event.payload || '{}');
        if (payload?.source !== 'git-watcher-board') return;
        const details = typeof payload.payload === 'string' ? JSON.parse(payload.payload || '{}') : (payload.payload || {});
        const eventPath = details.worktreePath || '';
        if (!eventPath || eventPath !== this.workspacePath) return;
        this._refreshBoard();
      } catch (_) {
        // ignore malformed websocket payloads
      }
    });

    this._ws.addEventListener('close', () => {
      setTimeout(() => {
        this._wsBound = false;
        this._bindWs();
      }, 1000);
    });
  }

  async _moveIssue(issueId, toColumn) {
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
      this._refreshBoard();
    } catch (_) {
      // ignore and let periodic refresh reconcile UI
    }
  }

  async _deleteIssue(issueId) {
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
      this._refreshBoard();
    } catch (_) {
      // ignore and let periodic refresh reconcile UI
    }
  }

  _bindCreateForm() {
    const form = this.closest('main')?.querySelector?.('[data-board-create]');
    if (!form || form.dataset.boundCreate === 'true') return;
    form.dataset.boundCreate = 'true';
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      const title = String(form.querySelector('[name="title"]')?.value || '').trim();
      const body = String(form.querySelector('[name="body"]')?.value || '').trim();
      if (!title || !body) return;
      await this._createIssue({ title, body, column: 'backlog', priority: 'medium' });
      form.reset();
    });
  }

  _bindDispatchButton() {
    const button = this.closest('main')?.querySelector?.('[data-board-dispatch]');
    if (!button || button.dataset.boundDispatch === 'true') return;
    button.dataset.boundDispatch = 'true';
    button.addEventListener('click', async () => {
      await this._dispatchCycle();
    });
  }

  async _createIssue(payload) {
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
      this._refreshBoard();
    } catch (_) {
      // ignore and let periodic refresh reconcile UI
    }
  }

  async _dispatchCycle() {
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
      this._refreshBoard();
    } catch (_) {
      // ignore and let periodic refresh reconcile UI
    }
  }

  _refreshBoard() {
    if (!window.htmx) return;
    window.htmx.ajax('GET', this.fragmentUrl, { target: this, swap: 'innerHTML' });
  }
}

if (!customElements.get('ab-board-fs')) {
  customElements.define('ab-board-fs', AbBoardFs);
}
