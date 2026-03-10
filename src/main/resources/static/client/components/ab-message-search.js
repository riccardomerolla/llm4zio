import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

/**
 * ab-message-search — full-text search across conversation messages
 *
 * Frontend-only: scans DOM elements with [data-sender] for message text.
 * Renders results with highlighted match snippet, sender label, and timestamp.
 * Clicking a result scrolls to the matching message in the timeline.
 *
 * Max 20 results. No shadow DOM (createRenderRoot returns this).
 */
class AbMessageSearch extends LitElement {
  static properties = {
    _query:   { type: String,  state: true },
    _results: { type: Array,   state: true },
  };

  constructor() {
    super();
    this._query   = '';
    this._results = [];
    this._onInput = this._onInput.bind(this);
  }

  createRenderRoot() { return this; }

  // ── Search logic ────────────────────────────────────────────────────────────

  /**
   * Collect all searchable message elements from the DOM.
   * We look for elements with a data-sender attribute and extract:
   *   - the text node to search within (text-sm or text-gray-50 children,
   *     or the element's own textContent as fallback)
   *   - the sender label
   *   - the timestamp (if present via a time element or data-ts attribute)
   */
  _collectMessages() {
    const wrappers = Array.from(
      document.querySelectorAll('[data-sender="user"], [data-sender="assistant"]')
    );
    const entries = [];
    for (const wrapper of wrappers) {
      const sender = wrapper.getAttribute('data-sender') || 'unknown';
      // Prefer the markdown or pre content divs; fall back to full textContent
      const textEl =
        wrapper.querySelector('.text-sm') ||
        wrapper.querySelector('.text-gray-50') ||
        wrapper.querySelector('pre') ||
        wrapper;
      const text = (textEl.textContent || '').trim();
      if (!text) continue;
      // Attempt to extract timestamp from a sibling or ancestor time element
      const timeEl =
        wrapper.querySelector('time') ||
        wrapper.closest('[data-ts]');
      const ts = timeEl
        ? (timeEl.getAttribute('datetime') || timeEl.getAttribute('data-ts') || '')
        : '';
      entries.push({ wrapper, sender, text, ts });
    }
    return entries;
  }

  _search(query) {
    const q = query.trim();
    if (!q) return [];
    const lower = q.toLowerCase();
    const messages = this._collectMessages();
    const results = [];
    for (const { wrapper, sender, text, ts } of messages) {
      const textLower = text.toLowerCase();
      const idx = textLower.indexOf(lower);
      if (idx === -1) continue;
      // Build a snippet: up to 30 chars before, the match, up to 30 chars after
      const before = text.slice(Math.max(0, idx - 30), idx);
      const match  = text.slice(idx, idx + q.length);
      const after  = text.slice(idx + q.length, idx + q.length + 30);
      results.push({ wrapper, sender, before, match, after, ts });
      if (results.length >= 20) break;
    }
    return results;
  }

  // ── Event handlers ──────────────────────────────────────────────────────────

  _onInput(e) {
    this._query   = e.target.value;
    this._results = this._search(this._query);
  }

  _scrollTo(wrapper) {
    wrapper.scrollIntoView({ behavior: 'smooth', block: 'center' });
    // Brief highlight flash to draw attention
    wrapper.style.transition = 'background-color 300ms ease-out';
    wrapper.style.backgroundColor = 'rgba(99,102,241,0.15)';
    setTimeout(() => {
      wrapper.style.backgroundColor = '';
    }, 1200);
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  _senderLabel(sender) {
    if (sender === 'user')      return 'User';
    if (sender === 'assistant') return 'Assistant';
    return sender;
  }

  _senderColor(sender) {
    return sender === 'user' ? 'text-indigo-300' : 'text-slate-400';
  }

  // ── Render ───────────────────────────────────────────────────────────────────

  _renderEmptyState() {
    if (this._query.trim()) {
      return html`
        <p class="text-[11px] text-gray-500 text-center py-6">
          No messages found for &ldquo;${this._query.trim()}&rdquo;
        </p>
      `;
    }
    return html`
      <p class="text-[11px] text-gray-500 text-center py-6">
        Type to search messages
      </p>
    `;
  }

  render() {
    const hasResults = this._results.length > 0;

    return html`
      <div class="flex flex-col gap-2">

        <!-- Search input -->
        <div class="relative">
          <!-- magnifier icon -->
          <svg
            xmlns="http://www.w3.org/2000/svg"
            class="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-gray-500 pointer-events-none"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 1 0 5.197 5.197a7.5 7.5 0 0 0 10.606 10.606Z" />
          </svg>
          <input
            type="search"
            placeholder="Search messages..."
            .value="${this._query}"
            @input="${this._onInput}"
            class="w-full rounded-md border border-white/10 bg-black/30 pl-8 pr-3 py-1.5 text-[12px] text-gray-100 placeholder:text-gray-500 focus:border-indigo-400/60 focus:outline-none focus:ring-1 focus:ring-indigo-500/30"
            autocomplete="off"
            spellcheck="false"
          />
        </div>

        <!-- Divider -->
        <div class="border-t border-white/8"></div>

        <!-- Results or empty state -->
        ${hasResults
          ? html`
            <div class="space-y-1">
              ${this._results.map((r, i) => html`
                <button
                  type="button"
                  key="${i}"
                  class="w-full text-left rounded-md px-3 py-2 hover:bg-white/5 focus:bg-white/5 focus:outline-none transition-colors group"
                  @click="${() => this._scrollTo(r.wrapper)}"
                  title="Jump to message"
                >
                  <!-- Sender row -->
                  <div class="flex items-baseline gap-1.5 mb-0.5">
                    <span class="text-[11px] font-semibold ${this._senderColor(r.sender)}">${this._senderLabel(r.sender)}</span>
                    ${r.ts ? html`<span class="text-[10px] text-gray-600">${r.ts}</span>` : html``}
                  </div>
                  <!-- Snippet with highlighted match -->
                  <p class="text-[11px] text-gray-400 leading-relaxed truncate">
                    ${r.before ? html`<span>${r.before}</span>` : html``}<mark
                      class="bg-yellow-400/30 text-yellow-200 rounded px-0.5 not-italic"
                    >${r.match}</mark>${r.after ? html`<span>${r.after}</span>` : html``}
                  </p>
                </button>
              `)}
            </div>
          `
          : this._renderEmptyState()
        }
      </div>
    `;
  }
}

if (!customElements.get('ab-message-search')) {
  customElements.define('ab-message-search', AbMessageSearch);
}
