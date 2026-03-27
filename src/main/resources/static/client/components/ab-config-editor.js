import { LitElement } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';
import hljs from 'https://cdn.jsdelivr.net/npm/highlight.js@11.11.1/+esm';

class ConfigEditor extends LitElement {
  static properties = {
    apiBase: { type: String, attribute: 'api-base' },
  };

  constructor() {
    super();
    this.apiBase = '/api/config';
    this._doc = null;
    this._history = [];
    this._validation = null;
    this._diff = null;
    this._dirty = false;
    this._format = 'Hocon';
  }

  createRenderRoot() { return this; }

  connectedCallback() {
    super.connectedCallback();
    this._renderShell();
    this._load();
  }

  async _load() {
    await Promise.all([this._loadCurrent(), this._loadHistory()]);
    this._renderData();
  }

  async _loadCurrent() {
    const res = await fetch(this.apiBase + '/current');
    if (!res.ok) throw new Error('Cannot load current configuration');
    this._doc = await res.json();
    this._format = this._doc.format || 'Hocon';
    const editor = this.querySelector('#cfg-editor');
    if (editor) editor.value = this._doc.content || '';
    this._dirty = false;
  }

  async _loadHistory() {
    const res = await fetch(this.apiBase + '/history');
    if (!res.ok) return;
    this._history = await res.json();
  }

  _renderShell() {
    this.innerHTML = `
      <div class="space-y-4">
        <div class="flex flex-wrap items-center gap-3">
          <select id="cfg-format" class="bg-white/10 border border-white/20 rounded-lg px-3 py-2 text-white text-sm">
            <option value="Hocon">HOCON</option>
            <option value="Json">JSON</option>
          </select>
          <button id="cfg-validate" class="px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg text-sm font-medium">Validate</button>
          <button id="cfg-diff" class="px-4 py-2 bg-slate-700 hover:bg-slate-600 text-white rounded-lg text-sm font-medium">Diff</button>
          <button id="cfg-save" class="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 text-white rounded-lg text-sm font-medium">Save + Hot Reload</button>
          <button id="cfg-reload" class="px-4 py-2 bg-amber-600 hover:bg-amber-700 text-white rounded-lg text-sm font-medium">Reload</button>
          <span id="cfg-status" class="text-xs rounded px-2 py-1 bg-white/10 text-gray-300 ring-1 ring-white/10">Ready</span>
        </div>

        <div class="grid grid-cols-1 xl:grid-cols-3 gap-4">
          <div class="xl:col-span-2 space-y-3">
            <textarea id="cfg-editor" class="w-full min-h-[30rem] bg-black/30 border border-white/10 rounded-lg p-4 text-sm leading-6 font-mono text-gray-100 focus:outline-none focus:ring-2 focus:ring-indigo-500"></textarea>
            <div class="rounded-lg bg-black/20 ring-1 ring-white/10 p-3">
              <h3 class="text-sm font-semibold text-white mb-2">Syntax Preview</h3>
              <pre class="text-xs overflow-auto"><code id="cfg-preview" class="language-json"></code></pre>
            </div>
          </div>
          <div class="space-y-3">
            <div class="rounded-lg bg-black/20 ring-1 ring-white/10 p-3">
              <h3 class="text-sm font-semibold text-white mb-2">Validation</h3>
              <div id="cfg-validation" class="text-xs text-gray-300"></div>
            </div>
            <div class="rounded-lg bg-black/20 ring-1 ring-white/10 p-3">
              <h3 class="text-sm font-semibold text-white mb-2">History</h3>
              <div id="cfg-history" class="space-y-2 max-h-[18rem] overflow-auto"></div>
            </div>
            <div class="rounded-lg bg-black/20 ring-1 ring-white/10 p-3">
              <h3 class="text-sm font-semibold text-white mb-2">Diff</h3>
              <div id="cfg-diff-view" class="space-y-1 max-h-[18rem] overflow-auto text-xs font-mono"></div>
            </div>
          </div>
        </div>
      </div>
    `;

    this.querySelector('#cfg-editor')?.addEventListener('input', () => {
      this._dirty = true;
      this._setStatus('Modified', 'bg-amber-500/10 text-amber-300 ring-amber-500/20');
      this._renderPreview();
    });

    this.querySelector('#cfg-format')?.addEventListener('change', (e) => {
      this._format = e.target.value;
      this._dirty = true;
      this._renderPreview();
    });

    this.querySelector('#cfg-validate')?.addEventListener('click', () => this._validate());
    this.querySelector('#cfg-diff')?.addEventListener('click', () => this._diffNow());
    this.querySelector('#cfg-save')?.addEventListener('click', () => this._save());
    this.querySelector('#cfg-reload')?.addEventListener('click', () => this._reload());
  }

  _payload() {
    const editor = this.querySelector('#cfg-editor');
    return {
      content: editor ? editor.value : '',
      format: this._format,
    };
  }

