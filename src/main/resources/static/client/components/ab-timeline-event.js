import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

// SVG path constants for each event type
const ICONS = {
  git: {
    paths: ['M7.5 3.75a.75.75 0 0 1 .75.75v1.5a.75.75 0 0 1-1.5 0v-1.5a.75.75 0 0 1 .75-.75Zm4.5 0a.75.75 0 0 1 .75.75v1.5a.75.75 0 0 1-1.5 0v-1.5a.75.75 0 0 1 .75-.75ZM6 7.5a.75.75 0 0 1 .75-.75h10.5a.75.75 0 0 1 0 1.5H6.75A.75.75 0 0 1 6 7.5Zm3.75 8.25a.75.75 0 0 1 .75-.75h3a.75.75 0 0 1 0 1.5h-3a.75.75 0 0 1-.75-.75Zm-3 3a.75.75 0 0 1 .75-.75h9a.75.75 0 0 1 0 1.5h-9a.75.75 0 0 1-.75-.75Z'],
  },
  tool: {
    paths: [
      'M9.594 3.94c.09-.542.56-.94 1.11-.94h2.593c.55 0 1.02.398 1.11.94l.213 1.281c.063.374.313.686.645.87.074.04.147.083.22.127.325.196.72.257 1.075.124l1.217-.456a1.125 1.125 0 0 1 1.37.49l1.296 2.247a1.125 1.125 0 0 1-.26 1.431l-1.003.827c-.293.241-.438.613-.43.992a7.723 7.723 0 0 1 0 .255c-.008.378.137.75.43.991l1.004.827c.424.35.534.955.26 1.43l-1.298 2.247a1.125 1.125 0 0 1-1.369.491l-1.217-.456c-.355-.133-.75-.072-1.076.124a6.47 6.47 0 0 1-.22.128c-.331.183-.581.495-.644.869l-.213 1.281c-.09.543-.56.94-1.11.94h-2.594c-.55 0-1.019-.398-1.11-.94l-.213-1.281c-.062-.374-.312-.686-.644-.87a6.52 6.52 0 0 1-.22-.127c-.325-.196-.72-.257-1.076-.124l-1.217.456a1.125 1.125 0 0 1-1.369-.49l-1.297-2.247a1.125 1.125 0 0 1 .26-1.431l1.004-.827c.292-.24.437-.613.43-.991a6.932 6.932 0 0 1 0-.255c.007-.38-.138-.751-.43-.992l-1.004-.827a1.125 1.125 0 0 1-.26-1.43l1.297-2.247a1.125 1.125 0 0 1 1.37-.491l1.216.456c.356.133.751.072 1.076-.124.072-.044.146-.086.22-.128.332-.183.582-.495.644-.869l.214-1.28Z',
      'M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z',
    ],
  },
  status: {
    paths: ['M11.25 11.25l.041-.02a.75.75 0 0 1 1.063.852l-.708 2.836a.75.75 0 0 0 1.063.853l.041-.021M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Zm-9-3.75h.008v.008H12V8.25Z'],
  },
  report: {
    paths: ['M19.5 14.25v-2.625a3.375 3.375 0 0 0-3.375-3.375h-1.5A1.125 1.125 0 0 1 13.5 7.125v-1.5a3.375 3.375 0 0 0-3.375-3.375H8.25m0 12.75h7.5m-7.5 3H12M10.5 2.25H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 0 0-9-9Z'],
  },
};

const CHEVRON_DOWN = 'M19.5 8.25l-7.5 7.5-7.5-7.5';

// Per-event-type styling config
const EVENT_CONFIG = {
  git: {
    label: 'Git',
    cardColor: 'border-emerald-400/40 bg-emerald-500/8',
    accentLine: 'bg-emerald-400',
    accentBorder: 'border-emerald-400',
    iconColor: 'text-emerald-400',
  },
  tool: {
    label: 'Tool',
    cardColor: 'border-indigo-400/30 bg-indigo-500/8',
    accentLine: 'bg-indigo-400',
    accentBorder: 'border-indigo-400',
    iconColor: 'text-indigo-400',
  },
  status: {
    label: 'Status',
    cardColor: 'border-amber-400/30 bg-amber-500/8',
    accentLine: 'bg-amber-400',
    accentBorder: 'border-amber-400',
    iconColor: 'text-amber-400',
  },
  report: {
    label: 'Report',
    cardColor: 'border-cyan-400/30 bg-cyan-500/8',
    accentLine: 'bg-cyan-400',
    accentBorder: 'border-cyan-400',
    iconColor: 'text-cyan-400',
  },
};

