class RunDashboard {
  constructor(root) {
    this.root = root;
    this.fragmentUrl = root?.dataset?.fragmentUrl || '/runs/fragment';
    this.ws = null;
    this.timer = null;

    this.tickDurations();
    this.startTicker();
    this.connectWs();

    document.body.addEventListener('htmx:afterSwap', (event) => {
      if (event.target && event.target.id === 'runs-dashboard-root') {
        this.tickDurations();
      }
    });
  }

  startTicker() {
    this.timer = window.setInterval(() => this.tickDurations(), 1000);
  }

  tickDurations() {
    this.root.querySelectorAll('[data-role="run-duration"]').forEach((el) => {
      const startedAt = Date.parse(el.dataset.startedAt || '');
      if (!Number.isFinite(startedAt)) return;

      const isRunning = String(el.dataset.isRunning || '').toLowerCase() === 'true';
      const endedAt = Date.parse(el.dataset.finishedAt || '');
      const end = isRunning || !Number.isFinite(endedAt) ? Date.now() : endedAt;
      const totalSeconds = Math.max(0, Math.floor((end - startedAt) / 1000));
      el.textContent = this.formatDuration(totalSeconds);
    });
  }

  formatDuration(totalSeconds) {
    const h = Math.floor(totalSeconds / 3600);
    const m = Math.floor((totalSeconds % 3600) / 60);
    const s = totalSeconds % 60;
    if (h > 0) {
      return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
    }
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  }

  connectWs() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    this.ws = new WebSocket(`${protocol}//${window.location.host}/ws/console`);
    this.ws.onopen = () => {
      this.ws?.send(JSON.stringify({ Subscribe: { topic: 'dashboard:recent-runs', params: {} } }));
    };
    this.ws.onmessage = (event) => this.onWsMessage(event.data);
    window.addEventListener('beforeunload', () => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.ws.send(JSON.stringify({ Unsubscribe: { topic: 'dashboard:recent-runs' } }));
      }
    });
  }

  onWsMessage(raw) {
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch (_ignored) {
      return;
    }

    const event = parsed?.Event;
    if (!event || event.topic !== 'dashboard:recent-runs') return;
    this.refresh();
  }

  refresh() {
    if (window.htmx?.ajax) {
      window.htmx.ajax('GET', this.fragmentUrl, {
        target: this.root,
        swap: 'innerHTML',
      }).then(() => this.tickDurations());
      return;
    }

    fetch(this.fragmentUrl)
      .then((response) => response.ok ? response.text() : Promise.reject(new Error('refresh failed')))
      .then((html) => {
        this.root.innerHTML = html;
        this.tickDurations();
      })
      .catch(() => {});
  }
}

document.querySelectorAll('#runs-dashboard-root').forEach((root) => {
  if (!root.__runDashboard) {
    root.__runDashboard = new RunDashboard(root);
  }
});
