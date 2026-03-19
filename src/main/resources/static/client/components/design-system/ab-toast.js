import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

// ---------------------------------------------------------------------------
// ab-toast
// Auto-dismissing toast notification stack.
//
// Usage — programmatic (recommended):
//   AbToast.show({ type: 'success', message: 'Saved!', duration: 4000 });
//
// Usage — declarative (single toast):
//   <ab-toast type="error" message="Something went wrong"></ab-toast>
//
// Props (declarative):
//   type      {String}  — 'success' | 'error' | 'info' | 'warning'
//   message   {String}  — notification text
//   duration  {Number}  — auto-dismiss ms (0 = sticky, default 4000)
// ---------------------------------------------------------------------------

const TOAST_CONFIG = {
  success: { icon: '✓', classes: 'bg-green-500/15 ring-green-500/30 text-green-300'  },
  error:   { icon: '✕', classes: 'bg-red-500/15   ring-red-500/30   text-red-300'    },
  info:    { icon: 'ℹ', classes: 'bg-blue-500/15  ring-blue-500/30  text-blue-300'   },
  warning: { icon: '⚠', classes: 'bg-yellow-500/15 ring-yellow-500/30 text-yellow-300' },
};

// Global singleton store -- shared among all <ab-toast> instances
const _toasts  = [];
let   _nextId  = 1;
const _listeners = new Set();

function _notify() {
  _listeners.forEach(fn => fn([..._toasts]));
}

class AbToast extends LitElement {
  static properties = {
    type:     { type: String },
    message:  { type: String },
    duration: { type: Number },
    _stack:   { state: true },
  };

  constructor() {
    super();
    this.type     = 'info';
    this.message  = '';
    this.duration = 4000;
    this._stack   = [..._toasts];
    this._listener = (stack) => { this._stack = stack; };
  }

  // ── Static helper for programmatic use ────────────────────────────────────

  static show({ type = 'info', message = '', duration = 4000 } = {}) {
    const id = _nextId++;
    _toasts.push({ id, type, message, duration });
    _notify();
    if (duration > 0) {
      setTimeout(() => AbToast._dismiss(id), duration);
    }
  }

  static _dismiss(id) {
    const idx = _toasts.findIndex(t => t.id === id);
    if (idx !== -1) {
      _toasts.splice(idx, 1);
      _notify();
    }
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  connectedCallback() {
    super.connectedCallback();
    _listeners.add(this._listener);

    // If used declaratively (message prop set), push a toast entry once
    if (this.message) {
      AbToast.show({ type: this.type, message: this.message, duration: this.duration });
    }
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    _listeners.delete(this._listener);
  }

  createRenderRoot() { return this; }

  // ── Rendering ──────────────────────────────────────────────────────────────

  _renderToast(toast) {
    const cfg = TOAST_CONFIG[toast.type] ?? TOAST_CONFIG.info;
    return html`
      <div
        class="flex items-start gap-3 rounded-lg px-4 py-3 text-sm shadow-lg ring-1 ${cfg.classes} animate-[fadeInUp_0.2s_ease-out]"
        role="alert"
        aria-live="assertive"
      >
        <span class="mt-0.5 text-base leading-none" aria-hidden="true">${cfg.icon}</span>
        <span class="flex-1">${toast.message}</span>
        <button
          type="button"
          class="ml-2 shrink-0 opacity-60 hover:opacity-100 transition-opacity"
          aria-label="Dismiss"
          @click="${() => AbToast._dismiss(toast.id)}"
        >
          <svg class="h-4 w-4" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
            <path d="M6.28 5.22a.75.75 0 0 0-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 1 0 1.06 1.06L10 11.06l3.72 3.72a.75.75 0 1 0 1.06-1.06L11.06 10l3.72-3.72a.75.75 0 0 0-1.06-1.06L10 8.94 6.28 5.22Z"/>
          </svg>
        </button>
      </div>
    `;
  }

  render() {
    if (this._stack.length === 0) return html``;
    return html`
      <div
        class="fixed bottom-4 right-4 z-[100] flex flex-col gap-2 w-80 max-w-[calc(100vw-2rem)]"
        aria-atomic="false"
        aria-label="Notifications"
      >
        ${this._stack.map(t => this._renderToast(t))}
      </div>
    `;
  }
}

if (!customElements.get('ab-toast')) customElements.define('ab-toast', AbToast);
