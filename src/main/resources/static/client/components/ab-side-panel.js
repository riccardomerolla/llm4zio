import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

class AbSidePanel extends LitElement {
  static properties = {
    open:    { type: Boolean, reflect: true },
    title:   { type: String },
    width:   { type: String },
    panelId: { type: String, attribute: 'panel-id' },
  };

  constructor() {
    super();
    this.open    = false;
    this.title   = '';
    this.width   = '320px';
    this.panelId = '';

    this._onEsc       = this._onEsc.bind(this);
    this._onPanelOpen = this._onPanelOpen.bind(this);
  }

  createRenderRoot() { return this; }

  // ── Lifecycle ────────────────────────────────────────────────────────────────

  connectedCallback() {
    super.connectedCallback();
    document.addEventListener('keydown',      this._onEsc);
    window.addEventListener('ab-panel-open',  this._onPanelOpen);
    this._applyTransitionStyles();
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    document.removeEventListener('keydown',     this._onEsc);
    window.removeEventListener('ab-panel-open', this._onPanelOpen);
  }

  updated(changedProps) {
    if (changedProps.has('open') || changedProps.has('width')) {
      this._applyTransitionStyles();
    }
  }

  // ── Transition styles (applied via JS to avoid needing arbitrary Tailwind values) ──

  _applyTransitionStyles() {
    const resolvedWidth = this.width || '320px';
    const isMobile = window.innerWidth < 1024;

    Object.assign(this.style, {
      position:      'fixed',
      right:         '0',
      top:           '4rem',
      height:        'calc(100vh - 4rem)',
      width:         isMobile ? '100%' : resolvedWidth,
      zIndex:        '40',
      transition:    'transform 200ms ease-out, opacity 150ms ease-out',
      transform:     this.open ? 'translateX(0)'   : 'translateX(100%)',
      opacity:       this.open ? '1'               : '0',
      pointerEvents: this.open ? 'auto'            : 'none',
    });
  }

  // ── Event handlers ───────────────────────────────────────────────────────────

  _onEsc(event) {
    if (event.key === 'Escape' && this.open) {
      this._close();
    }
  }

  _onPanelOpen(event) {
    const { panelId, title, contentUrl } = event.detail || {};
    if (!panelId || panelId !== this.panelId) return;

    if (title) this.title = title;
    this.open = true;

    if (contentUrl) {
      // Defer until after Lit has rendered so the content element exists in DOM
      this.updateComplete.then(() => this._loadContent(contentUrl));
    }
  }

  _close() {
    this.open = false;
    this.dispatchEvent(new CustomEvent('panel-close', {
      bubbles:  true,
      composed: true,
      detail:   { panelId: this.panelId },
    }));
  }

  // ── Content loading (HTMX-first) ─────────────────────────────────────────────

  _loadContent(url) {
    const contentEl = this.querySelector(`#panel-${this.panelId}-content`);
    if (!contentEl) return;

    if (window.htmx) {
      // Let HTMX handle the fetch and swap; it sanitises before insertion.
      contentEl.setAttribute('hx-get',     url);
      contentEl.setAttribute('hx-trigger', 'load');
      contentEl.setAttribute('hx-swap',    'innerHTML');
      window.htmx.process(contentEl);
    } else {
      // Plain fetch fallback — server-rendered HTML from same origin.
      const loadingEl = document.createElement('p');
      loadingEl.className = 'text-xs text-gray-400 animate-pulse';
      loadingEl.textContent = 'Loading…';
      contentEl.replaceChildren(loadingEl);

      fetch(url)
        .then((r) => {
          if (!r.ok) throw new Error(`HTTP ${r.status}`);
          return r.text();
        })
        .then((markup) => {
          // Parse via DOMParser so the browser handles the HTML safely
          const doc = new DOMParser().parseFromString(markup, 'text/html');
          const fragment = document.createDocumentFragment();
          for (const child of doc.body.childNodes) {
            fragment.appendChild(document.adoptNode(child));
          }
          contentEl.replaceChildren(fragment);
        })
        .catch(() => {
          const errEl = document.createElement('p');
          errEl.className = 'text-xs text-rose-400';
          errEl.textContent = 'Failed to load content.';
          contentEl.replaceChildren(errEl);
        });
    }
  }

  // ── SVG helpers ───────────────────────────────────────────────────────────────

  _xIconSvg() {
    return html`
      <svg
        xmlns="http://www.w3.org/2000/svg"
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="M18 6 6 18M6 6l12 12" />
      </svg>
    `;
  }

  // ── Render ───────────────────────────────────────────────────────────────────

  render() {
    const panelId = this.panelId || 'default';

    return html`
      <div class="panel-wrapper flex flex-col h-full bg-slate-900 border-l border-white/10 shadow-2xl shadow-black/50">

        <!-- Header -->
        <div class="panel-header flex items-center justify-between px-4 py-3 border-b border-white/10 flex-shrink-0">
          <h2 class="text-sm font-semibold text-white truncate">${this.title}</h2>
          <div class="flex items-center gap-1 flex-shrink-0">
            <!-- External header-actions slot (populated by consumers targeting this id) -->
            <div id="panel-${panelId}-actions"></div>
            <!-- Close button -->
            <button
              type="button"
              class="p-1 text-gray-400 hover:text-white hover:bg-white/10 rounded-md transition-colors"
              aria-label="Close panel"
              title="Close panel"
              @click="${() => this._close()}"
            >
              ${this._xIconSvg()}
            </button>
          </div>
        </div>

        <!-- Content — HTMX targets this div by id; Lit only renders the wrapper -->
        <div
          class="panel-content flex-1 overflow-y-auto p-4"
          id="panel-${panelId}-content"
        ></div>

      </div>
    `;
  }
}

if (!customElements.get('ab-side-panel')) {
  customElements.define('ab-side-panel', AbSidePanel);
}
