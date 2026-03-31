/**
 * ab-board-column — LitElement custom element for Fizzy-style board columns.
 *
 * Properties / Attributes:
 *   status   (String)  — column status token e.g. "todo", "in_progress"
 *   label    (String)  — display name e.g. "In Progress"
 *   count    (String)  — number of cards e.g. "5"
 *   expanded (Boolean) — reflected; true = expanded, false = compact
 *   color    (String)  — Tailwind dot class e.g. "bg-amber-400"
 *
 * Events:
 *   ab-column-toggle — bubbles, composed; detail: { status }
 */
import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

class AbBoardColumn extends LitElement {
  static properties = {
    status:   { type: String },
    label:    { type: String },
    count:    { type: String },
    expanded: { type: Boolean, reflect: true },
    color:    { type: String },
  };

  // Progress bar sizing: 4 px per card, min 4 px, max 56 px (full at 14 cards)
  static _PROGRESS_PX_PER_CARD = 4;
  static _PROGRESS_MAX_PX      = 56;
  static _PROGRESS_MIN_PX      = 4;

  // Map Tailwind bg-* class names to hex colors for inline dot styling
  static _colorMap = {
    'bg-slate-400':   '#94a3b8',
    'bg-slate-500':   '#64748b',
    'bg-blue-400':    '#60a5fa',
    'bg-amber-400':   '#fbbf24',
    'bg-purple-400':  '#c084fc',
    'bg-orange-400':  '#fb923c',
    'bg-teal-400':    '#2dd4bf',
    'bg-green-400':   '#4ade80',
    'bg-emerald-400': '#34d399',  // Done
    'bg-gray-400':    '#9ca3af',
    'bg-red-400':     '#f87171',
    'bg-rose-500':    '#f43f5e',  // Canceled
  };

  constructor() {
    super();
    this.status   = '';
    this.label    = '';
    this.count    = '0';
    this.expanded = false;
    this.color    = '';
    this._boundOnClick = this._onClick.bind(this);
  }

  /**
   * Render Lit content into the compact title strip container so that
   * server-rendered column children (header, cards, quick-add) are untouched.
   * Must be called after _ensureCompactTitle() has inserted the container.
   */
  createRenderRoot() {
    return this._compactTitleEl ?? this;
  }

  connectedCallback() {
    // Create the compact title strip container BEFORE calling super.connectedCallback()
    // because super calls createRenderRoot() synchronously and needs the container to exist.
    this._ensureCompactTitle();

    super.connectedCallback();
    // Set data-drop-status so drag-and-drop code can find this column
    this.dataset.dropStatus = this.status || '';

    // Always fill the grid cell height (compact strip also uses height:100%)
    this.style.height   = '100%';
    this.style.overflow = 'hidden';  // contain flex children; cards area scrolls internally

    // Insert collapse button into the column header (if present)
    this._insertCollapseButton();

    // Apply initial visual state
    this._applyVisual();

    this.addEventListener('click', this._boundOnClick);
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this.removeEventListener('click', this._boundOnClick);
  }

