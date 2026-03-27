import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

// ---------------------------------------------------------------------------
// ab-filter-bar
// Progressive-disclosure filter toolbar.
// Search is always visible; extra filters collapse behind a [Filters] button.
// Active filter chips are shown inline after the toggle.
//
// Slots:
//   search  — the always-visible search input
//   filters — additional filter fields shown when expanded
//   chips   — active filter chips (always visible, shown after Filters button)
// ---------------------------------------------------------------------------

class AbFilterBar extends LitElement {
  static properties = {
    _open: { type: Boolean, state: true },
  };

  constructor() {
    super();
    this._open = false;
  }

  createRenderRoot() { return this; }

  _toggle() {
    this._open = !this._open;
    this.dispatchEvent(new CustomEvent('ab-filters-toggle', {
      bubbles:  true,
      composed: true,
      detail:   { open: this._open },
    }));
  }

  render() {
    return html`
      <div class="space-y-2">

        <!-- Always-visible row: search + Filters button + active chips -->
        <div class="flex flex-wrap items-center gap-2">
          <slot name="search"></slot>

          <button
            type="button"
            class="inline-flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-sm text-gray-300
                   ring-1 ring-white/10 hover:bg-white/5 transition-colors ${this._open ? 'bg-white/10 text-white' : ''}"
            aria-expanded="${this._open}"
            @click="${this._toggle}"
          >
            <!-- funnel icon -->
            <svg class="h-4 w-4 flex-shrink-0" viewBox="0 0 24 24" fill="none"
                 stroke="currentColor" stroke-width="1.5" aria-hidden="true">
              <path stroke-linecap="round" stroke-linejoin="round"
                d="M12 3c2.755 0 5.455.232 8.083.678.533.09.917.556.917 1.096v1.044a2.25 2.25 0 0 1-.659 1.591L15.75
                   12.5v7.25a.75.75 0 0 1-.374.649l-4.5 2.5a.75.75 0 0 1-1.126-.65V12.5L4.659 7.409A2.25 2.25 0 0 1
                   4 5.818V4.774c0-.54.384-1.006.917-1.096A48.32 48.32 0 0 1 12 3Z"/>
            </svg>
            Filters
            <!-- chevron -->
            ${this._open
              ? html`<svg class="h-3 w-3" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M5.22 8.22a.75.75 0 0 1 1.06 0L10 11.94l3.72-3.72a.75.75 0 1 1 1.06 1.06l-4.25 4.25a.75.75 0 0 1-1.06 0L5.22 9.28a.75.75 0 0 1 0-1.06Z" clip-rule="evenodd"/></svg>`
              : html`<svg class="h-3 w-3" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M9.22 5.22a.75.75 0 0 1 1.06 0l4.25 4.25a.75.75 0 0 1-1.06 1.06L10 6.81 6.28 10.53a.75.75 0 0 1-1.06-1.06l4.25-4.25Z" clip-rule="evenodd"/></svg>`
            }
          </button>

          <!-- Active filter chips (always visible) -->
          <slot name="chips"></slot>
        </div>

        <!-- Expanded filter area -->
        ${this._open ? html`
          <div class="flex flex-wrap items-end gap-3 rounded-lg border border-white/10 bg-white/5 px-3 py-2.5">
            <slot name="filters"></slot>
          </div>
        ` : ''}

      </div>
    `;
  }
}

if (!customElements.get('ab-filter-bar')) customElements.define('ab-filter-bar', AbFilterBar);
