class MessageComposer {
  constructor(root) {
    this.root = root;
    this.form = root.closest('form');
    this.input = root.querySelector('textarea[name="content"]');
    this.writePane = root.querySelector('[data-role="write-pane"]');
    this.previewPane = root.querySelector('[data-role="preview-pane"]');
    this.mentionsEl = root.querySelector('[data-role="mentions"]');

    this.agentsEndpoint = root.dataset.agentsEndpoint || '/api/agents';
    this.mode = 'write';
    this.agents = [];
    this.filteredAgents = [];
    this.mentionState = null;
    this.selectedMentionIndex = 0;

    if (!this.form || !this.input) return;

    this.bind();
    this.loadAgents();
  }

  bind() {
    // Wire ab-icon-button elements (they emit native click events)
    const slashCommandBtn = this.root.querySelector('[data-role="slash-command"]');
    const insertCodeBtn = this.root.querySelector('[data-role="insert-code"]');
    const mentionTrigger = this.root.querySelector('[data-role="mention-trigger"]');
    const modeToggle = this.root.querySelector('[data-role="mode-toggle"]');

    slashCommandBtn?.addEventListener('click', () => this._triggerSlash());
    insertCodeBtn?.addEventListener('click', () => this.insertCodeBlock());
    mentionTrigger?.addEventListener('click', () => this._triggerMention());
    modeToggle?.addEventListener('click', () => this.togglePreview());

    // Send button visual state
    this.sendBtn = this.form.querySelector('button[type="submit"]');
    this._updateSendState();

    this.input.addEventListener('input', () => {
      this.updatePreview();
      this.refreshMentions();
      this._autoGrow();
      this._updateSendState();
    });

    this.input.addEventListener('keydown', (event) => this.handleKeyDown(event));

    this.form.addEventListener('submit', () => {
      this.notifyStreamPending();
    });

    this.form.addEventListener('htmx:afterRequest', () => {
      this.setMode('write');
      this.hideMentions();
      this.input.focus();
      this.updatePreview();
      this.notifyStreamPending();
      this._autoGrow();
    });

    document.addEventListener('click', (event) => {
      if (!this.root.contains(event.target)) this.hideMentions();
    });

    this.updatePreview();
    this._autoGrow();
  }

  _autoGrow() {
    if (!this.input) return;
    this.input.style.height = 'auto';
    this.input.style.height = Math.min(this.input.scrollHeight, 192) + 'px'; // max ~12 lines
  }

  _triggerMention() {
    if (!this.input) return;
    const current = this.input.value;
    const caret = this.input.selectionStart ?? current.length;
    const before = current.slice(0, caret);
    const after = current.slice(caret);
    const needsSpace = before.length > 0 && !before.endsWith(' ');
    const insert = (needsSpace ? ' ' : '') + '@';
    this.input.value = before + insert + after;
    const newCaret = caret + insert.length;
    this.input.setSelectionRange(newCaret, newCaret);
    this.input.focus();
    this.refreshMentions();
  }

  _triggerSlash() {
    if (!this.input) return;
    const current = this.input.value;
    const caret = this.input.selectionStart ?? current.length;
    const before = current.slice(0, caret);
    const after = current.slice(caret);
    const needsSpace = before.length > 0 && !before.endsWith(' ') && !before.endsWith('\n');
    const insert = (needsSpace ? ' ' : '') + '/';
    this.input.value = before + insert + after;
    const newCaret = caret + insert.length;
    this.input.setSelectionRange(newCaret, newCaret);
    this.input.focus();
    this._updateSendState();
  }

  _updateSendState() {
    if (!this.sendBtn) return;
    const hasContent = this.input?.value?.trim().length > 0;
    this.sendBtn.classList.toggle('opacity-40', !hasContent);
  }

  notifyStreamPending() {
    const conversationId = this.root.dataset.conversationId;
    if (!conversationId) return;
    const stream = document.getElementById(`messages-${conversationId}`);
    if (stream && typeof stream.markPending === 'function') {
      stream.markPending();
    }
  }

