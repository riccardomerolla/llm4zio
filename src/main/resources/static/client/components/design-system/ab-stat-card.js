import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

// ---------------------------------------------------------------------------
// ab-stat-card
// Compact metric card with label, value, and optional trend indicator.
// Used on Command Center, SDLC Dashboard, and Board header.
//
// Props:
//   label          {String}  — metric name (e.g. "Agents", "Issues completed")
//   value          {String}  — primary value to display (e.g. "0 / 0", "42")
//   trend          {String}  — optional trend string (e.g. "↑3 vs last week")
//   trend-positive {Boolean} — true → emerald, false/absent → red trend color
// ---------------------------------------------------------------------------

class AbStatCard extends LitElement {
  static properties = {
    label:         { type: String },
    value:         { type: String },
    trend:         { type: String },
    trendPositive: { type: Boolean, attribute: 'trend-positive' },
  };

  constructor() {
    super();
    this.label         = '';
    this.value         = '';
    this.trend         = '';
    this.trendPositive = false;
  }

  createRenderRoot() { return this; }

  render() {
    const trendCls = this.trendPositive
      ? 'text-emerald-400'
      : 'text-red-400';

    return html`
      <div class="rounded-lg border border-white/10 bg-white/5 px-4 py-3">
        <p class="text-xs font-medium uppercase tracking-wide text-gray-400">${this.label}</p>
        <p class="mt-1 text-2xl font-semibold text-white tabular-nums">${this.value}</p>
        ${this.trend ? html`
          <p class="mt-0.5 text-xs ${trendCls}">${this.trend}</p>
        ` : ''}
      </div>
    `;
  }
}

if (!customElements.get('ab-stat-card')) customElements.define('ab-stat-card', AbStatCard);
