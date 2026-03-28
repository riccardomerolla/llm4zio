import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

// ---------------------------------------------------------------------------
// ab-keyboard-shortcuts
// Global keyboard shortcut handler + help overlay.
//
// Registered shortcuts:
//   Ctrl/Cmd+K   — Open command palette (handled by ab-command-palette, documented here)
//   Ctrl/Cmd+N   — New chat → /chat/new
//   Ctrl/Cmd+Shift+M — Toggle memory/context panel
//   Ctrl/Cmd+Shift+G — Toggle git panel
//   Ctrl/Cmd+Enter   — Submit active composer form
//   Esc          — Close open ab-side-panel (or close help overlay)
//   ?            — Show/hide this help overlay (when not in an input)
// ---------------------------------------------------------------------------

class AbKeyboardShortcuts extends LitElement {
  static properties = {
    _showHelp: { type: Boolean, state: true },
  };

  constructor() {
    super();
    this._showHelp = false;
    this._mac      = /Mac|iPhone|iPad|iPod/.test(navigator.userAgent);
    this._onKeydown = this._onKeydown.bind(this);
  }

  createRenderRoot() { return this; }

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  connectedCallback() {
    super.connectedCallback();
    document.addEventListener('keydown', this._onKeydown);
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    document.removeEventListener('keydown', this._onKeydown);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  _isInputTarget(el) {
    if (!el) return false;
    const tag = el.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true;
    if (el.isContentEditable) return true;
    return false;
  }

  _dispatchPanelOpen(panelId, title) {
    window.dispatchEvent(
      new CustomEvent('ab-panel-open', {
        bubbles: true,
        composed: true,
        detail: { panelId, title },
      })
    );
  }

  // ── Keyboard handler ──────────────────────────────────────────────────────

  _onKeydown(e) {
    const target   = e.target;
    const inInput  = this._isInputTarget(target);
    const ctrl     = e.ctrlKey || e.metaKey;
    const shift    = e.shiftKey;

    // Ctrl/Cmd+N — New chat
    if (ctrl && !shift && e.key === 'n') {
      if (inInput) return;
      e.preventDefault();
      window.location.href = '/chat/new';
      return;
    }

    // Ctrl/Cmd+Shift+M — Memory/context panel
    if (ctrl && shift && e.key === 'M') {
      e.preventDefault();
      this._dispatchPanelOpen('context-panel', 'Memory & Context');
      return;
    }

    // Ctrl/Cmd+Shift+G — Git panel
    if (ctrl && shift && e.key === 'G') {
      e.preventDefault();
      this._dispatchPanelOpen('context-panel', 'Git Changes');
      return;
    }

    // Ctrl/Cmd+Enter — Submit composer form (fires even inside inputs)
    if (ctrl && !shift && e.key === 'Enter') {
      e.preventDefault();
      const form = document.querySelector('form [data-role="auto-grow"]')?.closest('form');
      if (form) form.requestSubmit();
      return;
    }

    // Esc — always fires
    if (e.key === 'Escape') {
      // Close open ab-side-panel first
      const openPanel = document.querySelector('ab-side-panel[open]');
      if (openPanel) {
        e.preventDefault();
        openPanel.dispatchEvent(new CustomEvent('panel-close', { bubbles: true, composed: true }));
        return;
      }
      // Otherwise close help overlay
      if (this._showHelp) {
        e.preventDefault();
        this._showHelp = false;
        return;
      }
      return;
    }

    // ? — show/hide help overlay (not when typing in an input)
    if (e.key === '?' && !inInput && !ctrl) {
      e.preventDefault();
      this._showHelp = !this._showHelp;
      return;
    }
  }

  // ── Help overlay ──────────────────────────────────────────────────────────

  _closeHelp() {
    this._showHelp = false;
  }

  _shortcuts() {
    const m = this._mac;
    return [
      { keys: m ? '⌘K'   : 'Ctrl+K',       label: 'Command palette' },
      { keys: m ? '⌘N'   : 'Ctrl+N',        label: 'New chat' },
      { keys: m ? '⌘⇧M'  : 'Ctrl+Shift+M', label: 'Memory panel' },
      { keys: m ? '⌘⇧G'  : 'Ctrl+Shift+G', label: 'Git panel' },
      { keys: m ? '⌘↵'   : 'Ctrl+Enter',   label: 'Send message' },
      { keys: 'Esc',                          label: 'Close panel' },
      { keys: '?',                            label: 'This help' },
    ];
  }

  render() {
    if (!this._showHelp) return html``;

    return html`
      <!-- Backdrop -->
      <div
        class="fixed inset-0 z-[9998] bg-black/60 backdrop-blur-sm"
        aria-hidden="true"
        @click="${this._closeHelp}"
      ></div>

      <!-- Help modal -->
      <div
        role="dialog"
        aria-label="Keyboard shortcuts"
        aria-modal="true"
        class="fixed inset-0 z-[9999] flex items-center justify-center px-4"
      >
        <div
          class="w-full max-w-sm rounded-xl border border-white/10 bg-slate-900 shadow-2xl shadow-black/60 overflow-hidden"
        >
          <!-- Header -->
          <div class="flex items-center justify-between border-b border-white/10 px-4 py-3">
            <span class="text-[13px] font-semibold text-white">Keyboard Shortcuts</span>
            <button
              type="button"
              class="rounded p-1 text-gray-400 hover:bg-white/10 hover:text-white transition-colors"
              aria-label="Close shortcuts help"
              @click="${this._closeHelp}"
            >
              <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24"
                fill="none" stroke="currentColor" stroke-width="2"
                stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M6 18 18 6M6 6l12 12"/>
              </svg>
            </button>
          </div>

          <!-- Shortcut table -->
          <ul class="py-2 px-2">
            ${this._shortcuts().map(s => html`
              <li class="flex items-center gap-3 px-2 py-1.5 rounded-md hover:bg-white/5 select-none">
                <kbd class="inline-flex items-center rounded border border-white/10 bg-white/5 px-2 py-0.5 font-mono text-[11px] text-indigo-300 whitespace-nowrap min-w-[5.5rem]">
                  ${s.keys}
                </kbd>
                <span class="text-[12px] text-gray-300">${s.label}</span>
              </li>
            `)}
          </ul>

          <!-- Footer -->
          <div class="border-t border-white/5 px-4 py-2 text-[10px] text-gray-600 select-none">
            Press <kbd class="font-mono">?</kbd> or <kbd class="font-mono">Esc</kbd> to close
          </div>
        </div>
      </div>
    `;
  }
}

if (!customElements.get('ab-keyboard-shortcuts')) {
  customElements.define('ab-keyboard-shortcuts', AbKeyboardShortcuts);
}
