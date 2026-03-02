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
        break;
      case 'chat-chunk':
        this._streamBuffer += parsed.delta || '';
        this._updateStreamBubble();
        break;
      case 'chat-stream-end':
      case 'chat-aborted':
        this._streaming = false;
        this._removeStreamBubble();
        this._toggleAbortButton(false);
        this._refreshMessages();
        break;
      case 'chat-message':
        if (!this._streaming) this._refreshMessages();
        break;
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
