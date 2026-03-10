import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

// ---------------------------------------------------------------------------
// ab-command-palette
// VS Code / Claude Code style command palette overlay.
// Opens on Ctrl+K / Cmd+K or via window event 'ab-command-palette-open'.
// ---------------------------------------------------------------------------

class AbCommandPalette extends LitElement {
  static properties = {
    open:        { type: Boolean, reflect: true },
    query:       { type: String },
    activeIndex: { type: Number },
  };

  constructor() {
    super();
    this.open        = false;
    this.query       = '';
    this.activeIndex = 0;
    this._mac        = /Mac|iPhone|iPad|iPod/.test(navigator.userAgent);

    this._onKeydown      = this._onKeydown.bind(this);
    this._openPalette    = this._openPalette.bind(this);
  }

  createRenderRoot() { return this; }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  connectedCallback() {
    super.connectedCallback();
    document.addEventListener('keydown', this._onKeydown);
    window.addEventListener('ab-command-palette-open', this._openPalette);
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    document.removeEventListener('keydown', this._onKeydown);
    window.removeEventListener('ab-command-palette-open', this._openPalette);
  }

  updated(changedProps) {
    if (changedProps.has('open') && this.open) {
      // Auto-focus the search input after render
      this.updateComplete.then(() => {
        const input = this.querySelector('#cp-search-input');
        if (input) input.focus();
      });
    }
  }

  // ── Data ───────────────────────────────────────────────────────────────────

  _getRecentChats() {
    // Prefer [data-palette-chat] links, fall back to .sidebar-chat-link
    const selector = 'a[data-palette-chat], a.sidebar-chat-link';
    const links = Array.from(document.querySelectorAll(selector));
    const seen  = new Set();
    const items = [];
    for (const link of links) {
      const href  = link.getAttribute('href') || '';
      const label = (link.textContent || '').trim();
      if (!href || !label || seen.has(href)) continue;
      seen.add(href);
      items.push({ type: 'chat', label, href });
    }
    return items;
  }

  _getActions() {
    return [
      {
        type: 'action',
        label: 'New chat',
        shortcut: this._mac ? '⌘N' : 'Ctrl+N',
        icon: 'plus',
        action: () => { window.location.href = '/chat/new'; },
      },
      {
        type: 'action',
        label: 'Toggle sidebar',
        shortcut: this._mac ? '⌘/' : 'Ctrl+/',
        icon: 'sidebar',
        action: () => {
          // Mirror what the desktop sidebar toggle does
          const btn = document.getElementById('desktop-sidebar-toggle');
          if (btn) {
            btn.click();
          } else {
            window.dispatchEvent(new CustomEvent('toggle-sidebar'));
          }
        },
      },
    ];
  }

  _allItems() {
    const chats   = this._getRecentChats();
    const actions = this._getActions();
    const all     = [];
    // Groups: recent chats first, then actions
    if (chats.length > 0) {
      all.push({ type: 'group', label: 'RECENT CHATS' });
      all.push(...chats.map((c, i) => ({ ...c, _idx: i })));
    }
    all.push({ type: 'group', label: 'ACTIONS' });
    all.push(...actions);
    return all;
  }

  _filteredItems() {
    const q = (this.query || '').trim().toLowerCase();
    if (!q) return this._allItems();

    const chats   = this._getRecentChats().filter(c =>
      c.label.toLowerCase().includes(q)
    );
    const actions = this._getActions().filter(a =>
      a.label.toLowerCase().includes(q)
    );

    const all = [];
    if (chats.length > 0) {
      all.push({ type: 'group', label: 'RECENT CHATS' });
      all.push(...chats);
    }
    if (actions.length > 0) {
      all.push({ type: 'group', label: 'ACTIONS' });
      all.push(...actions);
    }
    return all;
  }

  // Returns only non-group items (selectable rows)
  _selectableItems() {
    return this._filteredItems().filter(item => item.type !== 'group');
  }

  // ── Event handlers ─────────────────────────────────────────────────────────

