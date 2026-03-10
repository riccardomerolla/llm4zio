import { LitElement } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

class ChatMessageStream extends LitElement {
  static properties = {
    conversationId: { type: String, attribute: 'conversation-id' },
    wsUrl: { type: String, attribute: 'ws-url' },
  };

  constructor() {
    super();
    this._streaming = false;
    this._streamBuffer = '';
    this._assistantCountAtPending = 0;
    this._ws = null;
    this._wsEnabled = true;
    this._wsEverOpened = false;
    this._reconnectTimer = null;
    this._pendingPoll = null;
    this._backgroundPoll = null;
    this._thinkingTicker = null;
    this._thinkingIndex = 0;
    this._thinkingPhrases = [
      'Could be worse. Could be raining.',
      'Walk this way.',
      "It's alive!",
      'What knockers!',
      'Put... the candle... back!'
    ];
    this._lastSnapshot = '';
    this._shouldAutoScroll = true;
  }

  createRenderRoot() {
    return this;
  }

  connectedCallback() {
    super.connectedCallback();
    this._lastSnapshot = this._normalizedSnapshot(this.innerHTML);
    this._assistantCountAtPending = this._countAssistantMessages();
    this.addEventListener('scroll', () => {
      this._shouldAutoScroll = this._isNearBottom();
      this._updateScrollButton();
    });
    this._scrollToBottom(true);
    this._backgroundPoll = setInterval(() => {
      if (!this._streaming) this._refreshMessages();
    }, 2000);
    this._connect();
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    if (this._reconnectTimer) clearTimeout(this._reconnectTimer);
    if (this._pendingPoll) clearInterval(this._pendingPoll);
    if (this._backgroundPoll) clearInterval(this._backgroundPoll);
    if (this._thinkingTicker) clearInterval(this._thinkingTicker);
    if (this._ws) this._ws.close();
  }

  _connect() {
    if (!this._wsEnabled || !this.wsUrl || !this.conversationId) return;
    try {
      const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
      this._ws = new WebSocket(protocol + '//' + location.host + this.wsUrl);
      this._ws.onopen = () => {
        this._wsEverOpened = true;
        this._ws.send(JSON.stringify({ Subscribe: { topic: 'chat:' + this.conversationId + ':stream', params: {} } }));
      };
      this._ws.onmessage = (e) => {
        try {
          this._handleMessage(JSON.parse(e.data));
        } catch (_ignored) {}
      };
      this._ws.onclose = () => {
        if (this._wsEnabled) {
          this._reconnectTimer = setTimeout(() => this._connect(), 3000);
        }
      };
      this._ws.onerror = () => {
        if (!this._wsEverOpened) {
          this._wsEnabled = false;
          if (this._reconnectTimer) clearTimeout(this._reconnectTimer);
        }
      };
    } catch (_ignored) {}
  }

