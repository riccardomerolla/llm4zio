import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

// ---------------------------------------------------------------------------
// ab-badge
// General-purpose status / label badge.
//
// Props:
//   text     {String}  — display text
//   variant  {String}  — 'default' | 'success' | 'warning' | 'error' |
//                         'info' | 'indigo' | 'purple' | 'pink' | 'gray'
// ---------------------------------------------------------------------------

const BADGE_VARIANTS = {
  default: 'bg-gray-500/10   ring-gray-500/20   text-gray-400',
  success: 'bg-green-500/10  ring-green-500/20  text-green-400',
  warning: 'bg-yellow-500/10 ring-yellow-500/20 text-yellow-400',
  error:   'bg-red-500/10    ring-red-500/20    text-red-400',
  info:    'bg-blue-500/10   ring-blue-500/20   text-blue-400',
  indigo:  'bg-indigo-500/10 ring-indigo-500/20 text-indigo-400',
  purple:  'bg-purple-500/10 ring-purple-500/20 text-purple-400',
  pink:    'bg-pink-500/10   ring-pink-500/20   text-pink-400',
  gray:    'bg-gray-500/10   ring-gray-500/20   text-gray-400',
  amber:   'bg-amber-500/10  ring-amber-500/20  text-amber-400',
};

class AbBadge extends LitElement {
  static properties = {
    text:    { type: String },
    variant: { type: String },
  };

  constructor() {
    super();
    this.text    = '';
    this.variant = 'default';
  }

  createRenderRoot() { return this; }

  render() {
    const cls = BADGE_VARIANTS[this.variant] ?? BADGE_VARIANTS.default;
    return html`
      <span class="inline-flex items-center rounded-md px-2 py-1 text-xs font-medium ${cls} ring-1 ring-inset">
        ${this.text}
      </span>
    `;
  }
}

if (!customElements.get('ab-badge')) customElements.define('ab-badge', AbBadge);