  _onKeydown(e) {
    // Toggle open with Ctrl+K / Cmd+K
    if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
      e.preventDefault();
      if (this.open) {
        this._close();
      } else {
        this._openPalette();
      }
      return;
    }

    if (!this.open) return;

    if (e.key === 'Escape') {
      e.preventDefault();
      this._close();
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      const max = this._selectableItems().length - 1;
      this.activeIndex = Math.min(this.activeIndex + 1, max);
      this._scrollActiveIntoView();
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      this.activeIndex = Math.max(this.activeIndex - 1, 0);
      this._scrollActiveIntoView();
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const items = this._selectableItems();
      if (items[this.activeIndex]) {
        this._activateItem(items[this.activeIndex]);
      }
    }
  }

  _openPalette() {
    this.query       = '';
    this.activeIndex = 0;
    this.open        = true;
  }

  _close() {
    this.open  = false;
    this.query = '';
  }

  _onQueryInput(e) {
    this.query       = e.target.value;
    this.activeIndex = 0;
  }

  _activateItem(item) {
    if (item.type === 'chat') {
      this._close();
      window.location.href = item.href;
    } else if (item.type === 'action' && typeof item.action === 'function') {
      this._close();
      item.action();
    }
  }

  _scrollActiveIntoView() {
    this.updateComplete.then(() => {
      const el = this.querySelector(`[data-cp-index="${this.activeIndex}"]`);
      if (el) el.scrollIntoView({ block: 'nearest' });
    });
  }

  // ── SVG icons ──────────────────────────────────────────────────────────────

  _iconSvg(name) {
    const paths = {
      plus:    'M12 4.5v15m7.5-7.5h-15',
      search:  'M21 21l-5.197-5.197m0 0A7.5 7.5 0 1 0 5.196 5.196a7.5 7.5 0 0 0 10.607 10.607Z',
      sidebar: 'M3.75 6.75h16.5M3.75 12h16.5m-16.5 5.25H12',
      chat:    'M7.5 8.25h9m-9 3H12m-9.75 1.51c0 1.6 1.123 2.994 2.707 3.227 1.129.166 2.27.293 3.423.379.35.026.67.21.865.501L12 21l2.755-4.133a1.14 1.14 0 0 1 .865-.501 48.172 48.172 0 0 0 3.423-.379c1.584-.233 2.707-1.626 2.707-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0 0 12 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018Z',
      x:       'M6 18 18 6M6 6l12 12',
    };
    const d = paths[name] || paths.x;
    return html`
      <svg
        xmlns="http://www.w3.org/2000/svg"
        width="14"
        height="14"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.75"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
        class="shrink-0"
      ><path d="${d}" /></svg>
    `;
  }

  // ── Render helpers ─────────────────────────────────────────────────────────

  _renderItem(item, selectableIndex) {
    const isActive = selectableIndex === this.activeIndex;
    const baseRow  =
      'flex items-center gap-2.5 px-3 py-1.5 rounded-md cursor-pointer select-none transition-colors';
    const activeRow   = 'bg-indigo-600/30 text-white';
    const inactiveRow = 'text-gray-300 hover:bg-white/5 hover:text-white';

    if (item.type === 'chat') {
      return html`
        <li
          id="cp-option-${selectableIndex}"
          role="option"
          data-cp-index="${selectableIndex}"
          aria-selected="${isActive}"
          class="${baseRow} ${isActive ? activeRow : inactiveRow}"
          @click="${() => this._activateItem(item)}"
          @mouseenter="${() => { this.activeIndex = selectableIndex; }}"
        >
          <span class="text-indigo-400 shrink-0">${this._iconSvg('chat')}</span>
          <span class="flex-1 truncate text-[12px]">${item.label}</span>
        </li>
      `;
    }

    // action
    return html`
      <li
        id="cp-option-${selectableIndex}"
        role="option"
        data-cp-index="${selectableIndex}"
        aria-selected="${isActive}"
        class="${baseRow} ${isActive ? activeRow : inactiveRow}"
        @click="${() => this._activateItem(item)}"
        @mouseenter="${() => { this.activeIndex = selectableIndex; }}"
      >
        <span class="text-gray-400 shrink-0">${this._iconSvg(item.icon || 'plus')}</span>
        <span class="flex-1 truncate text-[12px]">${item.label}</span>
        ${item.shortcut
          ? html`<kbd class="hidden sm:inline-flex items-center rounded border border-white/10 bg-white/5 px-1.5 py-0.5 text-[10px] text-gray-500 font-mono">${item.shortcut}</kbd>`
          : ''}
      </li>
    `;
  }

  _renderList() {
    const filtered = this._filteredItems();
    let selectableCounter = 0;

    return html`
      <ul id="cp-results-list" class="py-1 space-y-0.5" role="listbox" aria-label="Command palette results">
        ${filtered.map(item => {
          if (item.type === 'group') {
            return html`
              <li class="px-3 pt-2 pb-0.5 text-[10px] font-semibold uppercase tracking-widest text-gray-500 select-none" role="presentation">
                ${item.label}
              </li>
            `;
          }
          const idx = selectableCounter++;
          return this._renderItem(item, idx);
        })}
        ${filtered.filter(i => i.type !== 'group').length === 0
          ? html`<li class="px-3 py-3 text-center text-[12px] text-gray-500">No results</li>`
          : ''}
      </ul>
    `;
  }

  // ── Render ─────────────────────────────────────────────────────────────────

  render() {
    if (!this.open) return html``;

    const triggerK = this._mac ? '⌘K' : 'Ctrl+K';

    return html`
      <!-- Backdrop -->
      <div
        class="fixed inset-0 z-[9998] bg-black/60 backdrop-blur-sm"
        aria-hidden="true"
        @click="${() => this._close()}"
      ></div>

      <!-- Palette modal -->
      <div
        role="dialog"
        aria-label="Command palette"
        aria-modal="true"
        class="fixed inset-x-0 top-[15vh] z-[9999] mx-auto w-full max-w-xl px-4"
      >
        <div class="rounded-xl border border-white/10 bg-slate-900 shadow-2xl shadow-black/60 overflow-hidden">

          <!-- Search bar -->
          <div class="flex items-center gap-2 border-b border-white/10 px-3 py-2.5">
            <span class="text-[10px] font-mono font-semibold text-indigo-400 shrink-0 select-none">${triggerK}</span>
            <span class="text-gray-600 shrink-0 select-none">›</span>
            <input
              id="cp-search-input"
              type="text"
              autocomplete="off"
              autocorrect="off"
              autocapitalize="off"
              spellcheck="false"
              placeholder="Search commands..."
              aria-autocomplete="list"
              aria-controls="cp-results-list"
              aria-activedescendant="${this.activeIndex >= 0 ? 'cp-option-' + this.activeIndex : ''}"
              class="flex-1 bg-transparent text-[13px] text-gray-100 placeholder:text-gray-500 outline-none min-w-0"
              .value="${this.query}"
              @input="${this._onQueryInput}"
            />
            <button
              type="button"
              class="shrink-0 rounded p-1 text-gray-500 hover:text-white hover:bg-white/10 transition-colors"
              aria-label="Close command palette"
              @click="${() => this._close()}"
            >${this._iconSvg('x')}</button>
          </div>

          <!-- Results list -->
          <div class="max-h-80 overflow-y-auto px-2 pb-2">
            ${this._renderList()}
          </div>

          <!-- Footer hint -->
          <div class="border-t border-white/5 px-3 py-1.5 flex items-center gap-3 text-[10px] text-gray-600 select-none">
            <span><kbd class="font-mono">↑↓</kbd> navigate</span>
            <span><kbd class="font-mono">↵</kbd> select</span>
            <span><kbd class="font-mono">Esc</kbd> close</span>
          </div>

        </div>
      </div>
    `;
  }
}

if (!customElements.get('ab-command-palette')) {
  customElements.define('ab-command-palette', AbCommandPalette);
}
