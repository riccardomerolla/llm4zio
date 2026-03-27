import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

// ---------------------------------------------------------------------------
// ab-empty-state
// Consistent empty / zero-data placeholder used across all pages.
//
// Props:
//   headline     {String} — primary message (e.g. "No issues yet")
//   description  {String} — optional secondary text
//   icon         {String} — SVG path d-string; defaults to folder icon
//
// Slots:
//   default — optional action button / link
// ---------------------------------------------------------------------------

const DEFAULT_ICON =
  'M2.25 12.75V12A2.25 2.25 0 0 1 4.5 9.75h15A2.25 2.25 0 0 1 21.75 12v.75m-8.69-6.44-2.12-2.12a1.5 1.5 0 0 0-1.061-.44H4.5A2.25 2.25 0 0 0 2.25 6v12a2.25 2.25 0 0 0 2.25 2.25h15A2.25 2.25 0 0 0 21.75 18V9a2.25 2.25 0 0 0-2.25-2.25h-5.379a1.5 1.5 0 0 1-1.06-.44Z';

class AbEmptyState extends LitElement {
  static properties = {
    headline:    { type: String },
    description: { type: String },
    icon:        { type: String },
  };

  constructor() {
    super();
    this.headline    = '';
    this.description = '';
    this.icon        = '';
  }

  createRenderRoot() { return this; }

  render() {
    const iconPath = this.icon || DEFAULT_ICON;
    return html`
      <div class="flex flex-col items-center justify-center py-12 text-center">
        <svg
          class="mx-auto mb-4 h-10 w-10 text-gray-500"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
          aria-hidden="true"
        >
          <path stroke-linecap="round" stroke-linejoin="round" d="${iconPath}" />
        </svg>
        <p class="text-sm font-medium text-gray-300">${this.headline}</p>
        ${this.description ? html`
          <p class="mt-1 text-xs text-gray-500">${this.description}</p>
        ` : ''}
        <div class="mt-4">
          <slot></slot>
        </div>
      </div>
    `;
  }
}

if (!customElements.get('ab-empty-state')) customElements.define('ab-empty-state', AbEmptyState);
