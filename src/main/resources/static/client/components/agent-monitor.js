import { LitElement } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

class AgentMonitorPanel extends LitElement {
  static properties = {
    wsUrl: { type: String, attribute: 'ws-url' },
  };

  constructor() {
    super();
    this.wsUrl = '/ws/console';
    this._ws = null;
    this._agents = [];
    this._history = [];
    this._connected = false;
    this._pollTimer = null;
  }

  createRenderRoot() { return this; }

  connectedCallback() {
    super.connectedCallback();
    this._renderShell();
    this._loadInitial();
    this._connect();
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    if (this._ws) this._ws.close();
    if (this._pollTimer) clearInterval(this._pollTimer);
  }

  async _loadInitial() {
    await Promise.all([this._loadSnapshot(), this._loadHistory()]);
    this._renderData();
  }

  async _loadSnapshot() {
    try {
      const res = await fetch('/api/agent-monitor/snapshot');
      if (!res.ok) return;
      const payload = await res.json();
      this._agents = Array.isArray(payload.agents) ? payload.agents : [];
    } catch (ignored) {}
  }

  async _loadHistory() {
    try {
      const res = await fetch('/api/agent-monitor/history?limit=120');
      if (!res.ok) return;
      const payload = await res.json();
      this._history = Array.isArray(payload) ? payload : [];
    } catch (ignored) {}
  }

  _connect() {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    this._ws = new WebSocket(proto + '//' + location.host + this.wsUrl);
    this._ws.onopen = () => {
      this._connected = true;
      this._setConnectionBadge();
      this._ws.send(JSON.stringify({ Subscribe: { topic: 'agents:activity', params: {} } }));
    };
    this._ws.onmessage = (e) => this._onMessage(e.data);
    this._ws.onclose = () => {
      this._connected = false;
      this._setConnectionBadge();
      setTimeout(() => this._connect(), 2000);
    };

    if (!this._pollTimer) {
      this._pollTimer = setInterval(async () => {
        if (this._connected) return;
        await this._loadInitial();
      }, 3000);
    }
  }

  _onMessage(raw) {
    try {
      const msg = JSON.parse(raw);
      if (!msg.Event || msg.Event.topic !== 'agents:activity') return;
      const payload = JSON.parse(msg.Event.payload);
      this._agents = Array.isArray(payload.snapshot?.agents) ? payload.snapshot.agents : this._agents;
      this._history = Array.isArray(payload.history) ? payload.history : this._history;
      this._renderData();
    } catch (ignored) {}
  }

  async _control(agentName, action) {
    const endpoint = '/api/agent-monitor/agents/' + encodeURIComponent(agentName) + '/' + action;
    try {
      const res = await fetch(endpoint, { method: 'POST' });
      if (!res.ok) return;
      await this._loadInitial();
    } catch (ignored) {}
  }

  _renderShell() {
    this.innerHTML = `
      <div class="space-y-4">
        <div class="flex items-center justify-between">
          <h2 class="text-sm font-semibold text-white">Live Agent States</h2>
          <span id="am-conn" class="text-xs rounded px-2 py-1 bg-red-500/10 text-red-400 ring-1 ring-red-500/20">Disconnected</span>
        </div>
        <div id="am-agents" class="grid grid-cols-1 xl:grid-cols-2 gap-3"></div>
        <div class="rounded-lg bg-black/20 ring-1 ring-white/10 p-4">
          <h3 class="text-sm font-semibold text-white mb-3">Execution Timeline</h3>
          <div id="am-history" class="space-y-2 max-h-[34rem] overflow-auto"></div>
        </div>
      </div>
    `;
  }

  _setConnectionBadge() {
    const badge = this.querySelector('#am-conn');
    if (!badge) return;
    if (this._connected) {
      badge.textContent = 'Connected';
      badge.className = 'text-xs rounded px-2 py-1 bg-green-500/10 text-green-400 ring-1 ring-green-500/20';
    } else {
      badge.textContent = 'Disconnected';
      badge.className = 'text-xs rounded px-2 py-1 bg-red-500/10 text-red-400 ring-1 ring-red-500/20';
    }
  }

  _stateBadge(state) {
    switch (state) {
      case 'Idle': return 'bg-gray-500/10 text-gray-300 ring-gray-400/30';
      case 'Executing': return 'bg-emerald-500/10 text-emerald-400 ring-emerald-500/20';
      case 'WaitingForTool': return 'bg-amber-500/10 text-amber-300 ring-amber-500/20';
      case 'Paused': return 'bg-blue-500/10 text-blue-300 ring-blue-500/20';
      case 'Aborted': return 'bg-red-500/10 text-red-300 ring-red-500/20';
      case 'Failed': return 'bg-rose-500/10 text-rose-300 ring-rose-500/20';
      default: return 'bg-gray-500/10 text-gray-300 ring-gray-400/30';
    }
  }

