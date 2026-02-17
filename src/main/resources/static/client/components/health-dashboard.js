import { LitElement } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';
import { Chart, LineController, LineElement, PointElement, LinearScale, CategoryScale, Tooltip, Legend } from 'https://cdn.jsdelivr.net/npm/chart.js@4.4.7/+esm';

Chart.register(LineController, LineElement, PointElement, LinearScale, CategoryScale, Tooltip, Legend);

class HealthDashboard extends LitElement {
  static properties = {
    wsUrl: { type: String, attribute: 'ws-url' },
  };

  constructor() {
    super();
    this.wsUrl = '/ws/console';
    this._ws = null;
    this._history = [];
    this._chart = null;
  }

  createRenderRoot() { return this; }

  connectedCallback() {
    super.connectedCallback();
    this._renderShell();
    this._loadHistory();
    this._connect();
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    if (this._ws) this._ws.close();
    if (this._chart) this._chart.destroy();
  }

  _renderShell() {
    this.innerHTML = `
      <div class="space-y-4">
        <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-4">
          <div id="gateway-card" class="rounded-lg bg-black/20 ring-1 ring-white/10 p-4 text-sm text-gray-200"></div>
          <div id="agents-card" class="rounded-lg bg-black/20 ring-1 ring-white/10 p-4 text-sm text-gray-200"></div>
          <div id="resources-card" class="rounded-lg bg-black/20 ring-1 ring-white/10 p-4 text-sm text-gray-200"></div>
          <div id="errors-card" class="rounded-lg bg-black/20 ring-1 ring-white/10 p-4 text-sm text-gray-200"></div>
        </div>
        <div class="rounded-lg bg-black/20 ring-1 ring-white/10 p-4">
          <h3 class="text-sm font-semibold text-white mb-3">Health Trends</h3>
          <div class="h-72">
            <canvas id="health-trend-chart" class="w-full h-full"></canvas>
          </div>
        </div>
        <div class="rounded-lg bg-black/20 ring-1 ring-white/10 p-4">
          <h3 class="text-sm font-semibold text-white mb-2">Channels</h3>
          <div id="channels-list" class="space-y-2 text-sm text-gray-300"></div>
        </div>
      </div>
    `;
  }

  async _loadHistory() {
    try {
      const res = await fetch('/api/health/history?limit=30');
      const items = await res.json();
      this._history = Array.isArray(items) ? items : [];
      this._renderFromLatest();
      this._renderChart();
    } catch (ignored) {}
  }

  _connect() {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    this._ws = new WebSocket(proto + '//' + location.host + this.wsUrl);
    this._ws.onopen = () => {
      this._ws.send(JSON.stringify({ Subscribe: { topic: 'health:metrics', params: {} } }));
    };
    this._ws.onmessage = (e) => {
      try {
        const msg = JSON.parse(e.data);
        if (!msg.Event || msg.Event.topic !== 'health:metrics') return;
        const snapshot = JSON.parse(msg.Event.payload);
        this._history.push(snapshot);
        if (this._history.length > 60) this._history = this._history.slice(this._history.length - 60);
        this._renderFromLatest();
        this._renderChart();
      } catch (ignored) {}
    };
    this._ws.onclose = () => {
      setTimeout(() => this._connect(), 2000);
    };
  }

  _renderFromLatest() {
    const latest = this._history[this._history.length - 1];
    if (!latest) return;

    const gateway = latest.gateway || {};
    const agents = latest.agents || {};
    const resources = latest.resources || {};
    const errors = latest.errors || {};
    const channels = latest.channels || [];

    this.querySelector('#gateway-card').innerHTML = `
      <h3 class="text-sm font-semibold text-white mb-2">Gateway</h3>
      <div>Version: <span class="text-gray-100">${gateway.version || '-'}</span></div>
      <div>Uptime: <span class="text-gray-100">${gateway.uptimeSeconds || 0}s</span></div>
      <div>Connections: <span class="text-gray-100">${gateway.connectionCount || 0}</span></div>
    `;

    this.querySelector('#agents-card').innerHTML = `
      <h3 class="text-sm font-semibold text-white mb-2">Agents</h3>
      <div>Active: <span class="text-emerald-400">${agents.active || 0}</span></div>
      <div>Idle: <span class="text-gray-200">${agents.idle || 0}</span></div>
      <div>Failed: <span class="text-red-400">${agents.failed || 0}</span></div>
    `;

    this.querySelector('#resources-card').innerHTML = `
      <h3 class="text-sm font-semibold text-white mb-2">Resources</h3>
      <div>CPU: <span class="text-gray-100">${(resources.cpuPercent || 0).toFixed(1)}%</span></div>
      <div>Memory: <span class="text-gray-100">${resources.usedMemoryMb || 0}/${resources.maxMemoryMb || 0} MB</span></div>
      <div>Token Usage: <span class="text-gray-100">${resources.tokenUsageEstimate || 0}</span></div>
    `;

    const recentFailure = (errors.recentFailures && errors.recentFailures[0]) ? errors.recentFailures[0] : 'None';
    this.querySelector('#errors-card').innerHTML = `
      <h3 class="text-sm font-semibold text-white mb-2">Errors</h3>
      <div>Error rate: <span class="text-red-300">${((errors.errorRate || 0) * 100).toFixed(2)}%</span></div>
      <div class="mt-1 text-xs text-gray-400 truncate" title="${recentFailure}">${recentFailure}</div>
    `;

    this.querySelector('#channels-list').innerHTML = channels.map((c) => {
      const color = c.status === 'Connected' ? 'text-emerald-400' : c.status === 'Error' ? 'text-red-400' : 'text-gray-400';
      return `<div class="flex items-center justify-between rounded bg-white/5 px-3 py-2"><span>${c.name}</span><span class="${color}">${c.status} (${c.activeSessions})</span></div>`;
    }).join('');
  }

  _renderChart() {
    const canvas = this.querySelector('#health-trend-chart');
    if (!canvas) return;
    const labels = this._history.map((s) => {
      const d = new Date(s.ts);
      return d.toLocaleTimeString();
    });
    const cpu = this._history.map((s) => (s.resources?.cpuPercent || 0));
    const mem = this._history.map((s) => (s.resources?.usedMemoryMb || 0));
    const err = this._history.map((s) => ((s.errors?.errorRate || 0) * 100));

    if (this._chart) {
      this._chart.data.labels = labels;
      this._chart.data.datasets[0].data = cpu;
      this._chart.data.datasets[1].data = mem;
      this._chart.data.datasets[2].data = err;
      this._chart.update();
      return;
    }

    this._chart = new Chart(canvas, {
      type: 'line',
      data: {
        labels,
        datasets: [
          { label: 'CPU %', data: cpu, borderColor: '#34d399', tension: 0.25 },
          { label: 'Memory MB', data: mem, borderColor: '#60a5fa', tension: 0.25 },
          { label: 'Error Rate %', data: err, borderColor: '#f87171', tension: 0.25 },
        ],
      },
      options: {
        animation: false,
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { labels: { color: '#e5e7eb' } } },
        scales: {
          x: { ticks: { color: '#9ca3af' }, grid: { color: 'rgba(255,255,255,0.08)' } },
          y: { ticks: { color: '#9ca3af' }, grid: { color: 'rgba(255,255,255,0.08)' } },
        },
      },
    });
  }
}

if (!customElements.get('health-dashboard')) {
  customElements.define('health-dashboard', HealthDashboard);
}
