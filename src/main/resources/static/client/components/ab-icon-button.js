import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

class AbIconButton extends LitElement {
  static properties = {
    icon:     { type: String },
    tooltip:  { type: String },
    variant:  { type: String },
    size:     { type: String },
    badge:    { type: String },
    active:   { type: Boolean },
    href:     { type: String },
    disabled: { type: Boolean },
  };

  constructor() {
    super();
    this.icon     = '';
    this.tooltip  = '';
    this.variant  = 'ghost';
    this.size     = 'md';
    this.badge    = '';
    this.active   = false;
    this.href     = '';
    this.disabled = false;
  }

  createRenderRoot() { return this; }

  _variantClasses() {
    if (this.active) {
      return 'bg-white/15 text-white';
    }
    switch (this.variant) {
      case 'subtle': return 'text-gray-300 bg-white/5 hover:bg-white/15';
      case 'solid':  return 'text-white bg-indigo-600 hover:bg-indigo-500';
      case 'ghost':
      default:       return 'text-gray-400 hover:text-white hover:bg-white/10';
    }
  }

  _sizeClasses() {
    return this.size === 'sm' ? 'p-1' : 'p-1.5';
  }

  _iconSize() {
    return this.size === 'sm' ? 16 : 20;
  }

  _baseClasses() {
    return [
      'relative inline-flex items-center justify-center',
      'rounded-md',
      'transition-colors duration-150',
      'focus:outline-none focus:ring-2 focus:ring-indigo-500/50',
      this._sizeClasses(),
      this._variantClasses(),
      this.disabled ? 'opacity-50 cursor-not-allowed pointer-events-none' : 'cursor-pointer',
    ].join(' ');
  }

  _renderIcon() {
    const sz = this._iconSize();
    return html`
      <svg
        xmlns="http://www.w3.org/2000/svg"
        width="${sz}"
        height="${sz}"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="${this.icon}" />
      </svg>
    `;
  }

  _renderBadge() {
    if (!this.badge) return html``;
    return html`
      <span class="absolute -top-0.5 -right-0.5 flex items-center justify-center rounded-full bg-indigo-500 text-white text-[10px] font-medium min-w-[14px] h-[14px] px-[3px] leading-none pointer-events-none">
        ${this.badge}
      </span>
    `;
  }

  render() {
    const classes  = this._baseClasses();
    const tooltip  = this.tooltip || '';

    if (this.href) {
      return html`
        <a
          href="${this.href}"
          class="${classes}"
          title="${tooltip}"
          aria-label="${tooltip}"
        >
          ${this._renderIcon()}
          ${this._renderBadge()}
        </a>
      `;
    }

    return html`
      <button
        type="button"
        class="${classes}"
        title="${tooltip}"
        aria-label="${tooltip}"
        ?disabled="${this.disabled}"
      >
        ${this._renderIcon()}
        ${this._renderBadge()}
      </button>
    `;
  }
}

customElements.define('ab-icon-button', AbIconButton);
