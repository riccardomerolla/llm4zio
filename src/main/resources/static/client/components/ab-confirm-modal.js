import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

class AbConfirmModal extends LitElement {
  static properties = {
    open:        { type: Boolean, reflect: true },
    heading:     { type: String },
    message:     { type: String },
    confirmText: { type: String, attribute: 'confirm-text' },
    cancelText:  { type: String, attribute: 'cancel-text' },
    variant:     { type: String },
  };

  constructor() {
    super();
    this.open        = false;
    this.heading     = 'Confirm';
    this.message     = '';
    this.confirmText = 'Confirm';
    this.cancelText  = 'Cancel';
    this.variant     = 'default'; // 'default' | 'danger'
    this._resolve    = null;
  }

  createRenderRoot() { return this; }

  /**
   * Show the modal and return a Promise that resolves to true (confirmed) or false (cancelled).
   */
  confirm({ heading, message, confirmText, cancelText, variant } = {}) {
    if (heading) this.heading = heading;
    if (message) this.message = message;
    if (confirmText) this.confirmText = confirmText;
    if (cancelText) this.cancelText = cancelText;
    if (variant) this.variant = variant;
    this.open = true;
    this.requestUpdate();
    return new Promise((resolve) => {
      this._resolve = resolve;
    });
  }

  _onConfirm() {
    this.open = false;
    this._resolve?.(true);
    this._resolve = null;
  }

  _onCancel() {
    this.open = false;
    this._resolve?.(false);
    this._resolve = null;
  }

  _onKeydown(e) {
    if (e.key === 'Escape') this._onCancel();
  }

  _onBackdropClick(e) {
    if (e.target === e.currentTarget) this._onCancel();
  }

  updated(changed) {
    if (changed.has('open') && this.open) {
      const btn = this.querySelector('[data-role="confirm-btn"]');
      btn?.focus();
    }
  }

  render() {
    if (!this.open) return html``;

    const confirmCls = this.variant === 'danger'
      ? 'bg-rose-600 hover:bg-rose-500 text-white'
      : 'bg-emerald-600 hover:bg-emerald-500 text-white';

    return html`
      <div class="fixed inset-0 z-[9999] flex items-center justify-center bg-black/60 backdrop-blur-sm"
           @click=${this._onBackdropClick}
           @keydown=${this._onKeydown}>
        <div class="w-full max-w-sm rounded-xl border border-white/10 bg-slate-900 p-5 shadow-2xl"
             @click=${(e) => e.stopPropagation()}>
          <h3 class="text-sm font-semibold text-white">${this.heading}</h3>
          <p class="mt-2 text-xs text-slate-300">${this.message}</p>
          <div class="mt-4 flex justify-end gap-2">
            <button type="button"
                    class="rounded-lg border border-white/10 bg-white/5 px-3 py-1.5 text-xs font-medium text-slate-300 hover:bg-white/10"
                    @click=${this._onCancel}>
              ${this.cancelText}
            </button>
            <button type="button"
                    data-role="confirm-btn"
                    class="rounded-lg px-3 py-1.5 text-xs font-semibold ${confirmCls}"
                    @click=${this._onConfirm}>
              ${this.confirmText}
            </button>
          </div>
        </div>
      </div>
    `;
  }
}

if (!customElements.get('ab-confirm-modal')) {
  customElements.define('ab-confirm-modal', AbConfirmModal);
}
