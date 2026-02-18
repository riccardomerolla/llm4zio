package web.views

import models.{ ChatConversation, ConversationMessage, ConversationSessionMeta, SenderType }
import scalatags.Text.all.*

object ChatView:

  def dashboard(
    conversations: List[ChatConversation],
    sessionMetaByConversation: Map[Long, ConversationSessionMeta],
  ): String =
    Layout.page("Chat — COBOL Modernization", "/chat")(
      div(cls := "mb-6")(
        div(cls := "flex items-center justify-between")(
          h1(cls := "text-2xl font-bold text-white")("Chat Interface"),
          form(method := "post", action := "/chat", cls := "flex items-center gap-2")(
            input(
              name        := "title",
              placeholder := "New chat title",
              cls         := "bg-white/10 border border-white/20 rounded-lg px-3 py-2 text-white text-sm placeholder:text-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500",
              required,
            ),
            button(
              `type` := "submit",
              cls    := "inline-flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg font-medium transition-colors",
            )("+ New Chat"),
          ),
        ),
        p(cls := "text-gray-400 text-sm mt-2")("Manage conversations with the AI service"),
      ),
      if conversations.isEmpty then
        div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-12 text-center")(
          p(cls := "text-gray-400 mb-4")("No conversations yet"),
          a(
            href := "/chat",
            cls  := "inline-flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg",
          )("Start New Conversation"),
        )
      else
        div(cls := "grid grid-cols-1 gap-4")(
          conversations.map { conv =>
            val meta = conv.id.flatMap(sessionMetaByConversation.get)
            conversationCard(conv, meta)
          }
        ),
    )

  def detail(
    conversation: ChatConversation,
    sessionMeta: Option[ConversationSessionMeta],
  ): String =
    val issuesHref = conversation.runId match
      case Some(runId) => s"/issues?run_id=$runId"
      case None        => "/issues"

    Layout.page(s"Chat — ${conversation.title}", s"/chat/${conversation.id.get}")(
      div(cls := "flex flex-col min-h-[calc(100vh-9rem)]")(
        div(cls := "flex flex-col lg:flex-row lg:items-start lg:justify-between gap-4 mb-4")(
          div(cls := "min-w-0")(
            a(
              href := "/chat",
              cls  := "text-indigo-400 hover:text-indigo-300 text-sm font-medium mb-3 inline-flex items-center gap-2",
            )("← Back to Chats"),
            h1(cls := "text-2xl font-bold text-white")(conversation.title),
            if conversation.description.isDefined then
              p(cls := "text-gray-400 text-sm mt-2")(conversation.description.get)
            else (),
            sessionContextPanel(sessionMeta),
          ),
          div(cls := "inline-flex flex-wrap items-center gap-2 text-xs self-start lg:justify-end")(
            span(
              cls := "inline-flex items-center rounded-md bg-white/5 ring-1 ring-white/10 px-3 py-1.5 text-gray-200"
            )(
              span(cls := "text-gray-400 mr-1")("Status:"),
              span(cls := "font-semibold capitalize")(conversation.status),
            ),
            span(
              cls := "inline-flex items-center rounded-md bg-white/5 ring-1 ring-white/10 px-3 py-1.5 text-gray-200"
            )(
              span(cls := "text-gray-400 mr-1")("Messages:"),
              span(cls := "font-semibold")(conversation.messages.length.toString),
            ),
            span(
              cls := "inline-flex items-center rounded-md bg-white/5 ring-1 ring-white/10 px-3 py-1.5 text-gray-200"
            )(
              span(cls := "text-gray-400 mr-1")("Created:"),
              span(cls := "font-semibold")(conversation.createdAt.toString.take(19)),
            ),
            a(
              href := issuesHref,
              cls  := "inline-flex items-center rounded-md bg-indigo-600 hover:bg-indigo-700 text-white px-3 py-1.5 font-semibold transition-colors",
            )("View Related Issues"),
          ),
        ),
        div(cls := "flex-1 min-h-0 bg-white/5 ring-1 ring-white/10 rounded-lg overflow-hidden flex flex-col")(
          // Messages list — Lit web component for streaming
          tag("chat-message-stream")(
            id                      := s"messages-${conversation.id.get}",
            cls                     := "flex-1 min-h-0 overflow-y-auto p-6 space-y-4 block",
            attr("conversation-id") := conversation.id.get.toString,
            attr("ws-url")          := "/ws/console",
          )(
            raw(messagesFragment(conversation.messages))
          )
        ),
        // Sticky composer
        div(
          cls := "sticky bottom-0 mt-3 rounded-lg bg-gray-900/95 ring-1 ring-white/10 p-3 backdrop-blur"
        )(
          form(
            method                       := "post",
            action                       := s"/chat/${conversation.id.get}/messages",
            attr("hx-post")              := s"/chat/${conversation.id.get}/messages",
            attr("hx-target")            := s"#messages-${conversation.id.get}",
            attr("hx-swap")              := "innerHTML",
            attr("hx-disabled-elt")      := "button[type='submit']",
            attr("hx-on::before-request") := s"""
              |document.getElementById('messages-${conversation.id.get}')?.markPending?.();
            """.stripMargin.trim,
            attr("hx-on::after-request") := s"""
              |this.reset();
            """.stripMargin.trim,
            cls                          := "space-y-2",
          )(
            input(`type`  := "hidden", name := "fragment", value := "true"),
            div(
              id                           := "chat-composer",
              cls                          := "space-y-2",
              attr("data-conversation-id") := conversation.id.get.toString,
              attr("data-agents-endpoint") := "/api/agents",
            )(
              div(cls := "flex flex-wrap items-center gap-2")(
                button(
                  `type`            := "button",
                  cls               := "rounded-md bg-white/10 hover:bg-white/20 px-3 py-1.5 text-xs font-semibold text-gray-200",
                  attr("data-role") := "mode-toggle",
                )("Preview"),
                select(
                  cls               := "rounded-md bg-white/10 border border-white/20 px-2 py-1.5 text-xs text-gray-100",
                  attr("data-role") := "code-language",
                )(
                  option(value := "plain")("plain"),
                  option(value := "scala")("scala"),
                  option(value := "python")("python"),
                  option(value := "bash")("bash"),
                  option(value := "json")("json"),
                  option(value := "yaml")("yaml"),
                ),
                button(
                  `type`            := "button",
                  cls               := "rounded-md bg-white/10 hover:bg-white/20 px-3 py-1.5 text-xs font-semibold text-gray-200",
                  attr("data-role") := "insert-code",
                )("</> Code"),
                span(cls := "text-xs text-gray-400")("Ctrl/Cmd+Enter send, Ctrl+Shift+P preview, Ctrl+K code"),
              ),
              div(cls := "relative")(
                div(attr("data-role") := "write-pane")(
                  textarea(
                    id          := s"chat-input-${conversation.id.get}",
                    name        := "content",
                    placeholder := "Type your message... Use @agent-name to route directly.",
                    rows        := 5,
                    cls         := "w-full bg-white/10 border border-white/20 rounded-lg px-4 py-3 text-white placeholder:text-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500 font-mono text-sm",
                    required,
                    attr("autocomplete") := "off",
                    attr("spellcheck")   := "false",
                  )()
                ),
                div(
                  attr("data-role") := "preview-pane",
                  cls               := "hidden min-h-[7rem] rounded-lg border border-white/20 bg-black/20 px-4 py-3 text-sm leading-6 text-gray-100 overflow-auto",
                )(),
                div(
                  attr("data-role") := "mentions",
                  cls               := "hidden absolute left-0 right-0 mt-1 max-h-48 overflow-auto rounded-md border border-white/15 bg-slate-900/95 shadow-lg z-20",
                )(),
              ),
            ),
            div(cls := "flex items-center gap-2")(
              button(
                `type` := "submit",
                cls    := "px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg font-medium transition-colors",
              )("Send"),
              button(
                id              := s"abort-btn-${conversation.id.get}",
                `type`          := "button",
                cls             := "hidden px-4 py-2 bg-red-600 hover:bg-red-700 text-white rounded-lg font-medium transition-colors",
                attr("onclick") := s"document.getElementById('messages-${conversation.id.get}').abort()",
              )("Stop"),
            ),
          )
        ),
      ),
      streamingScript(conversation.id.get),
      JsResources.markedScript,
      tag("link")(
        attr("rel")  := "stylesheet",
        attr("href") := "https://cdn.jsdelivr.net/npm/highlight.js@11.11.1/styles/github-dark.min.css",
      ),
      JsResources.inlineModuleScript("/static/client/components/message-composer.js"),
    )

  def messagesFragment(messages: List[ConversationMessage]): String =
    div(cls := "space-y-4 text-gray-100")(
      messages.map(messageCard)
    ).render

  private def messageCard(message: ConversationMessage): Frag =
    val isUser                                           = message.senderType == SenderType.User
    val (containerClasses, bubbleClasses, senderClasses) =
      if isUser then
        (
          "flex justify-end",
          "max-w-[85%] lg:max-w-[72%] rounded-2xl rounded-br-md border border-indigo-300/20 bg-indigo-500/90 px-4 py-3 shadow-lg shadow-indigo-900/20",
          "text-indigo-100",
        )
      else
        (
          "flex justify-start",
          "max-w-[85%] lg:max-w-[72%] rounded-2xl rounded-bl-md border border-white/15 bg-slate-800/80 px-4 py-3 shadow-lg shadow-black/20",
          "text-slate-300",
        )

    div(cls := containerClasses, attr("data-sender") := (if isUser then "user" else "assistant"))(
      div(cls := bubbleClasses)(
        div(cls := s"text-xs font-semibold mb-2 $senderClasses")(
          message.sender
        ),
        if !isUser && looksLikeMarkdown(message.content) then
          div(
            cls := "text-sm leading-6 text-gray-50 [&_p]:my-2 [&_ul]:my-2 [&_ol]:my-2 [&_h1]:mt-2 [&_h2]:mt-2 [&_h3]:mt-2"
          )(
            IssuesView.markdownFragment(message.content)
          )
        else
          pre(
            cls := "whitespace-pre-wrap break-words text-sm leading-6 text-gray-50 font-sans m-0"
          )(
            message.content
          ),
      )
    )

  private def looksLikeMarkdown(text: String): Boolean =
    val lines = text.split("\n").toList
    lines.exists(_.trim.startsWith("```")) ||
    lines.exists(line => line.trim.matches("^#{1,6}\\s+.*$")) ||
    lines.exists(line => line.trim.matches("^[-*+]\\s+.*$")) ||
    lines.exists(line => line.trim.matches("^\\d+\\.\\s+.*$")) ||
    lines.exists(_.trim.startsWith(">")) ||
    text.contains("**") ||
    text.contains("`") ||
    text.matches("(?s).*\\[[^\\]]+\\]\\((https?://|/|#)[^)]+\\).*")

  // scalafmt: { maxColumn = 500 }
  private def streamingScript(conversationId: Long): Frag =
    script(attr("type") := "module")(raw(s"""
import {LitElement} from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

class ChatMessageStream extends LitElement {
  static properties = {
    conversationId: {type: Number, attribute: 'conversation-id'},
    wsUrl: {type: String, attribute: 'ws-url'},
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
      'Thinking through your request...',
      'Checking context and dependencies...',
      'Preparing the best possible answer...',
      'Running reasoning and validations...',
      'Finalizing response...'
    ];
    this._lastSnapshot = '';
  }

  createRenderRoot() { return this; }

  connectedCallback() {
    super.connectedCallback();
    this._lastSnapshot = this._normalizedSnapshot(this.innerHTML);
    this._assistantCountAtPending = this._countAssistantMessages();
    this._scrollToBottom();
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
    if (!this._wsEnabled || !this.wsUrl) return;
    try {
      const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
      this._ws = new WebSocket(protocol + '//' + location.host + this.wsUrl);
      this._ws.onopen = () => {
        this._wsEverOpened = true;
        this._ws.send(JSON.stringify({Subscribe:{topic:'chat:'+this.conversationId+':stream',params:{}}}));
      };
      this._ws.onmessage = (e) => {
        try { this._handleMessage(JSON.parse(e.data)); } catch(ignored) {}
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
    } catch (ignored) {}
  }

  _handleMessage(msg) {
    if (!msg.Event) return;
    const {eventType, payload} = msg.Event;
    let parsed = {};
    try { parsed = JSON.parse(payload); } catch(ignored) { parsed = {type: eventType, delta: payload}; }
    const evtType = parsed.type || eventType;

    switch (evtType) {
      case 'chat-stream-start':
        this._streaming = true;
        this._streamBuffer = '';
        this._appendStreamBubble();
        this._toggleAbortButton(true);
        break;
      case 'chat-chunk':
        this._streamBuffer += (parsed.delta || '');
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
    inner.className = 'max-w-[85%] lg:max-w-[72%] rounded-2xl rounded-bl-md border border-indigo-300/25 bg-slate-800/60 px-4 py-3 shadow-lg shadow-black/20';
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

  _scrollToBottom() {
    requestAnimationFrame(() => {
      this.scrollTop = this.scrollHeight;
      requestAnimationFrame(() => {
        this.scrollTop = this.scrollHeight;
      });
    });
  }

  _toggleAbortButton(show) {
    const btn = document.getElementById('abort-btn-$conversationId');
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
    return (value || '').replace(/\\s+/g, ' ').trim();
  }

  _countAssistantMessages() {
    return this.querySelectorAll('[data-sender="assistant"]').length;
  }

  _refreshMessages() {
    fetch('/chat/$conversationId/messages')
      .then(r => r.text())
      .then(t => {
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
    if (this._wsEnabled && this._ws && this._ws.readyState === WebSocket.OPEN) {
      this._ws.send(JSON.stringify({AbortChat:{conversationId:$conversationId}}));
    }
    this._streaming = false;
    this._removeStreamBubble();
    this._toggleAbortButton(false);
    this._stopPendingPoll();
    fetch('/api/chat/$conversationId/abort', {method:'POST'});
  }
}

if (!customElements.get('chat-message-stream')) {
  customElements.define('chat-message-stream', ChatMessageStream);
}
"""))
  // scalafmt: { maxColumn = 120 }

  private def conversationCard(
    conv: ChatConversation,
    sessionMeta: Option[ConversationSessionMeta] = None,
  ): Frag =
    val lastMessage = conv.messages.lastOption
    val preview     = lastMessage.map(_.content.trim).filter(_.nonEmpty).map(compactPreview).getOrElse("No messages yet")
    val channel     = conv.channel.orElse(sessionMeta.map(_.channelName)).getOrElse("web")
    a(
      href := s"/chat/${conv.id.get}",
      cls  := "bg-white/5 hover:bg-white/10 ring-1 ring-white/10 rounded-lg p-4 transition-all hover:ring-indigo-500/50 cursor-pointer block",
    )(
      div(cls := "flex items-start justify-between mb-2")(
        div(cls := "min-w-0")(
          h3(cls := "text-lg font-semibold text-white max-w-xs truncate")(conv.title),
          div(cls := "mt-1 flex items-center gap-2 text-xs")(
            channelBadge(channel),
            sessionMeta.filter(_.channelName == "telegram").map(meta =>
              span(cls := "text-amber-300/90")(s"via Telegram (${meta.sessionKey})")
            ),
          ),
        ),
        span(
          cls := s"inline-flex items-center rounded-md px-2 py-1 text-xs font-medium ${
              if conv.status == "active" then
                "bg-green-500/10 text-green-400 ring-1 ring-inset ring-green-500/20"
              else
                "bg-gray-500/10 text-gray-400 ring-1 ring-inset ring-gray-500/20"
            }"
        )(
          span(
            cls   := "w-1.5 h-1.5 rounded-full mr-1.5",
            style := s"background-color: ${if conv.status == "active" then "#10b981" else "#6b7280"}",
          ),
          conv.status,
        ),
      ),
      div(cls := "pt-2")(
        p(cls := "text-gray-300 text-sm mb-2 truncate")(preview),
      ),
      div(cls := "flex items-center justify-between text-xs text-gray-500")(
        span(s"${conv.messages.length} message${if conv.messages.length != 1 then "s" else ""}"),
        span(cls := "text-right")(
          lastMessage.map(_.createdAt).map(formatTimestamp).getOrElse(formatTimestamp(conv.updatedAt))
        ),
      ),
    )

  private def channelBadge(channel: String): Frag =
    val normalized = channel.trim.toLowerCase
    val classes    = normalized match
      case "telegram"  => "bg-sky-500/10 text-sky-300 ring-sky-400/30"
      case "websocket" => "bg-indigo-500/10 text-indigo-300 ring-indigo-400/30"
      case _           => "bg-gray-500/10 text-gray-300 ring-gray-400/30"
    span(cls := s"inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium ring-1 ring-inset $classes")(
      normalized
    )

  private def sessionContextPanel(sessionMeta: Option[ConversationSessionMeta]): Frag =
    sessionMeta match
      case None       => frag()
      case Some(meta) =>
        div(cls := "mt-3 rounded-md bg-white/5 ring-1 ring-white/10 p-3 text-xs text-gray-300")(
          div(cls := "font-semibold text-gray-200 mb-2")("Session Context"),
          div(cls := "space-y-1")(
            p(span(cls := "text-gray-400 mr-2")("Channel:"), meta.channelName),
            p(span(cls := "text-gray-400 mr-2")("Session Key:"), meta.sessionKey),
            p(
              span(cls := "text-gray-400 mr-2")("Linked Task:"),
              meta.linkedTaskRunId.map(id => s"#$id").getOrElse("none")
            ),
            p(span(cls := "text-gray-400 mr-2")("Updated:"), formatTimestamp(meta.updatedAt)),
          ),
        )

  private def compactPreview(raw: String): String =
    val compact = raw.replaceAll("\\s+", " ").trim
    if compact.length <= 90 then compact else compact.take(87) + "..."

  private def formatTimestamp(instant: java.time.Instant): String =
    instant.toString.take(19).replace("T", " ")
