import { LitElement, html } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

// ---------------------------------------------------------------------------
// ab-data-table
// Sortable data table.
//
// Props:
//   headers  {Array<String>}               — column header labels
//   rows     {Array<Array<String>>}         — 2-D array of cell values
//
// Set via JS:
//   document.querySelector('ab-data-table').headers = ['ID', 'Name', 'Status'];
//   document.querySelector('ab-data-table').rows    = [['1', 'Foo', 'Active']];
//
// Events:
//   ab-sort  — { column: number, direction: 'asc' | 'desc' }
// ---------------------------------------------------------------------------

class AbDataTable extends LitElement {
  static properties = {
    headers:       { type: Array },
    rows:          { type: Array },
    _sortCol:      { type: Number, state: true },
    _sortDir:      { type: String, state: true },
  };

  constructor() {
    super();
    this.headers  = [];
    this.rows     = [];
    this._sortCol = -1;
    this._sortDir = 'asc';
  }

  createRenderRoot() { return this; }

  _toggleSort(colIdx) {
    if (this._sortCol === colIdx) {
      this._sortDir = this._sortDir === 'asc' ? 'desc' : 'asc';
    } else {
      this._sortCol = colIdx;
      this._sortDir = 'asc';
    }
    this.dispatchEvent(new CustomEvent('ab-sort', {
      detail: { column: this._sortCol, direction: this._sortDir },
      bubbles: true,
      composed: true,
    }));
  }

  _sortedRows() {
    if (this._sortCol < 0) return this.rows;
    return [...this.rows].sort((a, b) => {
      const av = a[this._sortCol] ?? '';
      const bv = b[this._sortCol] ?? '';
      const cmp = av.localeCompare(bv, undefined, { numeric: true, sensitivity: 'base' });
      return this._sortDir === 'asc' ? cmp : -cmp;
    });
  }

  _sortIcon(colIdx) {
    if (this._sortCol !== colIdx) {
      return html`<svg class="inline h-3 w-3 ml-1 text-gray-600" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true"><path d="M5 8l3-5 3 5H5zm0 0l3 5 3-5H5z"/></svg>`;
    }
    return this._sortDir === 'asc'
      ? html`<svg class="inline h-3 w-3 ml-1 text-indigo-400" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true"><path d="M5 10l3-6 3 6H5z"/></svg>`
      : html`<svg class="inline h-3 w-3 ml-1 text-indigo-400" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true"><path d="M11 6L8 12 5 6h6z"/></svg>`;
  }

  render() {
    const sorted = this._sortedRows();
    return html`
      <div class="overflow-x-auto">
        <table class="min-w-full divide-y divide-white/10">
          <thead class="bg-white/5">
            <tr>
              ${this.headers.map((h, i) => html`
                <th
                  class="py-3 px-4 text-left text-xs font-semibold uppercase tracking-wide text-gray-400 cursor-pointer select-none hover:text-white"
                  @click="${() => this._toggleSort(i)}"
                  scope="col"
                >
                  ${h}${this._sortIcon(i)}
                </th>
              `)}
            </tr>
          </thead>
          <tbody class="divide-y divide-white/5">
            ${sorted.length === 0
              ? html`<tr><td colspan="${this.headers.length}" class="py-6 text-center text-xs text-gray-500">No data</td></tr>`
              : sorted.map(row => html`
                  <tr class="hover:bg-white/5">
                    ${row.map(cell => html`<td class="whitespace-nowrap px-4 py-3 text-sm text-gray-300">${cell}</td>`)}
                  </tr>
                `)
            }
          </tbody>
        </table>
      </div>
    `;
  }
}

if (!customElements.get('ab-data-table')) customElements.define('ab-data-table', AbDataTable);
