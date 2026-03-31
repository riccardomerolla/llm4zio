import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

class AbRunDashboard extends LitElement {
  static properties = {
    fragmentUrl: { type: String, attribute: 'data-fragment-url' },
  };

  constructor() {
    super();
    this.fragmentUrl = '/runs/fragment';
    this._ws = null;
    this._timer = null;
    // Store a bound reference so the same function object can be removed in
    // disconnectedCallback (addEventListener/removeEventListener require the same reference).
    this._boundAfterSwap = () => this.tickDurations();
  }

  createRenderRoot() { return this; }

  connectedCallback() {
    super.connectedCallback();
    this.tickDurations();
    this._startTicker();
    this._connectWs();
    // htmx:afterSwap fires on the target element and bubbles; listen here to
    // re-tick durations after every HTMX innerHTML swap into this element.
    this.addEventListener('htmx:afterSwap', this._boundAfterSwap);
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this.removeEventListener('htmx:afterSwap', this._boundAfterSwap);
    if (this._timer !== null) {
      clearInterval(this._timer);
      this._timer = null;
    }
    if (this._ws && this._ws.readyState === WebSocket.OPEN) {
      this._ws.send(JSON.stringify({ Unsubscribe: { topic: 'dashboard:recent-runs' } }));
      this._ws.close();
    }
    this._ws = null;
  }

  render() { return html``; }

  _startTicker() {
    this._timer = window.setInterval(() => this.tickDurations(), 1000);
  }

  tickDurations() {
    this.querySelectorAll('[data-role="run-duration"]').forEach((el) => {
      const startedAt = Date.parse(el.dataset.startedAt || '');
      if (!Number.isFinite(startedAt)) return;

      const isRunning = String(el.dataset.isRunning || '').toLowerCase() === 'true';
      const endedAt = Date.parse(el.dataset.finishedAt || '');
      const end = isRunning || !Number.isFinite(endedAt) ? Date.now() : endedAt;
      const totalSeconds = Math.max(0, Math.floor((end - startedAt) / 1000));
      el.textContent = this._formatDuration(totalSeconds);
    });
  }

  _formatDuration(totalSeconds) {
    const h = Math.floor(totalSeconds / 3600);
    const m = Math.floor((totalSeconds % 3600) / 60);
    const s = totalSeconds % 60;
    if (h > 0) {
      return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
    }
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  }

  _connectWs() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    this._ws = new WebSocket(`${protocol}//${window.location.host}/ws/console`);
    this._ws.onopen = () => {
      this._ws?.send(JSON.stringify({ Subscribe: { topic: 'dashboard:recent-runs', params: {} } }));
    };
    this._ws.onmessage = (event) => this._onWsMessage(event.data);
    window.addEventListener('beforeunload', () => {
      if (this._ws && this._ws.readyState === WebSocket.OPEN) {
        this._ws.send(JSON.stringify({ Unsubscribe: { topic: 'dashboard:recent-runs' } }));
      }
    });
  }

  _onWsMessage(raw) {
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch (_ignored) {
      return;
    }

    const event = parsed?.Event;
    if (!event || event.topic !== 'dashboard:recent-runs') return;
    this._refresh();
  }

  _refresh() {
    if (window.htmx?.ajax) {
      window.htmx.ajax('GET', this.fragmentUrl, {
        target: this,
        swap: 'innerHTML',
      }).then(() => this.tickDurations());
      return;
    }

    fetch(this.fragmentUrl)
      .then((response) => response.ok ? response.text() : Promise.reject(new Error('refresh failed')))
      .then((html) => {
        this.innerHTML = html;
        this.tickDurations();
      })
      .catch(() => {});
  }
}

if (!customElements.get('ab-run-dashboard')) {
  customElements.define('ab-run-dashboard', AbRunDashboard);
}
