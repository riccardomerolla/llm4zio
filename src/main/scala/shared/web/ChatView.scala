package shared.web

import java.net.URLEncoder

import conversation.entity.api.{ ChatConversation, ConversationEntry, ConversationSessionMeta, MessageType, SenderType }
import gateway.entity.ChatSession
import scalatags.Text.all.*
import workspace.entity.{ RunSessionMode, RunStatus }

object ChatView:

  def dashboard(
    conversations: List[ChatConversation],
    sessionMetaByConversation: Map[String, ConversationSessionMeta],
    sessions: List[ChatSession],
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
      sessionsSection(sessions),
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
            val meta = sanitizeOptionalString(conv.id).flatMap { convId =>
              sessionMetaByConversation.get(convId)
            }
            conversationCard(conv, meta)
          }
        ),
    )

  def detail(
    conversation: ChatConversation,
    sessionMeta: Option[ConversationSessionMeta],
    runSessionMeta: Option[RunSessionUiMeta],
  ): String =
    val conversationId = sanitizeOptionalString(conversation.id).getOrElse("unknown")
    val description    = sanitizeOptionalString(conversation.description)
    val runControlId   = s"run-session-controls-$conversationId"
    val issuesHref     =
      sanitizeOptionalString(conversation.runId)
        .map(runId => s"/issues?run_id=$runId")
        .getOrElse("/issues")

    Layout.page(s"Chat — ${conversation.title}", s"/chat/$conversationId")(
      div(cls := "flex flex-col min-h-[calc(100vh-9rem)]")(
        div(cls := "flex flex-col lg:flex-row lg:items-start lg:justify-between gap-4 mb-4")(
          div(cls := "min-w-0")(
            a(
              href := "/chat",
              cls  := "text-indigo-400 hover:text-indigo-300 text-sm font-medium mb-3 inline-flex items-center gap-2",
            )("← Back to Chats"),
            h1(cls := "text-2xl font-bold text-white")(conversation.title),
            description.fold[Frag](frag())(text => p(cls := "text-gray-400 text-sm mt-2")(text)),
            sessionContextPanel(sessionMeta),
            runSessionMeta.fold[Frag](frag())(meta => runChainPanel(meta)),
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
            runSessionMeta.fold[Frag](frag()) { meta =>
              frag(
                span(
                  id  := s"run-mode-badge-$conversationId",
                  cls := s"inline-flex items-center rounded-md border px-3 py-1.5 font-semibold ${runModeBadgeClass(meta.status)}",
                )(runModeLabel(meta.status)),
                span(
                  id  := s"run-attached-count-$conversationId",
                  cls := "inline-flex items-center rounded-md bg-white/5 ring-1 ring-white/10 px-3 py-1.5 text-gray-200",
                )(
                  span(cls := "text-gray-400 mr-1")("Attached:"),
                  span(cls := "font-semibold", attr("data-role") := "count")(meta.attachedUsersCount.toString),
                ),
                span(
                  id  := s"run-active-indicator-$conversationId",
                  cls := s"inline-flex items-center gap-1.5 rounded-md bg-white/5 ring-1 ring-white/10 px-3 py-1.5 text-gray-200 ${
                      if isRunActive(meta.status) then "" else "hidden"
                    }",
                )(
                  span(cls := "h-2 w-2 rounded-full bg-emerald-400 animate-pulse"),
                  span("Live"),
                ),
              )
            },
          ),
        ),
        div(cls := "relative flex-1 min-h-0 bg-white/5 ring-1 ring-white/10 rounded-lg overflow-hidden flex flex-col")(
          // Messages list — Lit web component for streaming
          tag("chat-message-stream")(
            id                      := s"messages-$conversationId",
            cls                     := "flex-1 min-h-0 overflow-y-auto p-6 space-y-4 block",
            attr("conversation-id") := conversationId,
            attr("ws-url")          := "/ws/console",
          )(
            raw(messagesFragment(conversation.messages))
          ),
          button(
            id              := s"scroll-bottom-$conversationId",
            `type`          := "button",
            cls             := "hidden absolute bottom-6 right-6 rounded-full bg-indigo-600 hover:bg-indigo-700 text-white px-4 py-2 text-xs font-semibold shadow-lg",
            attr("onclick") := s"document.getElementById('messages-$conversationId')?.scrollToLatest?.()",
          )("Scroll to bottom"),
        ),
        runSessionMeta.fold[Frag](standardChatComposer(conversationId))(meta =>
          runInteractionComposer(
            conversationId = conversationId,
            runControlId = runControlId,
            meta = meta,
          )
        ),
      ),
      JsResources.markedScript,
      tag("link")(
        attr("rel")  := "stylesheet",
        attr("href") := "https://cdn.jsdelivr.net/npm/highlight.js@11.11.1/styles/github-dark.min.css",
      ),
      JsResources.inlineModuleScript("/static/client/components/chat-message-stream.js"),
      runSessionMeta.fold[Frag](JsResources.inlineModuleScript("/static/client/components/message-composer.js"))(_ =>
        JsResources.inlineModuleScript("/static/client/components/run-session-controls.js")
      ),
    )

  def messagesFragment(messages: List[ConversationEntry]): String =
    div(cls := "space-y-4 text-gray-100")(
      messages.map(messageCard)
    ).render

  private def messageCard(message: ConversationEntry): Frag =
    message.messageType match
      case MessageType.ToolCall   => toolCallCard(message)
      case MessageType.ToolResult => toolResultCard(message)
      case _                      =>
        message.senderType match
          case SenderType.System =>
            div(cls := "flex justify-center", attr("data-sender") := "system")(
              div(
                cls := "max-w-[90%] rounded-lg border border-amber-300/25 bg-amber-500/15 px-4 py-2 text-xs text-amber-100"
              )(
                message.content
              )
            )
          case sender            =>
            val isUser                                           = sender == SenderType.User
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

  private def toolCallCard(message: ConversationEntry): Frag =
    div(cls := "flex justify-start")(
      div(
        cls := "tool-call-block max-w-[85%] lg:max-w-[72%] border border-indigo-400/20 bg-indigo-950/40 rounded-xl px-4 py-3 my-1"
      )(
        tag("details")(
          tag("summary")(cls := "cursor-pointer select-none font-mono text-xs font-semibold text-indigo-300")(
            "\u2699 Tool call"
          ),
          pre(
            cls := "mt-2 text-xs text-indigo-100 whitespace-pre-wrap break-words font-mono bg-black/30 rounded p-2 overflow-auto"
          )(
            message.content
          ),
        )
      )
    )

  private def toolResultCard(message: ConversationEntry): Frag =
    div(cls := "flex justify-start")(
      div(
        cls := "tool-result-block max-w-[85%] lg:max-w-[72%] border border-emerald-400/20 bg-emerald-950/40 rounded-xl px-4 py-3 my-1"
      )(
        tag("details")(
          tag("summary")(cls := "cursor-pointer select-none font-mono text-xs font-semibold text-emerald-300")(
            "\u2713 Tool result"
          ),
          pre(
            cls := "mt-2 text-xs text-emerald-100 whitespace-pre-wrap break-words font-mono bg-black/30 rounded p-2 overflow-auto"
          )(
            message.content
          ),
        )
      )
    )

  private def runChainPanel(meta: RunSessionUiMeta): Frag =
    div(cls := "mt-3 flex flex-wrap items-center gap-2 text-xs")(
      span(cls := "text-gray-400 font-semibold")("Run chain:"),
      if meta.breadcrumb.isEmpty then
        span(cls := "text-gray-300 font-mono")(meta.runId.take(8))
      else
        frag(meta.breadcrumb.zipWithIndex.map {
          case (item, idx) =>
            frag(
              if idx > 0 then span(cls := "text-gray-500")("→") else frag(),
              a(
                href := s"/chat/${item.conversationId}",
                cls  := (
                  if item.runId == meta.runId then
                    "rounded bg-indigo-500/30 px-2 py-1 text-indigo-100 font-mono"
                  else "rounded bg-white/5 px-2 py-1 text-gray-300 hover:text-white font-mono"
                ),
              )(item.runId.take(8)),
            )
        }*)
      ,
      meta.parent.fold[Frag](frag())(prev =>
        a(
          href := s"/chat/${prev.conversationId}",
          cls  := "rounded border border-white/15 px-2 py-1 text-gray-300 hover:text-white",
        )("Previous run")
      ),
      meta.next.fold[Frag](frag())(next =>
        a(
          href := s"/chat/${next.conversationId}",
          cls  := "rounded border border-white/15 px-2 py-1 text-gray-300 hover:text-white",
        )("Next run")
      ),
    )

  private def standardChatComposer(conversationId: String): Frag =
    div(
      cls := "sticky bottom-0 mt-3 rounded-lg bg-gray-900/95 ring-1 ring-white/10 p-3 backdrop-blur"
    )(
      form(
        method                        := "post",
        action                        := s"/chat/$conversationId/messages",
        attr("hx-post")               := s"/chat/$conversationId/messages",
        attr("hx-target")             := s"#messages-$conversationId",
        attr("hx-swap")               := "innerHTML",
        attr("hx-disabled-elt")       := "button[type='submit']",
        attr("hx-on::before-request") := s"""
          |document.getElementById('messages-$conversationId')?.markPending?.();
        """.stripMargin.trim,
        attr("hx-on::after-request")  := """
          |this.reset();
        """.stripMargin.trim,
        cls                           := "space-y-2",
      )(
        input(`type` := "hidden", name := "fragment", value := "true"),
        div(
          id                           := "chat-composer",
          cls                          := "space-y-2",
          attr("data-conversation-id") := conversationId,
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
                id                   := s"chat-input-$conversationId",
                name                 := "content",
                placeholder          := "Type your message... Use @agent-name to route directly.",
                rows                 := 5,
                cls                  := "w-full bg-white/10 border border-white/20 rounded-lg px-4 py-3 text-white placeholder:text-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500 font-mono text-sm",
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
            id              := s"abort-btn-$conversationId",
            `type`          := "button",
            cls             := "hidden px-4 py-2 bg-red-600 hover:bg-red-700 text-white rounded-lg font-medium transition-colors",
            attr("onclick") := s"document.getElementById('messages-$conversationId').abort()",
          )("Stop"),
        ),
      )
    )

  private def runInteractionComposer(conversationId: String, runControlId: String, meta: RunSessionUiMeta): Frag =
    div(
      id                           := runControlId,
      cls                          := "sticky bottom-0 mt-3 rounded-lg bg-gray-900/95 ring-1 ring-white/10 p-3 backdrop-blur space-y-3",
      attr("data-conversation-id") := conversationId,
      attr("data-run-id")          := meta.runId,
      attr("data-workspace-id")    := meta.workspaceId,
      attr("data-status")          := runStateCode(meta.status),
      attr("data-attached-count")  := meta.attachedUsersCount.toString,
      attr("data-is-attached")     := "false",
    )(
      div(cls := "flex flex-wrap items-center gap-2")(
        button(
          `type`            := "button",
          cls               := "rounded-md bg-cyan-600 hover:bg-cyan-700 text-white px-3 py-1.5 text-xs font-semibold",
          attr("data-role") := "attach",
        )("Attach"),
        button(
          `type`            := "button",
          cls               := "hidden rounded-md bg-slate-600 hover:bg-slate-700 text-white px-3 py-1.5 text-xs font-semibold",
          attr("data-role") := "detach",
        )("Detach"),
        button(
          `type`            := "button",
          cls               := s"hidden rounded-md bg-amber-600 hover:bg-amber-700 text-white px-3 py-1.5 text-xs font-semibold ${
              if runStateCode(meta.status).startsWith("running") then "" else "hidden"
            }",
          attr("data-role") := "interrupt",
        )("Interrupt"),
        button(
          `type`            := "button",
          cls               := s"rounded-md bg-emerald-600 hover:bg-emerald-700 text-white px-3 py-1.5 text-xs font-semibold ${
              if showContinue(runStateCode(meta.status)) then "" else "hidden"
            }",
          attr("data-role") := "continue",
        )("Continue"),
        button(
          `type`            := "button",
          cls               := "rounded-md bg-rose-600 hover:bg-rose-700 text-white px-3 py-1.5 text-xs font-semibold",
          attr("data-role") := "cancel",
        )("Cancel"),
      ),
      form(cls := "space-y-2", attr("data-role") := "run-form")(
        textarea(
          attr("data-role") := "run-input",
          rows              := 4,
          placeholder       := runInputPlaceholder(runStateCode(meta.status)),
          cls               := "w-full bg-white/10 border border-white/20 rounded-lg px-4 py-3 text-white placeholder:text-gray-500 focus:outline-none focus:ring-2 focus:ring-cyan-500 font-mono text-sm disabled:opacity-60",
          if isRunInputEnabled(runStateCode(meta.status)) then () else disabled,
          attr("title")     := runInputTooltip(runStateCode(meta.status)),
        )(),
        div(cls := "flex items-center justify-between gap-2")(
          span(
            cls               := "text-xs text-gray-400",
            attr("data-role") := "feedback",
          )("Enter to send, Shift+Enter for newline"),
          button(
            `type`            := "submit",
            cls               := "px-4 py-2 bg-cyan-600 hover:bg-cyan-700 text-white rounded-lg font-medium transition-colors disabled:opacity-50",
            attr("data-role") := "send",
            if isRunInputEnabled(runStateCode(meta.status)) then () else disabled,
          )("Send to Run"),
        ),
      ),
    )

  private def runModeLabel(status: RunStatus): String = status match
    case RunStatus.Running(RunSessionMode.Autonomous)  => "Autonomous"
    case RunStatus.Running(RunSessionMode.Interactive) => "Interactive"
    case RunStatus.Running(RunSessionMode.Paused)      => "Paused"
    case RunStatus.Pending                             => "Pending"
    case RunStatus.Completed                           => "Completed"
    case RunStatus.Failed                              => "Failed"
    case RunStatus.Cancelled                           => "Cancelled"

  private def runModeBadgeClass(status: RunStatus): String = status match
    case RunStatus.Running(RunSessionMode.Autonomous)  => "border-blue-400/40 bg-blue-500/20 text-blue-200"
    case RunStatus.Running(RunSessionMode.Interactive) => "border-emerald-400/40 bg-emerald-500/20 text-emerald-200"
    case RunStatus.Running(RunSessionMode.Paused)      => "border-amber-400/40 bg-amber-500/20 text-amber-200"
    case RunStatus.Pending                             => "border-slate-400/40 bg-slate-500/20 text-slate-200"
    case RunStatus.Completed                           => "border-emerald-400/40 bg-emerald-500/20 text-emerald-200"
    case RunStatus.Failed                              => "border-rose-400/40 bg-rose-500/20 text-rose-200"
    case RunStatus.Cancelled                           => "border-orange-400/40 bg-orange-500/20 text-orange-200"

  private def isRunActive(status: RunStatus): Boolean = status match
    case RunStatus.Pending | RunStatus.Running(_) => true
    case _                                        => false

  private def runStateCode(status: RunStatus): String = status match
    case RunStatus.Running(RunSessionMode.Autonomous)  => "running:autonomous"
    case RunStatus.Running(RunSessionMode.Interactive) => "running:interactive"
    case RunStatus.Running(RunSessionMode.Paused)      => "running:paused"
    case RunStatus.Pending                             => "pending"
    case RunStatus.Completed                           => "completed"
    case RunStatus.Failed                              => "failed"
    case RunStatus.Cancelled                           => "cancelled"

  private def isRunInputEnabled(state: String): Boolean =
    state == "running:interactive" || state == "running:paused"

  private def showContinue(state: String): Boolean =
    state == "running:paused" || state == "completed" || state == "failed" || state == "cancelled"

  private def runInputTooltip(state: String): String =
    if state == "running:autonomous" || state == "pending" then "Attach to interact"
    else "Send follow-up instructions to this run"

  private def runInputPlaceholder(state: String): String =
    if state == "running:autonomous" || state == "pending" then "Attach to interact"
    else "Send instructions to the active run..."

  private def looksLikeMarkdown(text: String): Boolean =
    val lines = text.split("\n").toList
    lines.exists(_.trim.startsWith("```")) ||
    looksLikeMarkdownTable(lines) ||
    lines.exists(line => line.trim.matches("^#{1,6}\\s+.*$")) ||
    lines.exists(line => line.trim.matches("^[-*+]\\s+.*$")) ||
    lines.exists(line => line.trim.matches("^\\d+\\.\\s+.*$")) ||
    lines.exists(_.trim.startsWith(">")) ||
    text.contains("**") ||
    text.contains("`") ||
    text.matches("(?s).*\\[[^\\]]+\\]\\((https?://|/|#)[^)]+\\).*")

  private def looksLikeMarkdownTable(lines: List[String]): Boolean =
    lines.sliding(2).exists {
      case List(header, divider) =>
        val parsedHeader  = parseTableRow(header)
        val parsedDivider = parseTableRow(divider)
        parsedHeader.nonEmpty && parsedDivider.length == parsedHeader.length &&
        parsedDivider.forall(cell => cell.matches("^:?-{3,}:?$"))
      case _                     => false
    }

  private def parseTableRow(line: String): List[String] =
    val stripped = line.trim.stripPrefix("|").stripSuffix("|")
    stripped.split("\\|", -1).toList.map(_.trim)

  private def conversationCard(
    conv: ChatConversation,
    sessionMeta: Option[ConversationSessionMeta] = None,
  ): Frag =
    val conversationId = sanitizeOptionalString(conv.id).getOrElse("unknown")
    val lastMessage    = conv.messages.lastOption
    val preview        = lastMessage.map(_.content.trim).filter(_.nonEmpty).map(compactPreview).getOrElse("No messages yet")
    val channel        = sanitizeOptionalString(conv.channel)
      .orElse(sessionMeta.flatMap(meta => sanitizeString(meta.channelName)))
      .getOrElse("web")
    div(id := s"conv-card-$conversationId", cls := "group flex items-stretch gap-2")(
      a(
        href := s"/chat/$conversationId",
        cls  := "flex-1 bg-white/5 hover:bg-white/10 ring-1 ring-white/10 rounded-lg p-4 transition-all hover:ring-indigo-500/50 cursor-pointer block",
      )(
        div(cls := "flex items-start justify-between mb-2")(
          div(cls := "min-w-0")(
            h3(cls := "text-lg font-semibold text-white max-w-xs truncate")(conv.title),
            div(cls := "mt-1 flex items-center gap-2 text-xs")(
              channelBadge(channel),
              sessionMeta
                .filter(meta => sanitizeString(meta.channelName).contains("telegram"))
                .flatMap(meta =>
                  sanitizeString(meta.sessionKey).map(key =>
                    span(cls := "text-amber-300/90")(s"via Telegram ($key)")
                  )
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
          p(cls := "text-gray-300 text-sm mb-2 truncate")(preview)
        ),
        div(cls := "flex items-center justify-between text-xs text-gray-500")(
          span(s"${conv.messages.length} message${if conv.messages.length != 1 then "s" else ""}"),
          span(cls := "text-right")(
            lastMessage.map(_.createdAt).map(formatTimestamp).getOrElse(formatTimestamp(conv.updatedAt))
          ),
        ),
      ),
      button(
        cls                          := "invisible group-hover:visible flex-shrink-0 self-center flex items-center justify-center w-8 h-8 rounded-md bg-red-600/20 hover:bg-red-500 text-red-400 hover:text-white transition-colors",
        attr("hx-delete")            := s"/api/conversations/$conversationId",
        attr("hx-confirm")           := s"Delete conversation '${conv.title}'?",
        attr("hx-on::after-request") := "if(event.detail.successful){window.location.reload();}",
        attr("title")                := "Delete conversation",
      )(
        raw(
          """<svg xmlns="http://www.w3.org/2000/svg" class="w-4 h-4" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z" clip-rule="evenodd"/></svg>"""
        )
      ),
    )

  private def channelBadge(channel: String): Frag =
    val normalized = sanitizeString(channel).map(_.toLowerCase).getOrElse("web")
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
        val sessionId =
          s"${sanitizeString(meta.channelName).getOrElse("unknown")}:${sanitizeString(meta.sessionKey).getOrElse("unknown")}"
        val encoded   = URLEncoder.encode(sessionId, "UTF-8")
        div(cls := "mt-3 rounded-md bg-white/5 ring-1 ring-white/10 p-3 text-xs text-gray-300")(
          div(cls := "font-semibold text-gray-200 mb-2")("Session Context"),
          div(cls := "space-y-1")(
            p(span(cls := "text-gray-400 mr-2")("Channel:"), sanitizeString(meta.channelName).getOrElse("unknown")),
            p(span(cls := "text-gray-400 mr-2")("Session Key:"), sanitizeString(meta.sessionKey).getOrElse("unknown")),
            p(
              span(cls := "text-gray-400 mr-2")("Linked Task:"),
              meta.linkedTaskRunId.map(id => s"#$id").getOrElse("none"),
            ),
            p(span(cls := "text-gray-400 mr-2")("Updated:"), formatTimestamp(meta.updatedAt)),
          ),
          div(cls := "mt-3")(
            button(
              cls                           := "rounded-md bg-red-600 hover:bg-red-500 px-3 py-1.5 text-xs font-semibold text-white",
              attr("hx-delete")             := s"/api/sessions/$encoded",
              attr("hx-confirm")            := "End this session?",
              attr("hx-swap")               := "none",
              attr("hx-on::after-request")  := "window.location='/chat';",
              attr("hx-on::response-error") := "window.location='/chat';",
            )("End Session")
          ),
        )

  private def sessionsSection(sessions: List[ChatSession]): Frag =
    div(cls := "mb-6 rounded-lg bg-white/5 ring-1 ring-white/10 p-4")(
      div(cls := "mb-3 flex items-center justify-between gap-3")(
        h2(cls := "text-sm font-semibold text-white")("Active Sessions"),
        span(cls := "text-xs text-gray-400")(s"${sessions.length} active"),
      ),
      if sessions.isEmpty then
        p(cls := "text-xs text-gray-400")("No active sessions.")
      else
        div(cls := "overflow-x-auto")(
          table(cls := "min-w-full text-left text-xs text-gray-200")(
            thead(
              tr(cls := "text-gray-400")(
                th(cls := "py-2 pr-3")("Session"),
                th(cls := "py-2 pr-3")("Agent"),
                th(cls := "py-2 pr-3")("Messages"),
                th(cls := "py-2 pr-3")("Last Activity"),
                th(cls := "py-2 pr-3")(""),
              )
            ),
            tbody(
              sessions.map { session =>
                val encoded = URLEncoder.encode(session.sessionId, "UTF-8")
                tr(cls := "border-t border-white/10")(
                  td(cls := "py-2 pr-3 font-mono text-[11px]")(session.sessionId),
                  td(cls := "py-2 pr-3")(session.agentName.getOrElse("-")),
                  td(cls := "py-2 pr-3")(session.messageCount.toString),
                  td(cls := "py-2 pr-3")(formatTimestamp(session.lastActivity)),
                  td(cls := "py-2 pr-0 text-right")(
                    button(
                      cls                := "rounded-md bg-red-600/80 hover:bg-red-500 px-2.5 py-1 text-[11px] font-semibold text-white",
                      attr("hx-delete")  := s"/api/sessions/$encoded",
                      attr("hx-confirm") := s"End session ${session.sessionId}?",
                      attr("hx-target")  := "closest tr",
                      attr("hx-swap")    := "outerHTML",
                    )("End")
                  ),
                )
              }
            ),
          )
        ),
    )

  private def compactPreview(raw: String): String =
    val compact = raw.replaceAll("\\s+", " ").trim
    if compact.length <= 90 then compact else compact.take(87) + "..."

  private def sanitizeString(value: String): Option[String] =
    Option(value).map(_.trim).filter(_.nonEmpty)

  private def sanitizeOptionalString(value: Option[String]): Option[String] =
    try
      value match
        case Some(v) => sanitizeString(v)
        case _       => None
    catch
      case _: Throwable => None

  private def formatTimestamp(instant: java.time.Instant): String =
    instant.toString.take(19).replace("T", " ")