  updated(changedProperties) {
    if (changedProperties.has('expanded')) {
      this._applyVisual();
    }
    // count / color / label changes are handled automatically by Lit re-rendering render()
  }

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /**
   * Update the displayed card count.
   * @param {number} n
   */
  updateCount(n) {
    this.count = String(n);
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  static _progressHeight(count) {
    const { _PROGRESS_PX_PER_CARD: step, _PROGRESS_MIN_PX: min, _PROGRESS_MAX_PX: max } = AbBoardColumn;
    return Math.max(min, Math.min(count * step, max));
  }

  get _compactTitleEl() {
    return this.querySelector('[data-column-compact-title]');
  }

  _ensureCompactTitle() {
    if (this._compactTitleEl) return;

    const strip = document.createElement('div');
    strip.setAttribute('data-column-compact-title', '');
    // display is managed exclusively by _applyVisual (either 'none' or 'flex')
    strip.style.cssText = [
      'flex-direction: column',
      'align-items: center',
      'justify-content: center',
      'gap: 8px',
      'padding: 12px 0',
      'width: 100%',
      'height: 100%',
      'min-height: 80px',
    ].join('; ');

    // Insert as first child so it appears above server-rendered content
    this.insertBefore(strip, this.firstChild);
  }

  _insertCollapseButton() {
    // Avoid double-inserting
    if (this.querySelector('[data-column-collapse-btn]')) return;

    const header = this.querySelector('[data-column-header]');
    if (!header) return;

    const btn = document.createElement('button');
    btn.type = 'button';
    btn.setAttribute('data-column-collapse-btn', '');
    btn.title = 'Collapse column';
    btn.style.cssText = [
      'padding: 2px 4px',
      'border-radius: 4px',
      'color: #6b7280',
      'font-size: 10px',
      'line-height: 1',
      'background: none',
      'border: none',
      'cursor: pointer',
    ].join('; ');
    btn.textContent = '\u2190'; // ←

    header.appendChild(btn);
  }

  _applyVisual() {
    const compactTitle = this._compactTitleEl;
    const cardsArea    = this.querySelector('[data-role="column-cards"]') ||
                         this.querySelector('[data-column-cards]');
    const quickAddForm = this.querySelector('[data-quick-add-form]');
    const collapseBtn  = this.querySelector('[data-column-collapse-btn]');

    if (this.expanded) {
      // Use flex-column so cards area can flex-1 and fill remaining column height
      this.style.display       = 'flex';
      this.style.flexDirection = 'column';
      this.style.removeProperty('cursor');
      if (compactTitle) compactTitle.style.display = 'none';
      // Header and quick-add must not shrink so they stay at the top
      const header = this.querySelector('[data-column-header]');
      if (header) header.style.flexShrink = '0';
      if (cardsArea) {
        cardsArea.style.removeProperty('display');
        cardsArea.style.flex      = '1';
        cardsArea.style.minHeight = '0';
        cardsArea.style.overflowY = 'auto';
      }
      // quick-add panel visibility managed by the board controller (classList); just clear override
      if (quickAddForm) {
        quickAddForm.style.removeProperty('display');
        quickAddForm.style.flexShrink = '0';
      }
      if (collapseBtn) collapseBtn.style.removeProperty('display');
    } else {
      this.style.removeProperty('display');
      this.style.removeProperty('flex-direction');
      this.style.cursor = 'pointer';
      if (compactTitle) compactTitle.style.display = 'flex'; // must be flex for column layout
      if (cardsArea) {
        cardsArea.style.display = 'none';
        cardsArea.style.removeProperty('flex');
        cardsArea.style.removeProperty('min-height');
        cardsArea.style.removeProperty('overflow-y');
      }
      if (quickAddForm) quickAddForm.style.display = 'none';
      if (collapseBtn)  collapseBtn.style.display = 'none';
    }
  }

  _onClick(e) {
    if (!this.expanded) {
      // Compact — click anywhere expands
      this._emitToggle();
      return;
    }
    // Expanded — only the collapse button triggers a toggle
    if (e.target.closest('[data-column-collapse-btn]')) {
      this._emitToggle();
    }
  }

  _emitToggle() {
    this.dispatchEvent(new CustomEvent('ab-column-toggle', {
      bubbles:  true,
      composed: true,
      detail:   { status: this.status },
    }));
  }

  render() {
    const dotColor  = AbBoardColumn._colorMap[this.color] || '#6b7280';
    const countVal  = parseInt(this.count || '0', 10);
    const barHeight = AbBoardColumn._progressHeight(countVal);

    return html`
      <span style="display:inline-block;width:6px;height:6px;border-radius:50%;background-color:${dotColor};flex-shrink:0;"></span>
      <span style="writing-mode:vertical-rl;transform:rotate(180deg);font-size:11px;font-weight:500;color:#94a3b8;white-space:nowrap;user-select:none;">${this.label}</span>
      <span style="font-size:11px;font-weight:600;color:#cbd5e1;">${countVal}</span>
      <div style="width:6px;height:${barHeight}px;border-radius:3px;background:linear-gradient(to top,${dotColor},transparent);flex-shrink:0;"></div>
    `;
  }
}

if (!customElements.get('ab-board-column')) {
  customElements.define('ab-board-column', AbBoardColumn);
}