  async loadAgents() {
    try {
      const response = await fetch(this.agentsEndpoint, { headers: { Accept: 'application/json' } });
      if (!response.ok) return;
      const payload = await response.json();
      this.agents = Array.isArray(payload)
        ? payload
            .map((item) => {
              if (typeof item === 'string') return { name: item, displayName: item };
              if (!item || !item.name) return null;
              return { name: String(item.name), displayName: String(item.displayName || item.name) };
            })
            .filter((item) => item !== null)
        : [];
    } catch (_error) {
      this.agents = [];
    }
  }

  handleKeyDown(event) {
    const isMac = navigator.platform.toUpperCase().includes('MAC');
    const ctrlOrCmd = isMac ? event.metaKey : event.ctrlKey;

    if (ctrlOrCmd && event.key === 'Enter') {
      event.preventDefault();
      this.form.requestSubmit();
      return;
    }

    if (ctrlOrCmd && !event.shiftKey && (event.key === 'P' || event.key === 'p')) {
      event.preventDefault();
      this.togglePreview();
      return;
    }

    if (ctrlOrCmd && (event.key === 'K' || event.key === 'k')) {
      event.preventDefault();
      this.insertCodeBlock();
      return;
    }

    if (this.mentionState && !this.mentionsEl.classList.contains('hidden')) {
      if (event.key === 'ArrowDown') {
        event.preventDefault();
        this.selectedMentionIndex = (this.selectedMentionIndex + 1) % this.filteredAgents.length;
        this.renderMentions();
        return;
      }

      if (event.key === 'ArrowUp') {
        event.preventDefault();
        this.selectedMentionIndex =
          (this.selectedMentionIndex - 1 + this.filteredAgents.length) % this.filteredAgents.length;
        this.renderMentions();
        return;
      }

      if (event.key === 'Enter' || event.key === 'Tab') {
        event.preventDefault();
        this.selectMention(this.filteredAgents[this.selectedMentionIndex]);
        return;
      }

      if (event.key === 'Escape') {
        event.preventDefault();
        this.hideMentions();
      }
    }
  }

  togglePreview() {
    this.setMode(this.mode === 'write' ? 'preview' : 'write');
  }

  setMode(nextMode) {
    this.mode = nextMode;
    const previewOn = nextMode === 'preview';

    this.writePane?.classList.toggle('hidden', previewOn);
    this.previewPane?.classList.toggle('hidden', !previewOn);

    this.updatePreview();
    if (!previewOn) this.input.focus();
  }

  updatePreview() {
    if (!this.previewPane) return;

    const content = this.input.value || '';
    if (!content.trim()) {
      this.previewPane.innerHTML = '<p class="text-gray-400">Nothing to preview yet.</p>';
      return;
    }

    let html = '';
    if (window.marked && typeof window.marked.parse === 'function') {
      if (window.hljs && typeof window.hljs.highlight === 'function') {
        window.marked.setOptions({
          highlight(code, language) {
            const selected = language && window.hljs.getLanguage(language) ? language : 'plaintext';
            return window.hljs.highlight(code, { language: selected }).value;
          },
        });
      }
      html = window.marked.parse(content);
    } else {
      html = this.escape(content).replace(/\n/g, '<br>');
    }

    this.previewPane.innerHTML = html;
  }

  refreshMentions() {
    const caret = this.input.selectionStart ?? this.input.value.length;
    const textBeforeCaret = this.input.value.slice(0, caret);
    const match = textBeforeCaret.match(/(?:^|\s)@([A-Za-z0-9_-]*)$/);

    if (!match) {
      this.hideMentions();
      return;
    }

    const query = match[1].toLowerCase();
    this.filteredAgents = this.agents.filter((agent) =>
      agent.name.toLowerCase().startsWith(query) || agent.displayName.toLowerCase().startsWith(query)
    );

    if (this.filteredAgents.length === 0) {
      this.hideMentions();
      return;
    }

    this.mentionState = {
      start: caret - match[1].length - 1,
      end: caret,
    };
    this.selectedMentionIndex = 0;
    this.renderMentions();
  }

