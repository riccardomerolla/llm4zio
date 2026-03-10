import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

const GEAR_ICON_PATHS = [
  'M9.594 3.94c.09-.542.56-.94 1.11-.94h2.593c.55 0 1.02.398 1.11.94l.213 1.281c.063.374.313.686.645.87.074.04.147.083.22.127.325.196.72.257 1.075.124l1.217-.456a1.125 1.125 0 0 1 1.37.49l1.296 2.247a1.125 1.125 0 0 1-.26 1.431l-1.003.827c-.293.241-.438.613-.43.992a7.723 7.723 0 0 1 0 .255c-.008.378.137.75.43.991l1.004.827c.424.35.534.955.26 1.43l-1.298 2.247a1.125 1.125 0 0 1-1.369.491l-1.217-.456c-.355-.133-.75-.072-1.076.124a6.47 6.47 0 0 1-.22.128c-.331.183-.581.495-.644.869l-.213 1.281c-.09.543-.56.94-1.11.94h-2.594c-.55 0-1.019-.398-1.11-.94l-.213-1.281c-.062-.374-.312-.686-.644-.87a6.52 6.52 0 0 1-.22-.127c-.325-.196-.72-.257-1.076-.124l-1.217.456a1.125 1.125 0 0 1-1.369-.49l-1.297-2.247a1.125 1.125 0 0 1 .26-1.431l1.004-.827c.292-.24.437-.613.43-.991a6.932 6.932 0 0 1 0-.255c.007-.38-.138-.751-.43-.992l-1.004-.827a1.125 1.125 0 0 1-.26-1.43l1.297-2.247a1.125 1.125 0 0 1 1.37-.491l1.216.456c.356.133.751.072 1.076-.124.072-.044.146-.086.22-.128.332-.183.582-.495.644-.869l.214-1.28Z',
  'M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z',
];

const CHEVRON_DOWN_PATH = 'M19.5 8.25l-7.5 7.5-7.5-7.5';
const CHEVRON_UP_PATH   = 'M4.5 15.75l7.5-7.5 7.5 7.5';

class AbToolWaterfall extends LitElement {
  static properties = {
    tools:     { type: String },
    _expanded: { type: Boolean, state: true },
    _tools:    { type: Array,   state: true },
  };

  constructor() {
    super();
    this.tools     = '[]';
    this._expanded = false;
    this._tools    = [];
  }

  createRenderRoot() { return this; }

  updated(changed) {
    if (changed.has('tools')) {
      try {
        this._tools = JSON.parse(this.tools || '[]');
      } catch {
        this._tools = [];
      }
    }
  }

  // Also parse on first connect in case `updated` fires before attribute is set
  connectedCallback() {
    super.connectedCallback();
    try {
      this._tools = JSON.parse(this.tools || '[]');
    } catch {
      this._tools = [];
    }
  }

  _totalMs() {
    return this._tools.reduce((sum, t) => sum + (t.durationMs || 0), 0);
  }

  _maxMs() {
    return Math.max(...this._tools.map((t) => t.durationMs || 0), 1);
  }

  _formatDuration(ms) {
    if (!ms) return '';
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
  }

  _formatTotal() {
    const total = this._totalMs();
    if (!total) return '';
    return this._formatDuration(total);
  }

  _toggle() {
    this._expanded = !this._expanded;
  }

  _renderGearIcon() {
    return html`
      <svg
        xmlns="http://www.w3.org/2000/svg"
        class="w-3.5 h-3.5 flex-shrink-0 text-indigo-400"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        ${GEAR_ICON_PATHS.map((d) => html`<path d="${d}" />`)}
      </svg>
    `;
  }

  _renderChevron() {
    const path = this._expanded ? CHEVRON_UP_PATH : CHEVRON_DOWN_PATH;
    return html`
      <svg
        xmlns="http://www.w3.org/2000/svg"
        class="w-3 h-3 text-gray-500 flex-shrink-0 transition-transform"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="${path}" />
      </svg>
    `;
  }

  _renderProgressBar(durationMs) {
    const maxMs = this._maxMs();
    const pct   = maxMs > 0 ? Math.round((durationMs / maxMs) * 100) : 0;
    return html`
      <div class="relative h-1.5 w-16 flex-shrink-0 rounded-full bg-white/10 overflow-hidden">
        <div
          class="absolute inset-y-0 left-0 rounded-full bg-indigo-400/60"
          style="width:${pct}%"
        ></div>
      </div>
    `;
  }

