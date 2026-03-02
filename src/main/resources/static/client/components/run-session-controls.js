class RunSessionControls {
  constructor(root) {
    this.root = root;
    this.runId = root?.dataset?.runId || '';
    this.workspaceId = root?.dataset?.workspaceId || '';
    this.conversationId = root?.dataset?.conversationId || '';
    this.state = root?.dataset?.status || 'pending';
    this.isAttached = (root?.dataset?.isAttached || 'false') === 'true';
    this.attachedCount = Number(root?.dataset?.attachedCount || '0');
    this.ws = null;

    this.attachBtn = root.querySelector('[data-role="attach"]');
    this.detachBtn = root.querySelector('[data-role="detach"]');
    this.interruptBtn = root.querySelector('[data-role="interrupt"]');
    this.continueBtn = root.querySelector('[data-role="continue"]');
    this.cancelBtn = root.querySelector('[data-role="cancel"]');
    this.form = root.querySelector('[data-role="run-form"]');
    this.input = root.querySelector('[data-role="run-input"]');
    this.sendBtn = root.querySelector('[data-role="send"]');
    this.feedback = root.querySelector('[data-role="feedback"]');

    this.modeBadge = document.getElementById(`run-mode-badge-${this.conversationId}`);
    this.attachedCounter = document.getElementById(`run-attached-count-${this.conversationId}`)?.querySelector('[data-role="count"]');
    this.activeIndicator = document.getElementById(`run-active-indicator-${this.conversationId}`);

    this.bind();
    this.connectWs();
    this.render();
  }

  bind() {
    this.attachBtn?.addEventListener('click', () => this.send({ AttachToRun: { runId: this.runId } }));
    this.detachBtn?.addEventListener('click', () => this.send({ DetachFromRun: { runId: this.runId } }));
    this.interruptBtn?.addEventListener('click', () => this.send({ InterruptRun: { runId: this.runId } }));
    this.continueBtn?.addEventListener('click', () => {
      const prompt = (window.prompt(`Continue instructions for run ${this.runId}`) || '').trim();
      if (!prompt) return;
      this.send({ ContinueRun: { runId: this.runId, prompt } });
    });
    this.cancelBtn?.addEventListener('click', async () => {
      if (!window.confirm(`Cancel run ${this.runId}?`)) return;
      const resp = await fetch(`/api/workspaces/${this.workspaceId}/runs/${this.runId}`, { method: 'DELETE' });
      if (resp.ok) {
        this.state = 'cancelled';
        this.isAttached = false;
        this.feedbackText('Run cancelled.');
        this.addSystemMessage('Run cancelled by user.');
        this.render();
      } else {
        this.feedbackText('Unable to cancel run.');
      }
    });

    this.form?.addEventListener('submit', (event) => {
      event.preventDefault();
      const content = (this.input?.value || '').trim();
      if (!content) return;
      this.send({ SendRunMessage: { runId: this.runId, content } });
    });

    this.input?.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        this.form?.requestSubmit();
      }
    });
  }

  connectWs() {
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    this.ws = new WebSocket(`${protocol}//${location.host}/ws/console`);
    this.ws.onmessage = (event) => this.onMessage(event.data);
  }

  send(payload) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      this.feedbackText('WebSocket not connected.');
      return;
    }
    this.ws.send(JSON.stringify(payload));
  }

  onMessage(raw) {
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch (_ignored) {
      return;
    }

    if (parsed.RunStateChanged && parsed.RunStateChanged.runId === this.runId) {
      const next = this.parseState(parsed.RunStateChanged.newState);
      this.state = next;
      if (next === 'running:interactive') {
        if (!this.isAttached) this.attachedCount += 1;
        this.isAttached = true;
      } else if (next === 'running:autonomous' || next === 'cancelled' || next === 'completed' || next === 'failed') {
        if (this.isAttached && this.attachedCount > 0) this.attachedCount -= 1;
        this.isAttached = false;
      }
      this.feedbackText(`State changed: ${this.humanState(next)}.`);
      this.addSystemMessage(`Run state changed to ${this.humanState(next)}.`);
      this.render();
      return;
    }

    if (parsed.RunInputAccepted && parsed.RunInputAccepted.runId === this.runId) {
      if (this.input) this.input.value = '';
      this.feedbackText('Message accepted.');
      this.addSystemMessage('Message sent to active run.');
      return;
    }

    if (parsed.RunInputRejected && parsed.RunInputRejected.runId === this.runId) {
      this.feedbackText(parsed.RunInputRejected.reason || 'Message rejected.');
      return;
    }
  }

  parseState(raw) {
    const value = String(raw || '').toLowerCase();
    if (value.includes('running(interactive)')) return 'running:interactive';
    if (value.includes('running(paused)')) return 'running:paused';
    if (value.includes('running(autonomous)')) return 'running:autonomous';
    if (value.includes('pending')) return 'pending';
    if (value.includes('completed')) return 'completed';
    if (value.includes('failed')) return 'failed';
    if (value.includes('cancelled')) return 'cancelled';
    return this.state;
  }

  humanState(state) {
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

  badgeClass(state) {
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

  feedbackText(message) {
    if (this.feedback) this.feedback.textContent = message;
  }

  addSystemMessage(message) {
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

  render() {
    const interactive = this.state === 'running:interactive' || this.state === 'running:paused';
    const canContinue = this.state === 'running:paused' || this.state === 'completed' || this.state === 'failed' || this.state === 'cancelled';
    const isRunning = this.state.startsWith('running:');

    this.attachBtn?.classList.toggle('hidden', this.isAttached);
    this.detachBtn?.classList.toggle('hidden', !this.isAttached);
    this.interruptBtn?.classList.toggle('hidden', !(this.isAttached && isRunning));
    this.continueBtn?.classList.toggle('hidden', !canContinue);

    if (this.input) {
      this.input.disabled = !(interactive && this.isAttached);
      this.input.placeholder = this.input.disabled ? 'Attach to interact' : 'Send instructions to the active run...';
      this.input.title = this.input.disabled ? 'Attach to interact' : 'Send follow-up instructions to this run';
    }
    if (this.sendBtn) this.sendBtn.disabled = !(interactive && this.isAttached);

    if (this.modeBadge) {
      this.modeBadge.textContent = this.humanState(this.state);
      this.modeBadge.className = `inline-flex items-center rounded-md border px-3 py-1.5 font-semibold ${this.badgeClass(this.state)}`;
    }
    if (this.attachedCounter) this.attachedCounter.textContent = String(Math.max(0, this.attachedCount));
    if (this.activeIndicator) this.activeIndicator.classList.toggle('hidden', !(isRunning || this.state === 'pending'));
  }
}

document.querySelectorAll('[id^="run-session-controls-"]').forEach((el) => {
  if (!el.__runSessionControls) el.__runSessionControls = new RunSessionControls(el);
});
