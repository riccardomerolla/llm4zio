import { LitElement, html, css } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

class AbA2aPanel extends LitElement {
  createRenderRoot() { return this; }

  static properties = {
    conversationId: { type: String, attribute: 'conversation-id' },
    messages: { type: Array, state: true },
    concluded: { type: Boolean, state: true },
    outcomeSummary: { type: String, state: true },
    outcomeType: { type: String, state: true },
  };

  constructor() {
    super();
    this.messages = [];
    this.concluded = false;
    this.outcomeSummary = '';
    this.outcomeType = '';
    this._eventSource = null;
  }

  connectedCallback() {
    super.connectedCallback();
    if (this.conversationId) {
      this._connect();
    }
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this._disconnect();
  }

  _connect() {
    this._eventSource = new EventSource(`/api/dialogues/${this.conversationId}/sse`);

    this._eventSource.addEventListener('message-posted', (e) => {
      const data = JSON.parse(e.data);
      this.messages = [...this.messages, {
        sender: data.sender,
        senderRole: data.senderRole,
        content: data.content,
        turnNumber: data.turnNumber,
        occurredAt: data.occurredAt,
      }];
    });

    this._eventSource.addEventListener('human-intervened', (e) => {
      const data = JSON.parse(e.data);
      this.messages = [...this.messages, {
        sender: data.userId,
        senderRole: 'Human',
        content: '[Human intervention]',
        turnNumber: -1,
        occurredAt: data.occurredAt,
      }];
    });

    this._eventSource.addEventListener('dialogue-concluded', (e) => {
      const data = JSON.parse(e.data);
      this.concluded = true;
      this.outcomeType = Object.keys(data.outcome)[0] || 'Completed';
      this.outcomeSummary = Object.values(data.outcome)[0]?.summary ||
                            Object.values(data.outcome)[0] || '';
      this._disconnect();
    });
  }

  _disconnect() {
    if (this._eventSource) {
      this._eventSource.close();
      this._eventSource = null;
    }
  }

  _roleBadgeClass(role) {
    const map = {
      'Author': 'border-cyan-400/30 bg-cyan-500/10 text-cyan-200',
      'Reviewer': 'border-violet-400/30 bg-violet-500/10 text-violet-200',
      'Human': 'border-amber-400/30 bg-amber-500/10 text-amber-200',
    };
    return map[role] || 'border-slate-400/30 bg-slate-500/10 text-slate-200';
  }

  _outcomeBannerClass() {
    const map = {
      'Approved': 'border-emerald-400/20 bg-emerald-500/10 text-emerald-200',
      'ChangesRequested': 'border-amber-400/20 bg-amber-500/10 text-amber-200',
      'Escalated': 'border-red-400/20 bg-red-500/10 text-red-200',
    };
    return map[this.outcomeType] || 'border-blue-400/20 bg-blue-500/10 text-blue-200';
  }

  _handleIntervene() {
    const input = this.querySelector('#a2a-human-input');
    if (!input || !input.value.trim()) return;
    const message = input.value.trim();
    input.value = '';

    fetch(`/api/dialogues/${this.conversationId}/intervene`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message }),
    });
  }

  render() {
    if (this.messages.length === 0 && !this.concluded) {
      return html`
        <div class="rounded-lg border border-dashed border-white/10 bg-white/[0.03] px-4 py-6 text-center text-sm text-slate-400">
          Waiting for dialogue to begin...
        </div>
      `;
    }

    return html`
      <div class="space-y-3">
        ${this.messages.map(msg => html`
          <div class="rounded-lg border border-white/5 bg-white/[0.03] px-3 py-2">
            <div class="mb-1 flex items-center gap-2">
              <span class="rounded-full border px-2 py-0.5 text-[10px] font-semibold ${this._roleBadgeClass(msg.senderRole)}">
                ${msg.senderRole}
              </span>
              <span class="text-xs font-medium text-slate-200">${msg.sender}</span>
              <span class="ml-auto text-[10px] text-slate-500">${msg.occurredAt}</span>
            </div>
            <p class="whitespace-pre-wrap text-sm leading-6 text-slate-300">${msg.content}</p>
          </div>
        `)}

        ${this.concluded ? html`
          <div class="rounded-lg border px-4 py-3 text-sm font-medium ${this._outcomeBannerClass()}">
            ${this.outcomeType}: ${this.outcomeSummary}
          </div>
        ` : html`
          <div class="flex gap-2">
            <input id="a2a-human-input" type="text" placeholder="Intervene in the dialogue..."
              class="flex-1 rounded-lg border border-white/10 bg-slate-800/70 px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-indigo-400/50" />
            <button @click=${() => this._handleIntervene()}
              class="rounded-md bg-amber-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-amber-500">
              Intervene
            </button>
          </div>
        `}
      </div>
    `;
  }
}

customElements.define('ab-a2a-panel', AbA2aPanel);