  _isLast(idx) {
    return idx === this._tools.length - 1;
  }

  _connectorChar(idx) {
    return this._isLast(idx) ? '└─' : '├─';
  }

  _renderCollapsedRows() {
    return this._tools.map(
      (tool, idx) => html`
        <div class="flex items-center gap-2 pl-4 py-0.5">
          <span class="text-gray-600 font-mono text-[10px] flex-shrink-0 select-none"
            >${this._connectorChar(idx)}</span
          >
          <span class="text-gray-300 font-mono text-[11px] flex-shrink-0 min-w-[6rem] truncate"
            >${tool.name}</span
          >
          <span class="text-gray-500 font-mono text-[10px] flex-1 truncate"
            >${tool.subtitle || ''}</span
          >
          ${tool.durationMs ? this._renderProgressBar(tool.durationMs) : html``}
          ${tool.durationMs
            ? html`<span class="text-gray-500 font-mono text-[10px] flex-shrink-0 w-10 text-right"
                >${this._formatDuration(tool.durationMs)}</span
              >`
            : html``}
        </div>
      `,
    );
  }

  _renderExpandedRows() {
    const items = this._tools.map(
      (tool, idx) => html`
        ${idx > 0
          ? html`<div class="border-t border-white/5 my-2"></div>`
          : html``}
        <div class="space-y-1">
          <div class="flex items-center gap-2">
            <span class="text-gray-200 font-mono text-[11px] font-semibold">${tool.name}</span>
            ${tool.subtitle
              ? html`<span class="text-gray-500 font-mono text-[10px] truncate flex-1"
                  >${tool.subtitle}</span
                >`
              : html``}
            ${tool.durationMs
              ? html`<span class="text-gray-500 font-mono text-[10px] flex-shrink-0"
                  >${this._formatDuration(tool.durationMs)}</span
                >`
              : html``}
          </div>
          ${tool.input
            ? html`
                <div class="ml-2 border-l-2 border-indigo-400/40 pl-2">
                  <p class="text-[10px] text-gray-500 mb-0.5 font-semibold uppercase tracking-wide">
                    Input
                  </p>
                  <pre
                    class="text-[10px] text-gray-400 font-mono whitespace-pre-wrap break-words"
                  >${tool.input}</pre>
                </div>
              `
            : html``}
          ${tool.output
            ? html`
                <div class="ml-2 border-l-2 border-indigo-400/40 pl-2">
                  <p class="text-[10px] text-gray-500 mb-0.5 font-semibold uppercase tracking-wide">
                    Output
                  </p>
                  <pre
                    class="text-[10px] text-gray-400 font-mono whitespace-pre-wrap break-words"
                  >${tool.output}</pre>
                </div>
              `
            : html``}
        </div>
      `,
    );
    return items;
  }

  render() {
    const count     = this._tools.length;
    const totalStr  = this._formatTotal();

    return html`
      <div
        class="relative rounded-md border border-indigo-400/30 bg-indigo-500/8 my-1 overflow-hidden"
      >
        <!-- left accent line -->
        <div class="absolute left-0 top-0 bottom-0 w-0.5 bg-indigo-400 rounded-l-md"></div>

        <!-- header -->
        <div
          class="flex items-center gap-2 px-3 py-1.5 cursor-pointer hover:bg-white/5 transition-colors"
          @click="${this._toggle}"
          role="button"
          tabindex="0"
          @keydown="${(e) => (e.key === 'Enter' || e.key === ' ') && this._toggle()}"
        >
          ${this._renderGearIcon()}
          <span class="text-gray-300 font-mono text-[11px] font-semibold flex-1">
            Tool calls (${count})
          </span>
          ${totalStr
            ? html`<span class="text-gray-500 font-mono text-[10px] flex-shrink-0"
                >${totalStr} total</span
              >`
            : html``}
          ${this._renderChevron()}
        </div>

        <!-- separator -->
        <div class="border-t border-indigo-400/15 mx-3"></div>

        <!-- body: collapsed rows or expanded detail -->
        <div class="px-3 py-1.5">
          ${this._expanded
            ? this._renderExpandedRows()
            : this._renderCollapsedRows()}
        </div>
      </div>
    `;
  }
}

if (!customElements.get('ab-tool-waterfall')) {
  customElements.define('ab-tool-waterfall', AbToolWaterfall);
}
