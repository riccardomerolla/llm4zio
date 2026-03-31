/**
 * ab-board-layout — LitElement orchestrator for Fizzy-style board columns.
 *
 * Manages up to 2 expanded columns on desktop (1 on mobile <768px), using CSS Grid
 * to size expanded columns at 1fr and compact columns at 40px.
 *
 * Properties / Attributes:
 *   default-expanded  (String) — comma-separated status tokens expanded by default
 *                                e.g. "todo,in_progress"
 *
 * Listens for:
 *   ab-column-toggle  — bubbles from child <ab-board-column> elements; detail: { status }
 *
 * localStorage key: board-expanded-columns:v2  — JSON array of up to 2 status strings
 */
import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

class AbBoardLayout extends LitElement {
  static _STORAGE_KEY = 'board-expanded-columns:v2';
  static _MOBILE_BREAKPOINT = 768;

  static properties = {
    defaultExpanded:   { type: String, attribute: 'default-expanded' },
    _expandedStatuses: { state: true },
  };

  constructor() {
    super();
    this.defaultExpanded    = '';
    this._expandedStatuses  = [];
    this._boundOnColumnToggle = this._onColumnToggle.bind(this);
  }

  createRenderRoot() { return this; }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  connectedCallback() {
    super.connectedCallback();
    // Apply grid container styles
    this.style.display = 'grid';
    this.style.gap     = '4px';
    this.style.height  = '100%';  // fill the flex-1 board root container

    // Attach listener immediately so no toggle events are missed
    this.addEventListener('ab-column-toggle', this._boundOnColumnToggle);

    // Defer state application to the next microtask. When module scripts run
    // (always deferred), the SSR DOM tree is already fully parsed so children
    // are present. The Promise.resolve() guard handles the edge case where an
    // HTMX fragment inserts this element and children are upgraded asynchronously.
    Promise.resolve().then(() => {
      this._expandedStatuses = this._load();
    });
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this.removeEventListener('ab-column-toggle', this._boundOnColumnToggle);
  }

  updated(changedProperties) {
    if (changedProperties.has('_expandedStatuses')) {
      this._applyState();
    }
  }

  render() {
    return html``;
  }

  // ---------------------------------------------------------------------------
  // Event handler
  // ---------------------------------------------------------------------------

  /**
   * Handle ab-column-toggle events from child columns.
   * @param {CustomEvent} e - detail: { status: string }
   */
  _onColumnToggle(e) {
    const { status } = e.detail || {};
    if (!status) return;

    const alreadyExpanded = this._expandedStatuses.includes(status);
    const max = this._maxExpanded();

    let newStatuses;
    if (alreadyExpanded) {
      // Collapse: remove from the list
      newStatuses = this._expandedStatuses.filter(s => s !== status);
    } else {
      // Expand: push to end (most recently interacted), then clamp to max
      newStatuses = [...this._expandedStatuses, status];
      // while (not if) defensively handles persisted state arriving with excess
      // entries; in normal flow only one shift is ever needed per toggle.
      while (newStatuses.length > max) {
        newStatuses.shift();
      }
    }

    this._expandedStatuses = newStatuses;
    this._save();
  }

  // ---------------------------------------------------------------------------
  // State management
  // ---------------------------------------------------------------------------

  /**
   * Apply _expandedStatuses to all child ab-board-column elements and update grid.
   */
  _applyState() {
    const columns = this.querySelectorAll('ab-board-column');
    columns.forEach(col => {
      const status = col.getAttribute('status');
      if (this._expandedStatuses.includes(status)) {
        col.setAttribute('expanded', '');
      } else {
        col.removeAttribute('expanded');
      }
    });
    this._updateGrid();
  }

  /**
   * Set grid-template-columns inline style based on current expansion state.
   * Expanded columns → 1fr, compact columns → 40px.
   */
  _updateGrid() {
    const columns = this.querySelectorAll('ab-board-column');
    if (columns.length === 0) return;

    const tracks = Array.from(columns).map(col => {
      const status = col.getAttribute('status');
      return this._expandedStatuses.includes(status) ? '1fr' : '40px';
    });

    this.style.gridTemplateColumns = tracks.join(' ');
  }

  // ---------------------------------------------------------------------------
  // Persistence
  // ---------------------------------------------------------------------------

  /**
   * Persist _expandedStatuses to localStorage.
   */
  _save() {
    try {
      localStorage.setItem(
        AbBoardLayout._STORAGE_KEY,
        JSON.stringify(this._expandedStatuses),
      );
    } catch (_) {
      // localStorage may be unavailable (private browsing, quota exceeded, etc.)
    }
  }

  /**
   * Read expansion state from localStorage.
   * Falls back to default-expanded attribute, then empty array.
   * @returns {string[]} array of status tokens (max length = _maxExpanded())
   */
  _load() {
    const max = this._maxExpanded();

    // 1. Try localStorage
    try {
      const raw = localStorage.getItem(AbBoardLayout._STORAGE_KEY);
      if (raw !== null) {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed)) {
          // Clamp to current max in case we switched from desktop to mobile
          return parsed.filter(s => typeof s === 'string').slice(0, max);
        }
      }
    } catch (_) {
      // Ignore parse errors
    }

    // 2. Fall back to default-expanded attribute
    const defaultAttr = this.defaultExpanded;
    if (defaultAttr) {
      return defaultAttr
        .split(',')
        .map(s => s.trim())
        .filter(Boolean)
        .slice(0, max);
    }

    // 3. Empty — all columns start compact
    return [];
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Return the maximum number of simultaneously expanded columns.
   * 1 on mobile (<768px), 2 on desktop.
   * @returns {number}
   * @remarks Viewport size is evaluated at call time. No continuous resize
   * listener is installed — the max is not adjusted on live window resize.
   */
  _maxExpanded() {
    return window.innerWidth < AbBoardLayout._MOBILE_BREAKPOINT ? 1 : 2;
  }
}

if (!customElements.get('ab-board-layout')) {
  customElements.define('ab-board-layout', AbBoardLayout);
}
