/**
 * ab-board-column — plain HTMLElement custom element for Fizzy-style board columns.
 *
 * Attributes:
 *   status   (String)  — column status token e.g. "todo", "in_progress"
 *   label    (String)  — display name e.g. "In Progress"
 *   count    (String)  — number of cards e.g. "5"
 *   expanded (Boolean) — reflected; presence = expanded, absence = compact
 *   color    (String)  — Tailwind dot class e.g. "bg-amber-400"
 *
 * Events:
 *   ab-column-toggle — bubbles, composed; detail: { status }
 */
class AbBoardColumn extends HTMLElement {
  static observedAttributes = ['expanded', 'count'];

  // Progress bar sizing: 4 px per card, min 4 px, max 56 px (full at 14 cards)
  static _PROGRESS_PX_PER_CARD = 4;
  static _PROGRESS_MAX_PX      = 56;
  static _PROGRESS_MIN_PX      = 4;

  // Map Tailwind bg-* class names to hex colors for inline dot styling
  static _colorMap = {
    'bg-slate-400':  '#94a3b8',
    'bg-blue-400':   '#60a5fa',
    'bg-amber-400':  '#fbbf24',
    'bg-purple-400': '#c084fc',
    'bg-orange-400': '#fb923c',
    'bg-teal-400':   '#2dd4bf',
    'bg-green-400':  '#4ade80',
    'bg-gray-400':   '#9ca3af',
    'bg-red-400':    '#f87171',
  };

  constructor() {
    super();
    // Bind click handler so we can remove the exact same reference later
    this._boundOnClick = this._onClick.bind(this);
  }

  connectedCallback() {
    // 1. Set data-drop-status so drag-and-drop code can find this column
    const status = this.getAttribute('status') || '';
    this.dataset.dropStatus = status;

    // 2. Insert compact title strip as first child
    this._insertCompactTitle();

    // 3. Insert collapse button into the column header (if present)
    this._insertCollapseButton();

    // 4. Apply initial visual state
    this._applyVisual();

    // 5. Attach click listener
    this.addEventListener('click', this._boundOnClick);
  }

  disconnectedCallback() {
    this.removeEventListener('click', this._boundOnClick);
  }

  attributeChangedCallback(name, _oldValue, newValue) {
    if (name === 'expanded') {
      this._applyVisual();
    } else if (name === 'count') {
      const n = parseInt(newValue || '0', 10);
      const countEl = this.querySelector('[data-compact-count]');
      if (countEl) countEl.textContent = String(n);
      const bar = this.querySelector('[data-compact-progress]');
      if (bar) bar.style.height = `${AbBoardColumn._progressHeight(n)}px`;
    }
  }

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /**
   * Update the displayed card count on both the attribute and the compact strip.
   * @param {number} n
   */
  updateCount(n) {
    // Setting the attribute triggers attributeChangedCallback which updates the DOM
    this.setAttribute('count', String(n));
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  static _progressHeight(count) {
    const { _PROGRESS_PX_PER_CARD: step, _PROGRESS_MIN_PX: min, _PROGRESS_MAX_PX: max } = AbBoardColumn;
    return Math.max(min, Math.min(count * step, max));
  }

  _insertCompactTitle() {
    // Avoid double-inserting if connectedCallback is called more than once
    if (this.querySelector('[data-column-compact-title]')) return;

    const label    = this.getAttribute('label') || '';
    const count    = this.getAttribute('count') || '0';
    const colorKey = this.getAttribute('color') || '';
    const dotColor = AbBoardColumn._colorMap[colorKey] || '#6b7280';

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

    // Colored dot
    const dot = document.createElement('span');
    dot.style.cssText = [
      'display: inline-block',
      'width: 6px',
      'height: 6px',
      'border-radius: 50%',
      `background-color: ${dotColor}`,
      'flex-shrink: 0',
    ].join('; ');

    // Vertical label text
    const labelEl = document.createElement('span');
    labelEl.style.cssText = [
      'writing-mode: vertical-rl',
      'transform: rotate(180deg)',
      'font-size: 11px',
      'font-weight: 500',
      'color: #94a3b8',   // slate-400 — readable on dark bg-slate-900
      'white-space: nowrap',
      'user-select: none',
    ].join('; ');
    labelEl.textContent = label;

    // Count number
    const countEl = document.createElement('span');
    countEl.setAttribute('data-compact-count', '');
    countEl.style.cssText = [
      'font-size: 11px',
      'font-weight: 600',
      'color: #cbd5e1',   // slate-200 — brighter than label for visual hierarchy
    ].join('; ');
    countEl.textContent = count;

    // Progress gradient bar — height proportional to card count
    const countVal  = parseInt(this.getAttribute('count') || '0', 10);
    const barHeight = AbBoardColumn._progressHeight(countVal);
    const progressBar = document.createElement('div');
    progressBar.setAttribute('data-compact-progress', '');
    progressBar.style.cssText = [
      `width: 6px`,
      `height: ${barHeight}px`,
      'border-radius: 3px',
      `background: linear-gradient(to top, ${dotColor}, transparent)`,
      'flex-shrink: 0',
    ].join('; ');

    strip.appendChild(dot);
    strip.appendChild(labelEl);
    strip.appendChild(countEl);
    strip.appendChild(progressBar);

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
    const expanded     = this.hasAttribute('expanded');
    const compactTitle = this.querySelector('[data-column-compact-title]');
    const cardsArea    = this.querySelector('[data-role="column-cards"]') ||
                         this.querySelector('[data-column-cards]');
    const quickAddForm = this.querySelector('[data-quick-add-form]');
    const collapseBtn  = this.querySelector('[data-column-collapse-btn]');

    if (expanded) {
      this.style.removeProperty('cursor');
      if (compactTitle) compactTitle.style.display = 'none';
      if (cardsArea)    cardsArea.style.removeProperty('display');
      // quick-add panel visibility managed by the board controller (classList); just clear override
      if (quickAddForm) quickAddForm.style.removeProperty('display');
      if (collapseBtn)  collapseBtn.style.removeProperty('display');
    } else {
      this.style.cursor = 'pointer';
      if (compactTitle) compactTitle.style.display = 'flex'; // must be flex for column layout
      if (cardsArea)    cardsArea.style.display = 'none';
      if (quickAddForm) quickAddForm.style.display = 'none';
      if (collapseBtn)  collapseBtn.style.display = 'none';
    }
  }

  _onClick(e) {
    if (!this.hasAttribute('expanded')) {
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
      detail:   { status: this.getAttribute('status') },
    }));
  }
}

if (!customElements.get('ab-board-column')) {
  customElements.define('ab-board-column', AbBoardColumn);
}
