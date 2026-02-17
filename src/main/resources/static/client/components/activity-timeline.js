  import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

  class ActivityTimeline extends LitElement {
    static properties = {
      wsUrl: { type: String, attribute: 'ws-url' },
      _connected: { state: true },
      _activeFilter: { state: true },
    };

    constructor() {
      super();
      this.wsUrl = '/ws/console';
      this._connected = false;
      this._activeFilter = 'all';
      this._ws = null;
    }

    createRenderRoot() { return this; }

    connectedCallback() {
      super.connectedCallback();
      this._connect();
      window.__activityFilterClick = (el) => this._handleFilter(el);
    }

    disconnectedCallback() {
      super.disconnectedCallback();
      if (this._ws) { this._ws.close(); this._ws = null; }
      window.__activityFilterClick = null;
    }

    _connect() {
      const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
      this._ws = new WebSocket(proto + '//' + location.host + this.wsUrl);
      this._ws.onopen = () => {
        this._connected = true;
        this._ws.send(JSON.stringify({ Subscribe: { topic: 'activity:feed', params: {} } }));
      };
      this._ws.onclose = () => {
        this._connected = false;
        setTimeout(() => this._connect(), 3000);
      };
      this._ws.onmessage = (e) => this._handleMessage(e);
    }

    _handleMessage(e) {
      try {
        const msg = JSON.parse(e.data);
        if (!msg.Event) return;
        const { eventType, payload } = msg.Event;
        if (eventType === 'Pong') return;
        this._prependEvent(payload);
      } catch (err) { /* ignore parse errors */ }
    }

    _prependEvent(jsonPayload) {
      try {
        const container = document.getElementById('activity-events');
        if (!container) return;

        // Remove empty-state message if present
        const empty = container.querySelector('.text-center');
        if (empty) empty.remove();

        // Fetch the server-rendered card for the latest event
        fetch('/api/activity/events/latest-card')
          .then(r => r.text())
          .then(cardHtml => {
            const range = document.createRange();
            range.selectNode(container);
            const fragment = range.createContextualFragment(cardHtml);
            container.insertBefore(fragment, container.firstChild);
            this._applyFilter();
          });
      } catch (err) { /* ignore */ }
    }

    _handleFilter(el) {
      this._activeFilter = el.dataset.filter;
      // Update button styles
      document.querySelectorAll('.activity-filter').forEach(btn => {
        const isActive = btn.dataset.filter === this._activeFilter;
        btn.className = btn.className.replace(
          isActive ? 'bg-white/5 text-gray-400 ring-white/10' : 'bg-indigo-500/10 text-indigo-400 ring-indigo-500/20',
          isActive ? 'bg-indigo-500/10 text-indigo-400 ring-indigo-500/20' : 'bg-white/5 text-gray-400 ring-white/10',
        );
      });
      this._applyFilter();
    }

    _applyFilter() {
      document.querySelectorAll('.activity-event').forEach(card => {
        if (this._activeFilter === 'all' || card.dataset.eventType === this._activeFilter) {
          card.style.display = '';
        } else {
          card.style.display = 'none';
        }
      });
    }

    render() { return html``; }
  }

  customElements.define('activity-timeline', ActivityTimeline);
