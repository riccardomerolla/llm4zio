import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

// ---------------------------------------------------------------------------
// ab-nav-dropdown
// Top navigation bar dropdown menu.
// Renders a trigger button that reveals a floating panel when clicked.
//
// Attributes:
//   label  (String)  — button label text
//   align  (String)  — "left" (default) or "right" — panel alignment
//
// Usage:
//   <ab-nav-dropdown label="ADE" align="right">
//     <a href="/board" class="...">Board</a>
//   </ab-nav-dropdown>
// ---------------------------------------------------------------------------

class AbNavDropdown extends LitElement {
  static properties = {
    label: { type: String },
    open:  { type: Boolean },
    align: { type: String },
  };

  constructor() {
    super();
    this.label = '';
    this.open  = false;
    this.align = 'left';

    this._onOutsideClick = this._onOutsideClick.bind(this);
    this._onKeydown      = this._onKeydown.bind(this);
  }

  createRenderRoot() { return this; }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  connectedCallback() {
    super.connectedCallback();
    document.addEventListener('click',   this._onOutsideClick);
    document.addEventListener('keydown', this._onKeydown);
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    document.removeEventListener('click',   this._onOutsideClick);
    document.removeEventListener('keydown', this._onKeydown);
  }

  // ── Event handlers ─────────────────────────────────────────────────────────

  _onOutsideClick(event) {
    if (!this.contains(event.target)) {
      this._close();
    }
  }

  _onKeydown(event) {
    if (event.key === 'Escape' && this.open) {
      event.preventDefault();
      this._close();
    }
  }

  _toggle() {
    if (this.open) {
      this._close();
    } else {
      this.open = true;
    }
  }

  _close() {
    if (!this.open) return;
    this.open = false;
    // Return focus to the trigger button
    this.updateComplete.then(() => {
      const btn = this.querySelector('[data-nav-trigger]');
      if (btn) btn.focus();
    });
  }

  // ── Render ─────────────────────────────────────────────────────────────────

  render() {
    const alignClass = this.align === 'right' ? 'right-0' : 'left-0';
    const chevronStyle = this.open
      ? 'display:inline-block;transition:transform 200ms ease;transform:rotate(180deg)'
      : 'display:inline-block;transition:transform 200ms ease;transform:rotate(0deg)';

    return html`
      <div class="relative">

        <!-- Trigger button -->
        <button
          type="button"
          data-nav-trigger
          aria-haspopup="true"
          aria-expanded="${this.open}"
          class="text-sm text-gray-300 hover:text-white flex items-center gap-1 rounded px-2 py-1 transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
          @click="${(e) => { e.stopPropagation(); this._toggle(); }}"
        >
          ${this.label}
          <span aria-hidden="true" style="${chevronStyle}">▼</span>
        </button>

        <!-- Dropdown panel -->
        ${this.open ? html`
          <div
            class="absolute z-50 mt-1 rounded-lg border border-white/10 bg-slate-900 shadow-xl py-1 min-w-[12rem] ${alignClass}"
            role="menu"
            aria-label="${this.label} menu"
          >
            <slot></slot>
          </div>
        ` : ''}

      </div>
    `;
  }
}

if (!customElements.get('ab-nav-dropdown')) {
  customElements.define('ab-nav-dropdown', AbNavDropdown);
}
