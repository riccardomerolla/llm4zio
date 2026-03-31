import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

class AbRunSessionControls extends LitElement {
  static properties = {
    runId:          { type: String, attribute: 'data-run-id' },
    workspaceId:    { type: String, attribute: 'data-workspace-id' },
    conversationId: { type: String, attribute: 'data-conversation-id' },
    status:         { type: String, attribute: 'data-status' },
    isAttached:     { type: String, attribute: 'data-is-attached' },
    attachedCount:  { type: String, attribute: 'data-attached-count' },
  };

  constructor() {
    super();
    this.runId          = '';
    this.workspaceId    = '';
    this.conversationId = '';
    this.status         = 'pending';
    this.isAttached     = 'false';
    this.attachedCount  = '0';

    this._state            = 'pending';
    this._isAttached       = false;
    this._attachedCount    = 0;
    this._ws               = null;
    this._isContinuationMode = false;

    this._attachBtn    = null;
    this._detachBtn    = null;
    this._interruptBtn = null;
    this._continueBtn  = null;
    this._cancelBtn    = null;
    this._form         = null;
    this._input        = null;
    this._sendBtn      = null;
    this._feedback     = null;

    this._modeBadge       = null;
    this._attachedCounter = null;
    this._activeIndicator = null;
  }

  createRenderRoot() { return this; }

  connectedCallback() {
    super.connectedCallback();
    this._state         = this.status || 'pending';
    this._isAttached    = (this.isAttached || 'false') === 'true';
    this._attachedCount = Number(this.attachedCount || '0');

    this._attachBtn    = this.querySelector('[data-role="attach"]');
    this._detachBtn    = this.querySelector('[data-role="detach"]');
    this._interruptBtn = this.querySelector('[data-role="interrupt"]');
    this._continueBtn  = this.querySelector('[data-role="continue"]');
    this._cancelBtn    = this.querySelector('[data-role="cancel"]');
    this._form         = this.querySelector('[data-role="run-form"]');
    this._input        = this.querySelector('[data-role="run-input"]');
    this._sendBtn      = this.querySelector('[data-role="send"]');
    this._feedback     = this.querySelector('[data-role="feedback"]');

    this._modeBadge       = document.getElementById(`run-mode-badge-${this.conversationId}`);
    this._attachedCounter = document.getElementById(`run-attached-count-${this.conversationId}`)?.querySelector('[data-role="count"]');
    this._activeIndicator = document.getElementById(`run-active-indicator-${this.conversationId}`);

    this._bind();
    this._connectWs();
    this._render();
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    if (this._ws) {
      this._ws.close();
      this._ws = null;
    }
  }

  render() { return html``; }