  _handleMessage(msg) {
    if (!msg.Event) return;
    const { eventType, payload } = msg.Event;
    let parsed = {};
    try {
      parsed = JSON.parse(payload);
    } catch (_ignored) {
      parsed = { type: eventType, delta: payload };
    }
    const evtType = parsed.type || eventType;

    switch (evtType) {
      case 'chat-stream-start':
        this._streaming = true;
        this._streamBuffer = '';
        this._appendStreamBubble();
        this._toggleAbortButton(true);
        window.updateActivityIndicator(this.conversationId, 'Thinking', '');
        break;
      case 'chat-chunk': {
        const delta = parsed.delta || '';
        this._streamBuffer += delta;
        this._updateStreamBubble();
        // Detect tool name in streaming JSON chunks (look for "name": "<toolName>" pattern)
        const toolMatch = delta.match(/"name"\s*:\s*"([^"]+)"/);
        if (toolMatch) {
          const toolName = toolMatch[1];
          // Also try to extract a primary argument from common fields
          const argMatch = delta.match(/"(?:file_path|path|command|pattern|query|url)"\s*:\s*"([^"]+)"/);
          const toolArg = argMatch ? argMatch[1] : '';
          window.updateActivityIndicator(this.conversationId, toolName, toolArg);
        }
        break;
      }
      case 'chat-stream-end':
      case 'chat-aborted':
        this._streaming = false;
        this._removeStreamBubble();
        this._toggleAbortButton(false);
        window.hideActivityIndicator(this.conversationId);
        this._refreshMessages();
        break;
      case 'chat-message':
        if (!this._streaming) this._refreshMessages();
        break;
    }
    // Update token gauge if payload carries usage data.
    // Supported field names: tokenCount, totalTokens, contextUsage, inputTokens, usedTokens.
    const used =
      parsed.tokenCount ??
      parsed.totalTokens ??
      parsed.contextUsage ??
      parsed.inputTokens ??
      parsed.usedTokens ??
      null;
    const max = parsed.maxTokens ?? parsed.contextMax ?? null;
    if (used !== null && this.conversationId) {
      window.updateTokenGauge(this.conversationId, used, max ?? 200000);
    }
  }

  _appendStreamBubble() {
    const existing = this.querySelector('#stream-bubble');
    if (existing) existing.remove();
    const bubble = document.createElement('div');
    bubble.id = 'stream-bubble';
    bubble.className = 'flex justify-start';
    const inner = document.createElement('div');
    inner.className =
      'max-w-[85%] lg:max-w-[72%] rounded-2xl rounded-bl-md border border-indigo-300/25 bg-slate-800/60 px-4 py-3 shadow-lg shadow-black/20';
    inner.style.backdropFilter = 'blur(2px)';
    const senderEl = document.createElement('div');
    senderEl.className = 'text-xs font-semibold mb-2 text-slate-300';
    senderEl.textContent = 'assistant';
    const wrapper = document.createElement('div');
    wrapper.className = 'flex items-center gap-2';
    const dot = document.createElement('div');
    dot.className = 'w-2 h-2 rounded-full bg-indigo-400 animate-pulse';
    const contentEl = document.createElement('pre');
    contentEl.id = 'stream-content';
    contentEl.className = 'whitespace-pre-wrap break-words text-sm leading-6 text-gray-50 font-sans m-0';
    wrapper.appendChild(dot);
    wrapper.appendChild(contentEl);
    inner.appendChild(senderEl);
    inner.appendChild(wrapper);
    bubble.appendChild(inner);
    this.appendChild(bubble);
    this._startThinkingTicker();
    this._scrollToBottom();
  }

  _updateStreamBubble() {
    const el = this.querySelector('#stream-content');
    if (el) {
      el.textContent = this._streamBuffer;
      this._scrollToBottom();
    }
  }

  _removeStreamBubble() {
    const bubble = this.querySelector('#stream-bubble');
    if (bubble) bubble.remove();
    this._stopThinkingTicker();
  }

  _scrollButton() {
    return this.conversationId ? document.getElementById(`scroll-bottom-${this.conversationId}`) : null;
  }

  _isNearBottom() {
    const gap = this.scrollHeight - this.scrollTop - this.clientHeight;
    return gap < 80;
  }

  _updateScrollButton() {
    const button = this._scrollButton();
    if (!button) return;
    button.classList.toggle('hidden', this._shouldAutoScroll || this._isNearBottom());
  }

  _scrollToBottom(force = false) {
    if (!force && !this._shouldAutoScroll) {
      this._updateScrollButton();
      return;
    }
    requestAnimationFrame(() => {
      this.scrollTop = this.scrollHeight;
      requestAnimationFrame(() => {
        this.scrollTop = this.scrollHeight;
        this._shouldAutoScroll = true;
        this._updateScrollButton();
      });
    });
  }

  scrollToLatest() {
    this._shouldAutoScroll = true;
    this._scrollToBottom(true);
  }

  _abortButtonId() {
    return this.conversationId ? `abort-btn-${this.conversationId}` : 'abort-btn';
  }

  _toggleAbortButton(show) {
    const btn = document.getElementById(this._abortButtonId());
    if (btn) btn.classList.toggle('hidden', !show);
  }

  markPending() {
    const existingBubble = this.querySelector('#stream-bubble');
    if (this._streaming && existingBubble) {
      this._toggleAbortButton(true);
      this._startPendingPoll();
      this._scrollToBottom();
      return;
    }
    this._lastSnapshot = this._normalizedSnapshot(this.innerHTML);
    this._assistantCountAtPending = this._countAssistantMessages();
    this._streaming = true;
    this._streamBuffer = this._thinkingPhrases[0];
    this._appendStreamBubble();
    this._updateStreamBubble();
    this._toggleAbortButton(true);
    this._startPendingPoll();
  }

  _startPendingPoll() {
    if (this._pendingPoll) clearInterval(this._pendingPoll);
    this._pendingPoll = setInterval(() => {
      this._refreshMessages();
    }, 1200);
  }

  _stopPendingPoll() {
    if (this._pendingPoll) {
      clearInterval(this._pendingPoll);
      this._pendingPoll = null;
    }
  }

  _startThinkingTicker() {
    this._stopThinkingTicker();
    this._thinkingIndex = 0;
    this._streamBuffer = this._thinkingPhrases[this._thinkingIndex] || 'Thinking...';
    this._updateStreamBubble();
    this._thinkingTicker = setInterval(() => {
      this._thinkingIndex = (this._thinkingIndex + 1) % this._thinkingPhrases.length;
      this._streamBuffer = this._thinkingPhrases[this._thinkingIndex];
      this._updateStreamBubble();
    }, 1200);
  }

  _stopThinkingTicker() {
    if (this._thinkingTicker) {
      clearInterval(this._thinkingTicker);
      this._thinkingTicker = null;
    }
  }

  _normalizedSnapshot(value) {
    return (value || '').replace(/\s+/g, ' ').trim();
  }

  _countAssistantMessages() {
    return this.querySelectorAll('[data-sender="assistant"]').length;
  }

  _refreshMessages() {
    if (!this.conversationId) return;
    fetch(`/chat/${this.conversationId}/messages`)
      .then((r) => r.text())
      .then((t) => {
        const next = this._normalizedSnapshot(t);
        const changed = next !== this._lastSnapshot;
        if (!changed) return;
        const streamBubble = this.querySelector('#stream-bubble');
        const wrapper = document.createElement('div');
        wrapper.className = 'space-y-4 text-gray-100';
        const tmpl = document.createElement('template');
        tmpl.innerHTML = t;
        wrapper.appendChild(tmpl.content);
        while (this.firstChild) this.removeChild(this.firstChild);
        this.appendChild(wrapper);
        if (streamBubble && this._streaming) this.appendChild(streamBubble);
        if (this._streaming && !this.querySelector('#stream-bubble')) {
          this._appendStreamBubble();
          this._updateStreamBubble();
        }
        this._lastSnapshot = next;
        if (this._streaming) {
          const assistantCountNow = this._countAssistantMessages();
          const receivedAssistantReply = assistantCountNow > this._assistantCountAtPending;
          if (receivedAssistantReply) {
            this._streaming = false;
            this._removeStreamBubble();
            this._toggleAbortButton(false);
            this._stopPendingPoll();
          } else {
            this._toggleAbortButton(true);
            this._startPendingPoll();
          }
        }
        this._scrollToBottom();
      });
  }

  abort() {
    if (this._wsEnabled && this._ws && this._ws.readyState === WebSocket.OPEN && this.conversationId) {
      this._ws.send(JSON.stringify({ AbortChat: { conversationId: Number(this.conversationId) } }));
    }
    this._streaming = false;
    this._removeStreamBubble();
    this._toggleAbortButton(false);
    this._stopPendingPoll();
    if (this.conversationId) {
      fetch(`/api/chat/${this.conversationId}/abort`, { method: 'POST' });
    }
  }
}

