import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

// ---------------------------------------------------------------------------
// ab-progress-bar
// Animated fill progress bar.
//
// Props:
//   value  {Number}  — current value (default 0)
//   max    {Number}  — maximum value (default 100)
//   label  {String}  — optional overlay label; displays pct if empty
// ---------------------------------------------------------------------------

class AbProgressBar extends LitElement {
  static properties = {
    value: { type: Number },
    max:   { type: Number },
    label: { type: String },
  };

  constructor() {
    super();
    this.value = 0;
    this.max   = 100;
    this.label = '';
  }

  createRenderRoot() { return this; }

  _pct() {
    if (this.max <= 0) return 0;
    return Math.min(100, Math.max(0, Math.round((this.value / this.max) * 100)));
  }

  render() {
    const pct = this._pct();
    const displayLabel = this.label || `${pct}%`;
    return html`
      <div class="w-full" role="progressbar" aria-valuenow="${this.value}" aria-valuemin="0" aria-valuemax="${this.max}" aria-label="${displayLabel}">
        <div class="flex items-center justify-between mb-1">
          <span class="text-xs text-gray-400">${displayLabel}</span>
          <span class="text-xs text-gray-500 tabular-nums">${this.value}/${this.max}</span>
        </div>
        <div class="w-full bg-white/10 rounded-full h-2.5 overflow-hidden">
          <div
            class="bg-indigo-500 h-2.5 rounded-full transition-all duration-300 ease-out"
            style="width: ${pct}%"
          ></div>
        </div>
      </div>
    `;
  }
}

customElements.define('ab-progress-bar', AbProgressBar);
