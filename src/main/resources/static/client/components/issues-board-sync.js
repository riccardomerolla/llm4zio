function createBoardSyncHooks({
  beforeRefresh = () => {},
  afterRefresh = () => {},
  afterSettle = () => {},
} = {}) {
  return {
    _flushPendingRefresh() {
      if (!this._refreshPending) return;
      if (this._refreshInFlight) return;
      if (this._shouldDeferRefresh()) return;
      this._refreshPending = false;
      this.refreshBoard();
    },

    refreshBoard(landedIssueId = null) {
      if (this._refreshInFlight) {
        this._refreshPending = true;
        return;
      }

      if (this._shouldDeferRefresh()) {
        this._refreshPending = true;
        return;
      }

      this._refreshInFlight = true;
      beforeRefresh.call(this);

      const onSettled = () => {
        this._refreshInFlight = false;
        afterSettle.call(this);
        this._flushPendingRefresh();
      };

      const onRefreshed = () => {
        afterRefresh.call(this, landedIssueId);
        this._flushPostRefreshToast();
      };

      if (window.htmx?.ajax) {
        window.htmx.ajax('GET', this.fragmentUrl, {
          target: this,
          swap: 'innerHTML',
        }).then(onRefreshed).catch(() => {}).finally(onSettled);
        return;
      }

      fetch(this.fragmentUrl)
        .then((response) => response.ok ? response.text() : Promise.reject(new Error('refresh failed')))
        .then((html) => {
          // html is server-rendered markup from our own trusted endpoint
          this.innerHTML = html; // nosec: trusted server HTML, same origin
          onRefreshed();
        })
        .catch(() => {})
        .finally(onSettled);
    },

    _flushPostRefreshToast() {
      const toast = this._pendingPostRefreshToast;
      this._pendingPostRefreshToast = null;
      if (!toast?.message) return;
      this._showToast(toast.message, toast.type || 'warning');
    },

    connectWs() {
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      this.ws = new WebSocket(`${protocol}//${window.location.host}/ws/console`);
      this.ws.onopen = () => {
        this.ws?.send(JSON.stringify({ Subscribe: { topic: this.wsTopic, params: {} } }));
      };
      this.ws.onmessage = (event) => this.onWsMessage(event.data);
    },

    onWsMessage(raw) {
      let parsed;
      try {
        parsed = JSON.parse(raw);
      } catch (_ignored) {
        return;
      }

      const evt = parsed?.Event;
      if (!evt || evt.topic !== this.wsTopic) return;
      if (evt.eventType === 'activity-feed') {
        this.refreshBoard();
      }
    },
  };
}

window.__issuesBoardSync = Object.freeze({ createBoardSyncHooks });

export { createBoardSyncHooks };