if (!customElements.get('chat-message-stream')) {
  customElements.define('chat-message-stream', ChatMessageStream);
}

/**
 * Update the token gauge bar for a given conversation.
 *
 * @param {string|number} conversationId - the conversation ID used in element IDs
 * @param {number} used  - tokens used so far
 * @param {number} max   - total context window size (default 200 000)
 *
 * Color thresholds:
 *   0-60%  → bg-emerald-500
 *   60-85% → bg-amber-400
 *   85-100% → bg-rose-500
 */
window.updateTokenGauge = function updateTokenGauge(conversationId, used, max = 200000) {
  const bar   = document.getElementById('token-gauge-bar-'   + conversationId);
  const label = document.getElementById('token-gauge-label-' + conversationId);
  if (!bar || !label) return;

  const safeUsed = Math.max(0, Number(used)  || 0);
  const safeMax  = Math.max(1, Number(max));
  const pct      = Math.min(100, (safeUsed / safeMax) * 100);

  let colorClass;
  if (pct < 60) {
    colorClass = 'bg-emerald-500';
  } else if (pct < 85) {
    colorClass = 'bg-amber-400';
  } else {
    colorClass = 'bg-rose-500';
  }

  if (bar) {
    bar.style.width = pct.toFixed(2) + '%';
    bar.className = bar.className
      .replace(/\bbg-emerald-500\b|\bbg-amber-400\b|\bbg-rose-500\b/g, '')
      .trim() + ' ' + colorClass;
  }

  const gauge = document.getElementById(`token-gauge-${conversationId}`);
  if (gauge) gauge.setAttribute('aria-valuenow', Math.round(pct).toString());

  if (label) {
    const usedFmt = safeUsed.toLocaleString();
    const maxFmt  = safeMax.toLocaleString();
    const pctFmt  = pct.toFixed(0);
    label.textContent = usedFmt + ' / ' + maxFmt + ' tokens (' + pctFmt + '%)';
  }
};