const DEFAULT_CONFIG = {
  label: 'Event',
  cardColor: 'border-white/10 bg-white/5',
  accentLine: 'bg-gray-400',
  accentBorder: 'border-gray-400',
  iconColor: 'text-gray-400',
};

class AbTimelineEvent extends LitElement {
  static properties = {
    'event-type': { type: String },
    title:        { type: String },
    subtitle:     { type: String },
    timestamp:    { type: String },
    expandable:   { type: Boolean },
    'panel-target': { type: String },
    'event-data': { type: String },
    _expanded:    { type: Boolean, state: true },
  };

  constructor() {
    super();
    this['event-type']  = 'status';
    this.title          = '';
    this.subtitle       = '';
    this.timestamp      = '';
    this.expandable     = false;
    this['panel-target'] = '';
    this['event-data']  = '';
    this._expanded      = false;
  }

  createRenderRoot() { return this; }

  // Convenience getters to avoid bracket notation throughout the template
  get eventType() { return this['event-type'] || 'status'; }
  get panelTarget() { return this['panel-target'] || ''; }
  get eventData() { return this['event-data'] || ''; }

  _config() {
    return EVENT_CONFIG[this.eventType] || DEFAULT_CONFIG;
  }

  _getEventLabel() {
    return this._config().label;
  }

  _handleClick() {
    if (this.expandable) {
      this._expanded = !this._expanded;
    }

    if (this.panelTarget) {
      let parsedData = null;
      if (this.eventData) {
        try {
          parsedData = JSON.parse(this.eventData);
        } catch {
          parsedData = null;
        }
      }
      window.dispatchEvent(new CustomEvent('ab-panel-open', {
        detail: {
          panelId: this.panelTarget,
          title: this._getEventLabel(),
          eventType: this.eventType,
          data: parsedData,
        },
      }));
    }
  }

  _renderIcon(config) {
    const iconDef = ICONS[this.eventType] || ICONS['status'];
    const paths = iconDef.paths;
    return html`
      <svg
        xmlns="http://www.w3.org/2000/svg"
        class="w-3.5 h-3.5 flex-shrink-0 ${config.iconColor}"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        ${paths.map((d) => html`<path d="${d}" />`)}
      </svg>
    `;
  }

  _renderChevron() {
    if (!this.expandable) return html``;
    const rotation = this._expanded ? 'rotate-180' : '';
    return html`
      <svg
        xmlns="http://www.w3.org/2000/svg"
        class="w-3 h-3 text-gray-500 flex-shrink-0 transition-transform ${rotation}"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="${CHEVRON_DOWN}" />
      </svg>
    `;
  }

  _renderSubtitle(config) {
    if (!this._expanded || !this.subtitle) return html``;
    return html`
      <div class="mt-1 ml-6 pl-2 border-l-2 ${config.accentBorder} text-[11px] text-gray-400 font-mono whitespace-pre-wrap">
        ${this.subtitle}
      </div>
    `;
  }

  render() {
    const config = this._config();
    return html`
      <div>
        <div
          class="relative flex items-center gap-2 rounded-md border ${config.cardColor} px-3 py-1.5 my-1 text-[11px] cursor-pointer hover:bg-white/5 transition-colors group"
          @click="${this._handleClick}"
          role="button"
          tabindex="0"
          @keydown="${(e) => (e.key === 'Enter' || e.key === ' ') && this._handleClick()}"
        >
          <!-- left accent line -->
          <div class="absolute left-0 top-0 bottom-0 w-0.5 rounded-l-md ${config.accentLine}"></div>

          <!-- icon -->
          ${this._renderIcon(config)}

          <!-- title -->
          <span class="flex-1 text-gray-300 truncate font-mono">${this.title}</span>

          <!-- timestamp -->
          ${this.timestamp ? html`<span class="text-gray-500 flex-shrink-0">${this.timestamp}</span>` : html``}

          <!-- expand chevron -->
          ${this._renderChevron()}
        </div>

        <!-- expanded subtitle block -->
        ${this._renderSubtitle(config)}
      </div>
    `;
  }
}

customElements.define('ab-timeline-event', AbTimelineEvent);
