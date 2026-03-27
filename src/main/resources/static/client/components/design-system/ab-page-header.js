import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

// ---------------------------------------------------------------------------
// ab-page-header
// Standard page title + subtitle + optional CTA area used on every page.
//
// Props:
//   title     {String} — page title (required)
//   subtitle  {String} — optional secondary description below the title
//
// Slots:
//   actions — right-side CTA buttons / controls
// ---------------------------------------------------------------------------

class AbPageHeader extends LitElement {
  static properties = {
    title:    { type: String },
    subtitle: { type: String },
  };

  constructor() {
    super();
    this.title    = '';
    this.subtitle = '';
  }

  createRenderRoot() { return this; }

  render() {
    return html`
      <div class="flex items-start justify-between gap-4 mb-6">
        <div class="min-w-0">
          <h1 class="text-lg font-semibold text-white">${this.title}</h1>
          ${this.subtitle ? html`
            <p class="mt-1 text-sm text-gray-400">${this.subtitle}</p>
          ` : ''}
        </div>
        <div class="flex items-center gap-2 flex-shrink-0">
          <slot name="actions"></slot>
        </div>
      </div>
    `;
  }
}

if (!customElements.get('ab-page-header')) customElements.define('ab-page-header', AbPageHeader);