/**
 * Module-level timer map to track pending activity indicator timers.
 * Keyed by conversationId (for update timers) or conversationId + '-hide' (for hide timers).
 */
const _activityTimers = {};

/**
 * Map a tool name (or recognizable prefix) to a display emoji/symbol.
 */
function _activityIconForTool(toolName) {
  const n = (toolName || '').toLowerCase();
  if (/^read|^cat|^open/i.test(n))   return '\uD83D\uDCC4'; // 📄
  if (/^write|^creat/i.test(n))     return '\u270F\uFE0F'; // ✏️
  if (/^grep|^search|^find/i.test(n)) return '\uD83D\uDD0D'; // 🔍
  if (/^bash|^run|^exec/i.test(n))   return '\u26A1';       // ⚡
  if (/^glob|^list/i.test(n))       return '\uD83D\uDCC1'; // 📁
  return '\u2699';                                        // ⚙
}

/**
 * Show the activity indicator for the given conversation and display tool info.
 *
 * @param {string|number} conversationId
 * @param {string} toolName  - e.g. "Read", "Bash", "Thinking"
 * @param {string} toolArg   - primary argument (file path, command, …)
 */
window.updateActivityIndicator = function updateActivityIndicator(conversationId, toolName, toolArg) {
  const indicator = document.getElementById('activity-indicator-' + conversationId);
  const iconEl    = document.getElementById('activity-icon-' + conversationId);
  const textEl    = document.getElementById('activity-text-' + conversationId);
  if (!indicator) return;

  // Cancel any pending timer for this conversation
  if (_activityTimers[conversationId]) {
    clearTimeout(_activityTimers[conversationId]);
  }

  // Brief fade-out then fade-in to signal content change
  indicator.style.opacity = '0';
  _activityTimers[conversationId] = setTimeout(() => {
    if (iconEl) iconEl.textContent = _activityIconForTool(toolName);
    if (textEl) {
      const label = toolArg ? toolName + ' ' + toolArg + '...' : toolName + '...';
      textEl.textContent = label;
    }
    indicator.classList.remove('hidden');
    indicator.style.opacity = '1';
    delete _activityTimers[conversationId];
  }, 80);
};

/**
 * Hide the activity indicator for the given conversation.
 *
 * @param {string|number} conversationId
 */
window.hideActivityIndicator = function hideActivityIndicator(conversationId) {
  const indicator = document.getElementById('activity-indicator-' + conversationId);
  if (!indicator) return;

  // Cancel any pending update timer
  if (_activityTimers[conversationId]) {
    clearTimeout(_activityTimers[conversationId]);
    delete _activityTimers[conversationId];
  }

  indicator.style.opacity = '0';

  // Use a separate key for the hide timer
  const hideTimerKey = conversationId + '-hide';
  if (_activityTimers[hideTimerKey]) {
    clearTimeout(_activityTimers[hideTimerKey]);
  }

  _activityTimers[hideTimerKey] = setTimeout(() => {
    indicator.classList.add('hidden');
    indicator.style.opacity = '';
    delete _activityTimers[hideTimerKey];
  }, 200);
};