  renderMentions() {
    if (!this.mentionsEl) return;

    this.mentionsEl.innerHTML = this.filteredAgents
      .map((agent, index) => {
        const active = index === this.selectedMentionIndex;
        const itemClass = active
          ? 'bg-indigo-500/30 text-white'
          : 'text-gray-200 hover:bg-white/10';
        return `<button type="button" data-mention-index="${index}" class="w-full text-left px-3 py-2 text-sm ${itemClass}">@${this.escape(
          agent.name
        )}<span class="ml-2 text-xs text-gray-400">${this.escape(agent.displayName)}</span></button>`;
      })
      .join('');

    this.mentionsEl.classList.remove('hidden');
    this.mentionsEl.querySelectorAll('[data-mention-index]').forEach((button) => {
      button.addEventListener('click', () => {
        const idx = Number(button.getAttribute('data-mention-index'));
        this.selectMention(this.filteredAgents[idx]);
      });
    });
  }

  hideMentions() {
    this.mentionState = null;
    this.filteredAgents = [];
    this.selectedMentionIndex = 0;
    this.mentionsEl?.classList.add('hidden');
    if (this.mentionsEl) this.mentionsEl.innerHTML = '';
  }

  selectMention(agent) {
    if (!agent || !this.mentionState) return;

    const mentionText = `@${agent.name}`;
    const current = this.input.value;
    const prefix = current.slice(0, this.mentionState.start);
    const suffix = current.slice(this.mentionState.end);
    const next = `${prefix}${mentionText} ${suffix}`;

    this.input.value = next;
    const nextCaret = prefix.length + mentionText.length + 1;
    this.input.setSelectionRange(nextCaret, nextCaret);
    this.hideMentions();
    this.updatePreview();
    this.input.focus();
  }

  insertCodeBlock() {
    const value = this.input.value;
    const start = this.input.selectionStart ?? value.length;
    const end = this.input.selectionEnd ?? value.length;
    const selected = value.slice(start, end);
    const language = this.detectLanguage(selected);
    const langTag = language === 'plain' ? '' : language;
    const snippetBody = selected || 'code';
    const block = `\n\`\`\`${langTag}\n${snippetBody}\n\`\`\`\n`;
    this.input.setRangeText(block, start, end, 'end');
    if (!selected) {
      const bodyStart = start + 5 + langTag.length;
      this.input.setSelectionRange(bodyStart, bodyStart + 4);
    }
    this.updatePreview();
    this.input.focus();
  }

  detectLanguage(selectedText) {
    const text = (selectedText || '').trim();
    if (!text) return 'plain';
    if (/^\s*\{[\s\S]*\}\s*$/.test(text) || /^\s*\[[\s\S]*\]\s*$/.test(text)) return 'json';
    if (/^\s*---/.test(text) || /:\s+[^\n]+/.test(text)) return 'yaml';
    if (/\b(def|import|from|print|self)\b/.test(text)) return 'python';
    if (/\b(val|def|given|case class|enum|trait|object)\b/.test(text)) return 'scala';
    if (/\b(echo|grep|awk|sed|curl|chmod)\b/.test(text)) return 'bash';
    return 'plain';
  }

  escape(text) {
    return String(text)
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  }
}

const composerRoot = document.getElementById('chat-composer');
if (composerRoot) {
  // highlight.js global used by marked highlighter callback
  import('https://cdn.jsdelivr.net/npm/highlight.js@11.11.1/+esm')
    .then((module) => {
      window.hljs = module.default;
      new MessageComposer(composerRoot);
    })
    .catch(() => {
      new MessageComposer(composerRoot);
    });
}
