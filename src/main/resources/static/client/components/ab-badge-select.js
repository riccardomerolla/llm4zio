import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

class AbBadgeSelect extends LitElement {
  static properties = {
    value:   { type: String },
    options: { type: String },
    label:   { type: String },
    variant: { type: String },
    _open:   { type: Boolean, state: true },
  };

  constructor() {
    super();
    this.value   = '';
    this.options = '[]';
    this.label   = '';
    this.variant = 'default';
    this._open   = false;
    this._onDocClick = this._onDocClick.bind(this);
  }

  createRenderRoot() { return this; }

  connectedCallback() {
    super.connectedCallback();
    this.style.position = 'relative';
    document.addEventListener('click', this._onDocClick);
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    document.removeEventListener('click', this._onDocClick);
  }

  _onDocClick(event) {
    if (!this._open) return;
    if (!this.contains(event.target)) {
      this._open = false;
    }
  }

  get _parsedOptions() {
    try {
      return JSON.parse(this.options || '[]');
    } catch {
      return [];
    }
  }

  _currentLabel() {
    const opts = this._parsedOptions;
    const found = opts.find((o) => o.value === this.value);
    return found ? found.label : (this.value || '');
  }

  _pillClasses() {
    const base = 'rounded-full px-2.5 py-0.5 text-[11px] font-semibold cursor-pointer select-none inline-flex items-center gap-1 border transition-colors duration-150';
    if (this.variant === 'accent') {
      return `${base} border-indigo-400/30 bg-indigo-500/15 text-indigo-200 hover:bg-indigo-500/25`;
    }
    return `${base} border-white/15 bg-white/5 text-gray-200 hover:bg-white/10`;
  }

  _chevronStyle() {
    return `display:inline-block;transition:transform 150ms;transform:rotate(${this._open ? '180deg' : '0deg'})`;
  }

  _toggleOpen(event) {
    event.stopPropagation();
    this._open = !this._open;
  }

  _selectOption(event, opt) {
    event.stopPropagation();
    this.value = opt.value;
    this._open = false;
    this.dispatchEvent(
      new CustomEvent('change', {
        detail: { value: opt.value, label: opt.label },
        bubbles: true,
      }),
    );
  }

  _renderChevron() {
    return html`
      <svg
        xmlns="http://www.w3.org/2000/svg"
        width="10"
        height="10"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2.5"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
        style="${this._chevronStyle()}"
      >
        <path d="M6 9l6 6 6-6" />
      </svg>
    `;
  }

  _renderDropdown() {
    if (!this._open) return html``;
    const opts = this._parsedOptions;
    return html`
      <div
        class="absolute z-50 mt-1 min-w-[140px] rounded-md border border-white/10 bg-slate-800 shadow-lg shadow-black/30"
        style="top:100%;left:0;"
      >
        ${opts.map((opt) => {
          const isSelected = opt.value === this.value;
          const optClass = [
            'px-3 py-1.5 text-[12px] cursor-pointer flex items-center gap-2',
            isSelected
              ? 'text-indigo-300 bg-indigo-500/10'
              : 'text-gray-200 hover:bg-white/10',
          ].join(' ');
          return html`
            <div
              class="${optClass}"
              @click="${(e) => this._selectOption(e, opt)}"
            >
              ${opt.icon ? html`<span>${opt.icon}</span>` : html``}
              ${opt.label}
            </div>
          `;
        })}
      </div>
    `;
  }

  render() {
    const currentLabel = this._currentLabel();
    return html`
      <span class="${this._pillClasses()}" @click="${this._toggleOpen}">
        ${this.label ? html`<span class="opacity-60">${this.label}:</span>` : html``}
        <span>${currentLabel}</span>
        ${this._renderChevron()}
      </span>
      ${this._renderDropdown()}
    `;
  }
}

customElements.define('ab-badge-select', AbBadgeSelect);
