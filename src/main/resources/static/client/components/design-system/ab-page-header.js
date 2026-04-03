import { LitElement, html, nothing } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

// ---------------------------------------------------------------------------
// ab-page-header
// Compact inline header bar inspired by the issue timeline header.
// Single-row layout: [back link] [badges/metadata] [title] ... [actions]
//
// Design: rounded card with subtle border, glass-like backdrop blur,
// optional sticky positioning to stay visible during scroll.
//
// Props:
//   title     {String}  - primary heading text (truncated when space is tight)
//   subtitle  {String}  - optional secondary text shown after title on wide screens
//   back-href {String}  - if set, renders a "back" link on the far left
//   back-text {String}  - label for the back link (default: "Back")
//   sticky    {Boolean} - when present, header sticks below the top nav bar
//
// Slots:
//   badges  - inline badges / metadata pills between back-link and title
//   actions - right-aligned action buttons
// ---------------------------------------------------------------------------

class AbPageHeader extends LitElement {
  static properties = {
    title:    { type: String },
    subtitle: { type: String },
    backHref: { type: String, attribute: 'back-href' },
    backText: { type: String, attribute: 'back-text' },
    sticky:   { type: Boolean },
  };

  constructor() {
    super();
    this.title    = '';
    this.subtitle = '';
    this.backHref = '';
    this.backText = 'Back';
    this.sticky   = false;
  }

  createRenderRoot() { return this; }

  render() {
    const stickyClass = this.sticky
      ? 'sticky top-10 z-20 shadow-lg backdrop-blur bg-slate-900/95'
      : 'bg-slate-900/70';

    return html`
      <div class="flex items-center gap-3 rounded-xl border border-white/10 px-4 py-2.5 ${stickyClass}">

        ${this.backHref ? html`
          <a href="${this.backHref}"
             class="flex-shrink-0 rounded-md border border-white/10 bg-white/5 px-2.5 py-1 text-xs text-slate-300 hover:bg-white/10 transition-colors">
            &larr; ${this.backText}
          </a>
        ` : nothing}

        <slot name="badges"></slot>

        <h1 class="min-w-0 flex-1 truncate text-sm font-semibold text-white">
          ${this.title}
          ${this.subtitle ? html`
            <span class="ml-2 hidden font-normal text-slate-400 sm:inline">${this.subtitle}</span>
          ` : nothing}
        </h1>

        <div class="flex items-center gap-2 flex-shrink-0">
          <slot name="actions"></slot>
        </div>
      </div>
    `;
  }
}

if (!customElements.get('ab-page-header')) customElements.define('ab-page-header', AbPageHeader);
