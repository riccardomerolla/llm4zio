import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

// ---------------------------------------------------------------------------
// ab-modal
// Modal dialog with backdrop.
//
// Props:
//   open   {Boolean}  — controls visibility, reflected to attribute
//   title  {String}   — modal header title
//
// Events:
//   ab-close — dispatched when the user closes the modal (close button or backdrop click)
//
// Slots:
//   default — modal body content
// ---------------------------------------------------------------------------

class AbModal extends LitElement {
  static properties = {
    open:  { type: Boolean, reflect: true },
    title: { type: String },
  };

  constructor() {
    super();
    this.open  = false;
    this.title = '';
  }

  createRenderRoot() { return this; }

  _close() {
    this.open = false;
    this.dispatchEvent(new CustomEvent('ab-close', { bubbles: true, composed: true }));
  }

  _onBackdropClick(e) {
    if (e.target === e.currentTarget) this._close();
  }

  render() {
    if (!this.open) return html``;
    return html`
      <div
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
        @click="${this._onBackdropClick}"
        role="dialog"
        aria-modal="true"
        aria-label="${this.title}"
      >
        <div class="relative w-full max-w-lg rounded-lg bg-gray-800 ring-1 ring-white/10 shadow-xl">
          <!-- Header -->
          <div class="flex items-center justify-between px-6 py-4 border-b border-white/10">
            <h2 class="text-sm font-semibold text-white">${this.title}</h2>
            <button
              type="button"
              class="text-gray-400 hover:text-white transition-colors"
              aria-label="Close"
              @click="${this._close}"
            >
              <svg class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
                <path d="M6.28 5.22a.75.75 0 0 0-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 1 0 1.06 1.06L10 11.06l3.72 3.72a.75.75 0 1 0 1.06-1.06L11.06 10l3.72-3.72a.75.75 0 0 0-1.06-1.06L10 8.94 6.28 5.22Z"/>
              </svg>
            </button>
          </div>
          <!-- Body -->
          <div class="px-6 py-4">
            <slot></slot>
          </div>
        </div>
      </div>
    `;
  }
}

customElements.define('ab-modal', AbModal);
