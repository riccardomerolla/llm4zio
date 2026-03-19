import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

// ---------------------------------------------------------------------------
// ab-status
// Health / service status indicator dot + label.
//
// Props:
//   status  {String}  — 'healthy' | 'degraded' | 'down' | 'unknown'
//   label   {String}  — optional override label; defaults to capitalised status
// ---------------------------------------------------------------------------

const STATUS_CONFIG = {
  healthy:  { dot: 'bg-green-400',  ring: 'ring-green-400/20',  text: 'text-green-400',  default: 'Healthy'  },
  degraded: { dot: 'bg-yellow-400', ring: 'ring-yellow-400/20', text: 'text-yellow-400', default: 'Degraded' },
  down:     { dot: 'bg-red-400',    ring: 'ring-red-400/20',    text: 'text-red-400',    default: 'Down'     },
  unknown:  { dot: 'bg-gray-400',   ring: 'ring-gray-400/20',   text: 'text-gray-400',   default: 'Unknown'  },
};

class AbStatus extends LitElement {
  static properties = {
    status: { type: String },
    label:  { type: String },
  };

  constructor() {
    super();
    this.status = 'unknown';
    this.label  = '';
  }

  createRenderRoot() { return this; }

  render() {
    const cfg   = STATUS_CONFIG[this.status] ?? STATUS_CONFIG.unknown;
    const displayLabel = this.label || cfg.default;
    return html`
      <span class="inline-flex items-center gap-1.5">
        <span
          class="inline-block h-2 w-2 rounded-full ${cfg.dot} ring-1 ${cfg.ring}"
          aria-hidden="true"
        ></span>
        <span class="text-xs font-medium ${cfg.text}">${displayLabel}</span>
      </span>
    `;
  }
}

if (!customElements.get('ab-status')) customElements.define('ab-status', AbStatus);