  _renderData() {
    this._setConnectionBadge();

    const agentsContainer = this.querySelector('#am-agents');
    if (agentsContainer) {
      if (!this._agents.length) {
        agentsContainer.innerHTML = '<div class="text-sm text-gray-400 rounded-lg bg-black/20 ring-1 ring-white/10 p-4">No tracked agent execution yet.</div>';
      } else {
        agentsContainer.innerHTML = this._agents.map((agent) => {
          const badgeCls = this._stateBadge(agent.state);
          const runRef = agent.runId ? `Run: <span class="text-gray-200">${agent.runId}</span>` : 'Run: -';
          const stepRef = agent.step ? `Step: <span class="text-gray-200">${agent.step}</span>` : 'Step: -';
          const task = agent.task ? `<div class="mt-2 text-xs text-gray-300">${this._escape(agent.task)}</div>` : '';
          const message = agent.message ? `<div class="mt-1 text-xs text-gray-500">${this._escape(agent.message)}</div>` : '';
          return `
            <div class="rounded-lg bg-black/20 ring-1 ring-white/10 p-4">
              <div class="flex items-start justify-between gap-3">
                <div>
                  <div class="text-sm font-semibold text-white">${this._escape(agent.agentName)}</div>
                  <div class="mt-1 text-xs text-gray-400">${runRef} • ${stepRef}</div>
                </div>
                <span class="inline-flex items-center rounded-md px-2 py-1 text-xs ring-1 ${badgeCls}">${agent.state}</span>
              </div>
              ${task}
              ${message}
              <div class="mt-3 grid grid-cols-3 gap-2 text-xs">
                <div class="rounded bg-white/5 px-2 py-1 text-gray-300">Tokens<br><span class="text-gray-100">${agent.tokensUsed || 0}</span></div>
                <div class="rounded bg-white/5 px-2 py-1 text-gray-300">Latency<br><span class="text-gray-100">${agent.latencyMs || 0} ms</span></div>
                <div class="rounded bg-white/5 px-2 py-1 text-gray-300">Cost<br><span class="text-gray-100">$${(agent.cost || 0).toFixed(6)}</span></div>
              </div>
              <div class="mt-3 flex items-center gap-2">
                <button data-agent="${this._escapeAttr(agent.agentName)}" data-action="pause" class="px-2 py-1 text-xs rounded bg-amber-500/10 text-amber-300 ring-1 ring-amber-500/20 hover:bg-amber-500/20">Pause</button>
                <button data-agent="${this._escapeAttr(agent.agentName)}" data-action="resume" class="px-2 py-1 text-xs rounded bg-blue-500/10 text-blue-300 ring-1 ring-blue-500/20 hover:bg-blue-500/20">Resume</button>
                <button data-agent="${this._escapeAttr(agent.agentName)}" data-action="abort" class="px-2 py-1 text-xs rounded bg-red-500/10 text-red-300 ring-1 ring-red-500/20 hover:bg-red-500/20">Abort</button>
              </div>
            </div>
          `;
        }).join('');
      }
      agentsContainer.querySelectorAll('button[data-action]').forEach((btn) => {
        btn.addEventListener('click', () => this._control(btn.dataset.agent, btn.dataset.action));
      });
    }

    const historyContainer = this.querySelector('#am-history');
    if (historyContainer) {
      if (!this._history.length) {
        historyContainer.innerHTML = '<div class="text-sm text-gray-400">No execution events yet.</div>';
      } else {
        historyContainer.innerHTML = this._history.slice(0, 120).map((event) => {
          const when = new Date(event.timestamp).toLocaleTimeString();
          return `
            <div class="rounded bg-white/5 px-3 py-2 ring-1 ring-white/10 text-xs text-gray-300">
              <div class="flex items-center justify-between">
                <span class="font-medium text-white">${this._escape(event.agentName)}</span>
                <span class="text-gray-500">${when}</span>
              </div>
              <div class="mt-1 text-gray-400">${this._escape(event.state)} • ${this._escape(event.detail || '')}</div>
            </div>
          `;
        }).join('');
      }
    }
  }

  _escape(value) {
    return String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
  }

  _escapeAttr(value) {
    return this._escape(value).replaceAll('"', '&quot;');
  }
}

if (!customElements.get('agent-monitor-panel')) {
  customElements.define('agent-monitor-panel', AgentMonitorPanel);
}
