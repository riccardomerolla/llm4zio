import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

// ---------------------------------------------------------------------------
// ab-card
// Content card container with optional title.
//
// Props:
//   title  {String}  — optional header title
//
// Slots:
//   default — card body content
// ---------------------------------------------------------------------------

class AbCard extends LitElement {
  static properties = {
    title: { type: String },
  };

  constructor() {
    super();
    this.title = '';
  }

  createRenderRoot() { return this; }

  render() {
    return html`
      <div class="bg-white/5 ring-1 ring-white/10 rounded-lg overflow-hidden">
        ${this.title ? html`
          <div class="px-6 py-4 border-b border-white/10">
            <h3 class="text-sm font-semibold text-white">${this.title}</h3>
          </div>
        ` : ''}
        <div class="p-6">
          <slot></slot>
        </div>
      </div>
    `;
  }
}

customElements.define('ab-card', AbCard);