  async _validate() {
    const res = await fetch(this.apiBase + '/validate', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(this._payload()),
    });
    const body = await res.json();
    this._validation = body;
    this._renderValidation();
    this._setStatus(body.valid ? 'Valid' : 'Invalid', body.valid ? 'bg-emerald-500/10 text-emerald-300 ring-emerald-500/20' : 'bg-red-500/10 text-red-300 ring-red-500/20');
  }

  async _diffNow() {
    const original = this._doc?.content || '';
    const updated = this.querySelector('#cfg-editor')?.value || '';
    const res = await fetch(this.apiBase + '/diff', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ original, updated }),
    });
    if (!res.ok) return;
    this._diff = await res.json();
    this._renderDiff();
  }

  async _save() {
    const res = await fetch(this.apiBase + '/save', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(this._payload()),
    });
    const body = await res.json();
    this._validation = body.validation || null;
    this._renderValidation();
    if (!res.ok || !body.saved) {
      this._setStatus('Save failed', 'bg-red-500/10 text-red-300 ring-red-500/20');
      return;
    }
    this._doc = body.current;
    this._dirty = false;
    await this._loadHistory();
    this._renderHistory();
    this._setStatus('Saved + Reloaded', 'bg-emerald-500/10 text-emerald-300 ring-emerald-500/20');
  }

  async _reload() {
    const res = await fetch(this.apiBase + '/reload', { method: 'POST' });
    if (!res.ok) {
      this._setStatus('Reload failed', 'bg-red-500/10 text-red-300 ring-red-500/20');
      return;
    }
    this._doc = await res.json();
    this._format = this._doc.format || this._format;
    const editor = this.querySelector('#cfg-editor');
    if (editor) editor.value = this._doc.content || '';
    const select = this.querySelector('#cfg-format');
    if (select) select.value = this._format;
    this._dirty = false;
    this._renderPreview();
    this._setStatus('Reloaded', 'bg-blue-500/10 text-blue-300 ring-blue-500/20');
  }

  async _rollback(id) {
    const res = await fetch(this.apiBase + '/history/' + encodeURIComponent(id) + '/rollback', { method: 'POST' });
    if (!res.ok) {
      this._setStatus('Rollback failed', 'bg-red-500/10 text-red-300 ring-red-500/20');
      return;
    }
    this._doc = await res.json();
    this._format = this._doc.format || this._format;
    const editor = this.querySelector('#cfg-editor');
    if (editor) editor.value = this._doc.content || '';
    this._dirty = false;
    await this._loadHistory();
    this._renderData();
    this._setStatus('Rolled back', 'bg-blue-500/10 text-blue-300 ring-blue-500/20');
  }

  _renderData() {
    const editor = this.querySelector('#cfg-editor');
    if (editor && this._doc) editor.value = this._doc.content || '';
    const select = this.querySelector('#cfg-format');
    if (select) select.value = this._format;
    this._renderValidation();
    this._renderHistory();
    this._renderDiff();
    this._renderPreview();
  }

  _renderValidation() {
    const container = this.querySelector('#cfg-validation');
    if (!container) return;
    if (!this._validation) {
      container.innerHTML = '<div class="text-gray-400">Run validation to inspect issues.</div>';
      return;
    }
    if (this._validation.valid) {
      container.innerHTML = '<div class="text-emerald-300">Configuration is valid.</div>';
      return;
    }
    container.innerHTML = (this._validation.issues || []).map((issue) =>
      `<div class="rounded bg-red-500/10 ring-1 ring-red-500/20 px-2 py-1 text-red-200">${this._escape(issue.message || 'Validation error')}</div>`
    ).join('');
  }

  _renderHistory() {
    const container = this.querySelector('#cfg-history');
    if (!container) return;
    if (!this._history.length) {
      container.innerHTML = '<div class="text-gray-400">No saved revisions yet.</div>';
      return;
    }
    container.innerHTML = this._history.map((h) => {
      const ts = new Date(h.savedAt).toLocaleString();
      return `
        <div class="rounded bg-white/5 ring-1 ring-white/10 px-2 py-2">
          <div class="text-gray-300">${ts}</div>
          <div class="text-gray-500">${h.format} • ${h.sizeBytes} bytes</div>
          <button data-rollback="${this._escape(h.id)}" class="mt-1 px-2 py-1 text-xs rounded bg-blue-500/10 text-blue-300 ring-1 ring-blue-500/20 hover:bg-blue-500/20">Rollback</button>
        </div>
      `;
    }).join('');
    container.querySelectorAll('button[data-rollback]').forEach((btn) => {
      btn.addEventListener('click', () => this._rollback(btn.dataset.rollback));
    });
  }

  _renderDiff() {
    const container = this.querySelector('#cfg-diff-view');
    if (!container) return;
    if (!this._diff || !this._diff.lines) {
      container.innerHTML = '<div class="text-gray-400">No diff computed yet.</div>';
      return;
    }
    container.innerHTML = this._diff.lines.map((line) => {
      const kind = line.kind || 'unchanged';
      const cls = kind === 'added' ? 'text-emerald-300' : kind === 'removed' ? 'text-red-300' : 'text-gray-400';
      const prefix = kind === 'added' ? '+' : kind === 'removed' ? '-' : ' ';
      return `<div class="${cls}">${prefix} ${this._escape(line.text || '')}</div>`;
    }).join('');
  }

  _renderPreview() {
    const code = this.querySelector('#cfg-preview');
    const editor = this.querySelector('#cfg-editor');
    if (!code || !editor) return;
    code.textContent = editor.value;
    code.className = this._format === 'Json' ? 'language-json' : 'language-ini';
    hljs.highlightElement(code);
  }

  _setStatus(text, classNames) {
    const node = this.querySelector('#cfg-status');
    if (!node) return;
    node.textContent = text;
    node.className = 'text-xs rounded px-2 py-1 ring-1 ' + classNames;
  }

  _escape(value) {
    return String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
  }
}

if (!customElements.get('ab-config-editor')) {
  customElements.define('ab-config-editor', ConfigEditor);
}