  _bind() {
    this._attachBtn?.addEventListener('click', () => this._send({ AttachToRun: { runId: this.runId } }));
    this._detachBtn?.addEventListener('click', () => this._send({ DetachFromRun: { runId: this.runId } }));
    this._interruptBtn?.addEventListener('click', () => this._send({ InterruptRun: { runId: this.runId } }));
    this._continueBtn?.addEventListener('click', () => {
      this._isContinuationMode = true;
      this._render();
      this._feedbackText('Continue mode active.');
      if (this._input) {
        this._input.focus();
        this._input.setSelectionRange(this._input.value.length, this._input.value.length);
      }
    });
    this._cancelBtn?.addEventListener('click', async () => {
      if (!window.confirm(`Cancel run ${this.runId}?`)) return;
      const resp = await fetch(`/api/workspaces/${this.workspaceId}/runs/${this.runId}`, { method: 'DELETE' });
      if (resp.ok) {
        this._state = 'cancelled';
        this._isAttached = false;
        this._feedbackText('Run cancelled.');
        this._addSystemMessage('Run cancelled by user.');
        this._render();
      } else {
        this._feedbackText('Unable to cancel run.');
      }
    });

    this._form?.addEventListener('submit', (event) => {
      event.preventDefault();
      const content = (this._input?.value || '').trim();
      if (!content) return;
      if (this._isContinuationMode) {
        this._send({ ContinueRun: { runId: this.runId, prompt: content } });
        this._isContinuationMode = false;
        if (this._input) this._input.value = '';
      } else {
        this._send({ SendRunMessage: { runId: this.runId, content } });
      }
    });

    this._input?.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        this._form?.requestSubmit();
      }
    });
  }

  _connectWs() {
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    this._ws = new WebSocket(`${protocol}//${location.host}/ws/console`);
    this._ws.onopen = () => {
      if (this._isAutoAttachRequested() && !this._isAttached) {
        this._send({ AttachToRun: { runId: this.runId } });
      }
    };
    this._ws.onmessage = (event) => this._onMessage(event.data);
  }

  _isAutoAttachRequested() {
    const params = new URLSearchParams(window.location.search || '');
    const attachFlag = (params.get('attach') || '').trim();
    const requestedRunId = (params.get('runId') || '').trim();
    return attachFlag === '1' && requestedRunId === this.runId;
  }

  _send(payload) {
    if (!this._ws || this._ws.readyState !== WebSocket.OPEN) {
      this._feedbackText('WebSocket not connected.');
      return;
    }
    this._ws.send(JSON.stringify(payload));
  }

  _onMessage(raw) {
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch (_ignored) {
      return;
    }

    if (parsed.RunStateChanged && parsed.RunStateChanged.runId === this.runId) {
      const next = this._parseState(parsed.RunStateChanged.newState);
      this._state = next;
      if (next === 'running:interactive') {
        if (!this._isAttached) this._attachedCount += 1;
        this._isAttached = true;
      } else if (next === 'running:autonomous' || next === 'cancelled' || next === 'completed' || next === 'failed') {
        if (this._isAttached && this._attachedCount > 0) this._attachedCount -= 1;
        this._isAttached = false;
      }
      this._feedbackText(`State changed: ${this._humanState(next)}.`);
      this._addSystemMessage(`Run state changed to ${this._humanState(next)}.`);
      if (next !== 'running:paused') {
        this._isContinuationMode = false;
      }
      this._render();
      return;
    }

    if (parsed.RunInputAccepted && parsed.RunInputAccepted.runId === this.runId) {
      if (this._input) this._input.value = '';
      this._feedbackText('Message accepted.');
      this._addSystemMessage('Message sent to active run.');
      return;
    }

    if (parsed.RunInputRejected && parsed.RunInputRejected.runId === this.runId) {
      this._feedbackText(parsed.RunInputRejected.reason || 'Message rejected.');
      return;
    }
  }

  _parseState(raw) {
    const value = String(raw || '').toLowerCase();
    if (value.includes('running(interactive)')) return 'running:interactive';
    if (value.includes('running(paused)')) return 'running:paused';
    if (value.includes('running(autonomous)')) return 'running:autonomous';
    if (value.includes('pending')) return 'pending';
    if (value.includes('completed')) return 'completed';
    if (value.includes('failed')) return 'failed';
    if (value.includes('cancelled')) return 'cancelled';
    return this._state;
  }

  _humanState(state) {
    switch (state) {
      case 'running:autonomous': return 'Autonomous';
      case 'running:interactive': return 'Interactive';
      case 'running:paused': return 'Paused';
      case 'pending': return 'Pending';
      case 'completed': return 'Completed';
      case 'failed': return 'Failed';
      case 'cancelled': return 'Cancelled';
      default: return state;
    }
  }

  _badgeClass(state) {
    switch (state) {
      case 'running:autonomous': return 'border-blue-400/40 bg-blue-500/20 text-blue-200';
      case 'running:interactive': return 'border-emerald-400/40 bg-emerald-500/20 text-emerald-200';
      case 'running:paused': return 'border-amber-400/40 bg-amber-500/20 text-amber-200';
      case 'completed': return 'border-emerald-400/40 bg-emerald-500/20 text-emerald-200';
      case 'failed': return 'border-rose-400/40 bg-rose-500/20 text-rose-200';
      case 'cancelled': return 'border-orange-400/40 bg-orange-500/20 text-orange-200';
      default: return 'border-slate-400/40 bg-slate-500/20 text-slate-200';
    }
  }

  _feedbackText(message) {
    if (this._feedback) this._feedback.textContent = message;
  }

  _addSystemMessage(message) {
    const stream = document.getElementById(`messages-${this.conversationId}`);
    if (!stream) return;
    const row = document.createElement('div');
    row.className = 'flex justify-center';
    row.setAttribute('data-sender', 'system');
    const bubble = document.createElement('div');
    bubble.className = 'max-w-[90%] rounded-lg border border-amber-300/25 bg-amber-500/15 px-4 py-2 text-xs text-amber-100';
    bubble.textContent = message;
    row.appendChild(bubble);
    stream.appendChild(row);
    stream.scrollToLatest?.();
  }

  _render() {
    const interactive = this._state === 'running:interactive' || this._state === 'running:paused';
    const canContinue = this._state === 'running:paused';
    const isRunning = this._state.startsWith('running:');
    const canAttach = isRunning;
    const canCancel = this._state === 'pending' || isRunning;

    if (!canContinue) this._isContinuationMode = false;

    const inputEnabled = (interactive && this._isAttached) || (canContinue && this._isContinuationMode);
    const inputPlaceholder = this._isContinuationMode
      ? 'Enter continuation instructions...'
      : (inputEnabled ? 'Send instructions to the active run...' : 'Attach to interact');
    const inputTitle = this._isContinuationMode
      ? 'Send continuation instructions to start a new continuation run'
      : (inputEnabled ? 'Send follow-up instructions to this run' : 'Attach to interact');

    this._attachBtn?.classList.toggle('hidden', !(canAttach && !this._isAttached));
    this._detachBtn?.classList.toggle('hidden', !(canAttach && this._isAttached));
    this._interruptBtn?.classList.toggle('hidden', !(this._isAttached && isRunning));
    this._continueBtn?.classList.toggle('hidden', !canContinue);
    this._cancelBtn?.classList.toggle('hidden', !canCancel);

    if (this._input) {
      this._input.disabled = !inputEnabled;
      this._input.placeholder = inputPlaceholder;
      this._input.title = inputTitle;
    }
    if (this._sendBtn) this._sendBtn.disabled = !inputEnabled;

    if (this._modeBadge) {
      this._modeBadge.textContent = this._humanState(this._state);
      this._modeBadge.className = `inline-flex items-center rounded-md border px-3 py-1.5 font-semibold ${this._badgeClass(this._state)}`;
    }
    if (this._attachedCounter) this._attachedCounter.textContent = String(Math.max(0, this._attachedCount));
    if (this._activeIndicator) this._activeIndicator.classList.toggle('hidden', !(isRunning || this._state === 'pending'));
  }
}

if (!customElements.get('ab-run-session-controls')) {
  customElements.define('ab-run-session-controls', AbRunSessionControls);
}
