import { LitElement } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';
import hljs from 'https://cdn.jsdelivr.net/npm/highlight.js@11.11.1/+esm';

class LogViewer extends LitElement {
  static properties = {
    wsUrl: { type: String, attribute: 'ws-url' },
    logPath: { type: String, attribute: 'log-path' },
    maxLines: { type: Number, attribute: 'max-lines' },
  };

  constructor() {
    super();
    this.wsUrl = '/ws/logs';
    this.logPath = 'logs/app.log';
    this.maxLines = 1500;
    this._ws = null;
    this._entries = [];
    this._reconnectTimer = null;
    this._connected = false;
    this._search = '';
    this._levels = new Set(['DEBUG', 'INFO', 'WARN', 'ERROR']);
    this._hljs = hljs;
  }

  createRenderRoot() { return this; }

  connectedCallback() {
    super.connectedCallback();
    this._renderShell();
    this._connect();
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    if (this._reconnectTimer) clearTimeout(this._reconnectTimer);
    if (this._ws) this._ws.close();
  }

  _connect() {
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const params = new URLSearchParams({
      path: this.logPath,
      levels: Array.from(this._levels).join(','),
      search: this._search,
    });
    this._ws = new WebSocket(protocol + '//' + location.host + this.wsUrl + '?' + params.toString());
    this._ws.onopen = () => {
      this._connected = true;
      this._setConnectionBadge();
    };
    this._ws.onmessage = (event) => this._onMessage(event.data);
    this._ws.onclose = () => {
      this._connected = false;
      this._setConnectionBadge();
      this._reconnectTimer = setTimeout(() => this._connect(), 2000);
    };
  }

  _onMessage(payload) {
    let parsed;
    try {
      parsed = JSON.parse(payload);
    } catch (ignored) {
      return;
    }
    if (parsed.type === 'error') {
      this._appendLine({ line: parsed.message, level: 'ERROR' });
      return;
    }
    if (!parsed.line) return;
    this._appendLine({ line: parsed.line, level: parsed.level || 'INFO', ts: parsed.ts });
  }

  _appendLine(entry) {
    this._entries.push(entry);
    if (this._entries.length > this.maxLines) {
      this._entries.splice(0, this._entries.length - this.maxLines);
    }
    this._renderEntries();
  }

  _renderShell() {
    this.innerHTML = `
      <div class="space-y-4">
        <div class="flex flex-wrap items-center gap-3">
          <input id="log-search" type="text" placeholder="Search logs..." class="bg-white/10 border border-white/20 rounded-lg px-3 py-2 text-white text-sm placeholder:text-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500" />
          <input id="log-path" type="text" value="${this.logPath}" class="min-w-[260px] bg-white/10 border border-white/20 rounded-lg px-3 py-2 text-white text-sm placeholder:text-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500" />
          <button id="apply-filters" type="button" class="px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg text-sm font-medium">Apply</button>
          <span id="ws-badge" class="text-xs rounded px-2 py-1 bg-red-500/10 text-red-400 ring-1 ring-red-500/20">Disconnected</span>
        </div>
        <div class="flex flex-wrap items-center gap-2 text-xs">
          ${this._levelToggle('DEBUG')}
          ${this._levelToggle('INFO')}
          ${this._levelToggle('WARN')}
          ${this._levelToggle('ERROR')}
        </div>
        <div id="log-entries" class="bg-black/30 border border-white/10 rounded-lg p-4 max-h-[65vh] overflow-auto text-sm leading-6 font-mono text-gray-100"></div>
      </div>
    `;

    const applyButton = this.querySelector('#apply-filters');
    if (applyButton) applyButton.addEventListener('click', () => this._applyFilters());
    this.querySelectorAll('[data-level-toggle]').forEach((el) => {
      el.addEventListener('click', () => {
        const level = el.dataset.levelToggle;
        if (!level) return;
        if (this._levels.has(level)) this._levels.delete(level);
        else this._levels.add(level);
        this._renderShell();
        this._renderEntries();
      });
    });
    this._renderEntries();
    this._setConnectionBadge();
  }

  _levelToggle(level) {
    const active = this._levels.has(level);
    const cls = active
      ? 'cursor-pointer rounded-md px-2 py-1 bg-indigo-500/10 text-indigo-400 ring-1 ring-indigo-500/20'
      : 'cursor-pointer rounded-md px-2 py-1 bg-white/5 text-gray-400 ring-1 ring-white/10';
    return `<button type="button" data-level-toggle="${level}" class="${cls}">${level}</button>`;
  }

  _applyFilters() {
    const searchInput = this.querySelector('#log-search');
    const pathInput = this.querySelector('#log-path');
    this._search = searchInput ? searchInput.value.trim() : '';
    this.logPath = pathInput && pathInput.value.trim() ? pathInput.value.trim() : this.logPath;
    this._entries = [];
    this._renderEntries();
    if (this._ws) this._ws.close();
    this._connect();
  }

  _renderEntries() {
    const container = this.querySelector('#log-entries');
    if (!container) return;
    const filtered = this._entries.filter((entry) => {
      const levelOk = this._levels.has(entry.level || 'INFO');
      const searchOk = !this._search || entry.line.toLowerCase().includes(this._search.toLowerCase());
      return levelOk && searchOk;
    });
    container.innerHTML = filtered.map((entry) => {
      const level = entry.level || 'INFO';
      const levelCls = level === 'ERROR' ? 'text-red-400' : level === 'WARN' ? 'text-amber-400' : level === 'DEBUG' ? 'text-slate-400' : 'text-emerald-400';
      return `<div class="log-line py-0.5"><span class="${levelCls} font-semibold">[${level}]</span> <code class="hljs language-json">${this._escapeHtml(entry.line)}</code></div>`;
    }).join('');
    container.querySelectorAll('code.language-json').forEach((el) => this._hljs.highlightElement(el));
    container.scrollTop = container.scrollHeight;
  }

  _setConnectionBadge() {
    const badge = this.querySelector('#ws-badge');
    if (!badge) return;
    if (this._connected) {
      badge.textContent = 'Connected';
      badge.className = 'text-xs rounded px-2 py-1 bg-green-500/10 text-green-400 ring-1 ring-green-500/20';
    } else {
      badge.textContent = 'Disconnected';
      badge.className = 'text-xs rounded px-2 py-1 bg-red-500/10 text-red-400 ring-1 ring-red-500/20';
    }
  }

  _escapeHtml(value) {
    return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
  }
}

if (!customElements.get('log-viewer')) {
  customElements.define('log-viewer', LogViewer);
}
