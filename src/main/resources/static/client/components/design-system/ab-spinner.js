import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

// ---------------------------------------------------------------------------
// ab-spinner
// Animated SVG loading spinner.
//
// Props:
//   size   {String}  — 'sm' (h-4 w-4) | 'md' (h-6 w-6, default) | 'lg' (h-8 w-8)
//   label  {String}  — accessible label (aria-label), default 'Loading'
// ---------------------------------------------------------------------------

const SIZE_CLASSES = {
  sm: 'h-4 w-4',
  md: 'h-6 w-6',
  lg: 'h-8 w-8',
};

class AbSpinner extends LitElement {
  static properties = {
    size:  { type: String },
    label: { type: String },
  };

  constructor() {
    super();
    this.size  = 'md';
    this.label = 'Loading';
  }

  createRenderRoot() { return this; }

  render() {
    const sz = SIZE_CLASSES[this.size] ?? SIZE_CLASSES.md;
    return html`
      <span class="htmx-indicator flex justify-center items-center" role="status" aria-label="${this.label}">
        <svg
          class="animate-spin ${sz} text-indigo-400"
          xmlns="http://www.w3.org/2000/svg"
          fill="none"
          viewBox="0 0 24 24"
          aria-hidden="true"
        >
          <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
          <path
            class="opacity-75"
            fill="currentColor"
            d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
          ></path>
        </svg>
        <span class="sr-only">${this.label}</span>
      </span>
    `;
  }
}

customElements.define('ab-spinner', AbSpinner);
