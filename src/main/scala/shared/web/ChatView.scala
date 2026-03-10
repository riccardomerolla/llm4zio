package shared.web

import java.net.URLEncoder

import conversation.entity.api.{ ChatConversation, ConversationEntry, ConversationSessionMeta, MessageType, SenderType }
import db.TaskReportRow
import gateway.entity.ChatSession
import scalatags.Text.all.*
import workspace.entity.{ RunSessionMode, RunStatus }

object ChatView:

  final case class ChatWorkspaceFolder(
    id: String,
    label: String,
    chats: List[ChatConversation],
  )

  def dashboard(
    conversations: List[ChatConversation],
    sessionMetaByConversation: Map[String, ConversationSessionMeta],
    sessions: List[ChatSession],
    workspaceFolders: List[ChatWorkspaceFolder],
  ): String =
    val latestHref = conversations.sortBy(_.updatedAt)(Ordering[java.time.Instant].reverse).flatMap(_.id).headOption
      .map(id => s"/chat/$id")
      .getOrElse("/chat")
    Layout.page(
      "Chat",
      "/chat",
      chatWorkspaceNav = Some(buildWorkspaceNav(workspaceFolders, None, showNewChat = true)),
    )(
      div(cls := "rounded-lg border border-white/10 bg-slate-950/70 p-4 space-y-3")(
        div(
          h1(cls := "text-sm font-semibold text-white")("Chat"),
          p(cls := "text-[11px] text-gray-400")("Select a workspace chat from the left navigation."),
        ),
        a(
          href := latestHref,
          cls  := "inline-flex rounded bg-indigo-600 px-3 py-1.5 text-[11px] font-semibold text-white hover:bg-indigo-700",
        )("Open Latest Chat"),
        sessionsSection(sessions),
      )
    )

  def emptyState(workspaceFolders: List[ChatWorkspaceFolder]): String =
    Layout.page(
      "Chat",
      "/chat",
      chatWorkspaceNav = Some(buildWorkspaceNav(workspaceFolders, None, showNewChat = true)),
    )(
      div(cls := "rounded-lg border border-white/10 bg-slate-950/70 p-6 text-center")(
        h1(cls := "text-sm font-semibold text-white")("No chats yet"),
        p(cls := "mt-1 text-[11px] text-gray-400")("Use the left navigation to create a new chat."),
      )
    )

  def newConversation(
    workspaceFolders: List[ChatWorkspaceFolder],
    workspaces: List[(String, String)],
  ): String =
    Layout.page(
      "New Chat",
      "/chat/new",
      chatWorkspaceNav = Some(buildWorkspaceNav(workspaceFolders, None, showNewChat = true)),
    )(
      div(cls := "mx-auto flex min-h-[calc(100vh-9rem)] max-w-3xl items-center justify-center")(
        div(cls := "w-full space-y-4 rounded-xl border border-white/10 bg-slate-950/70 p-4")(
          div(cls := "text-center")(
            h1(cls := "text-2xl font-semibold text-white")("New chat"),
            p(cls := "mt-1 text-[11px] text-gray-400")("Pick a workspace and send your first message."),
          ),
          form(action := "/chat/new", method := "post", cls := "space-y-3")(
            label(cls := "block text-[11px] font-semibold uppercase tracking-wide text-gray-400")(
              "Workspace",
              select(
                name := "workspace_id",
                cls  := "mt-1 w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-[12px] text-gray-100",
              )(
                option(value := "chat")("Chat (no workspace)"),
                workspaces.map { (id, name) =>
                  option(value := id)(name)
                },
              ),
            ),
            label(cls := "block text-[11px] font-semibold uppercase tracking-wide text-gray-400")(
              "Message",
              textarea(
                name              := "content",
                required          := "required",
                rows              := 6,
                cls               := "mt-1 w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-[12px] text-gray-100 placeholder:text-gray-500 focus:border-indigo-400 focus:outline-none",
                placeholder       := "Ask anything...",
                attr("autofocus") := "autofocus",
              )(),
            ),
            div(cls := "flex items-center justify-end gap-2")(
              a(
                href := "/chat",
                cls  := "px-3 py-1.5 text-[11px] font-semibold text-gray-400 hover:text-white",
              )("Cancel"),
              button(
                `type` := "submit",
                cls    := "rounded bg-indigo-600 px-3 py-1.5 text-[11px] font-semibold text-white hover:bg-indigo-700",
              )("Start chat"),
            ),
          ),
        )
      )
    )

  def detail(
    conversation: ChatConversation,
    sessionMeta: Option[ConversationSessionMeta],
    runSessionMeta: Option[RunSessionUiMeta],
    workspaceFolders: List[ChatWorkspaceFolder] = Nil,
    detailContext: ChatDetailContext = ChatDetailContext.empty,
  ): String =
    val conversationId = sanitizeOptionalString(conversation.id).getOrElse("unknown")
    val description    = sanitizeOptionalString(conversation.description).flatMap(stripWorkspaceMarker)
    val runControlId   = s"run-session-controls-$conversationId"
    val issuesHref     =
      sanitizeOptionalString(conversation.runId)
        .map(runId => s"/issues?run_id=$runId")
        .getOrElse("/issues")

    Layout.page(
      s"Chat — ${conversation.title}",
      s"/chat/$conversationId",
      chatWorkspaceNav = Some(buildWorkspaceNav(workspaceFolders, Some(conversationId), showNewChat = true)),
    )(
      div(cls := "flex flex-col min-h-[calc(100vh-8rem)] gap-3 rounded-lg border border-white/10 bg-slate-950/70 p-3")(
        div(cls := "mb-1 flex items-center gap-2")(
          tag("ab-icon-button")(
            attr("icon")    := "M10.5 19.5 3 12m0 0 7.5-7.5M3 12h18",
            attr("tooltip") := "Back to chats",
            attr("href")    := "/chat",
            attr("size")    := "sm",
          )(),
          div(cls := "min-w-0 flex-1")(
            h1(cls := "truncate text-sm font-semibold text-white")(conversation.title),
            description.fold[Frag](frag())(text =>
              p(cls := "mt-0 text-[10px] text-gray-500 truncate")(text)
            ),
          ),
          div(cls := "flex items-center gap-0.5 flex-shrink-0")(
            tag("ab-icon-button")(
              attr("icon")    := "M21 21l-5.197-5.197m0 0A7.5 7.5 0 1 0 5.197 5.197a7.5 7.5 0 0 0 10.606 10.606Z",
              attr("tooltip") := "Search messages",
              attr("size")    := "sm",
              attr("onclick") := """(function(){window.dispatchEvent(new CustomEvent('ab-panel-open',{detail:{panelId:'context-panel',title:'Search Messages'}}));setTimeout(function(){var c=document.getElementById('panel-context-panel-content');if(c&&!c.querySelector('ab-message-search')){var el=document.createElement('ab-message-search');c.replaceChildren(el);}setTimeout(function(){var i=document.querySelector('ab-message-search input');if(i)i.focus();},50);},50);})()""",
            )(),
            tag("ab-icon-button")(
              attr("icon")    := "M9.813 15.904 9 18.75l-.813-2.846a4.5 4.5 0 0 0-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 0 0 3.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 0 0 3.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 0 0-3.09 3.09Z",
              attr("tooltip") := "Memory & Context",
              attr("size")    := "sm",
              attr("onclick") := """window.dispatchEvent(new CustomEvent('ab-panel-open', {detail:{panelId:'context-panel', title:'Memory & Context'}}))""",
            )(),
            tag("ab-icon-button")(
              attr("icon")    := "M19.5 14.25v-2.625a3.375 3.375 0 0 0-3.375-3.375h-1.5A1.125 1.125 0 0 1 13.5 7.125v-1.5a3.375 3.375 0 0 0-3.375-3.375H8.25m0 12.75h7.5m-7.5 3H12M10.5 2.25H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 0 0-9-9Z",
              attr("tooltip") := "Reports",
              attr("size")    := "sm",
              attr("onclick") := """window.dispatchEvent(new CustomEvent('ab-panel-open', {detail:{panelId:'context-panel', title:'Reports'}}))""",
            )(),
            tag("ab-icon-button")(
              attr("icon")    := "M17.25 6.75 22.5 12l-5.25 5.25m-10.5 0L1.5 12l5.25-5.25m7.5-3-4.5 16.5",
              attr("tooltip") := "Git changes",
              attr("size")    := "sm",
              attr("onclick") := """window.dispatchEvent(new CustomEvent('ab-panel-open', {detail:{panelId:'context-panel', title:'Git Changes'}}))""",
            )(),
            tag("ab-icon-button")(
              attr("icon")    := "M11.35 3.836c-.065.21-.1.433-.1.664 0 .414.336.75.75.75h4.5a.75.75 0 0 0 .75-.75 2.25 2.25 0 0 0-.1-.664m-5.8 0A2.251 2.251 0 0 1 13.5 2.25H15c1.012 0 1.867.668 2.15 1.586m-5.8 0c-.376.023-.75.05-1.124.08C9.095 4.01 8.25 4.973 8.25 6.108V8.25m8.9-4.414c.376.023.75.05 1.124.08 1.131.094 1.976 1.057 1.976 2.192V16.5A2.25 2.25 0 0 1 18 18.75h-2.25m-7.5-10.5H4.875c-.621 0-1.125.504-1.125 1.125v11.25c0 .621.504 1.125 1.125 1.125h9.75c.621 0 1.125-.504 1.125-1.125V18.75m-7.5-10.5h6.375c.621 0 1.125.504 1.125 1.125v9.375m-8.25-3 1.5 1.5 3-3.75",
              attr("tooltip") := "View issues",
              attr("href")    := issuesHref,
            )(),
          ),
        ),
        div(cls := "mb-2 flex flex-wrap items-center gap-1.5 text-[11px]")(
          statusDot(conversation.status),
          span(cls := "rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-gray-300")(
            s"${conversation.messages.length} msgs"
          ),
          runSessionMeta.fold[Frag](frag()) { meta =>
            frag(
              span(
                id  := s"run-mode-badge-$conversationId",
                cls := s"rounded-full border px-2 py-0.5 font-semibold ${runModeBadgeClass(meta.status)}",
              )(runModeLabel(meta.status)),
              span(
                id  := s"run-attached-count-$conversationId",
                cls := "rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-gray-300",
              )(
                span(cls := "mr-1 text-gray-400")(""),
                span(attr("data-role") := "count")(meta.attachedUsersCount.toString),
                " attached",
              ),
              span(
                id  := s"run-active-indicator-$conversationId",
                cls := s"rounded-full border border-emerald-400/40 bg-emerald-500/15 px-2 py-0.5 text-emerald-300 ${
                    if isRunActive(meta.status) then "" else "hidden"
                  }",
              )("● Live"),
            )
          },
        ),
        runSessionMeta.fold[Frag](frag())(meta => runBreadcrumb(meta, conversationId)),
        div(
          id      := s"token-gauge-$conversationId",
          cls     := "relative w-full cursor-pointer group overflow-visible h-[3px]",
          attr("onclick") := s"""window.dispatchEvent(new CustomEvent('ab-panel-open', {{detail:{{panelId:'context-panel', title:'Token Usage'}}}}))""",
          role    := "progressbar",
          attr("aria-label") := "Context window usage",
          attr("aria-valuenow") := "0",
          attr("aria-valuemax") := "100",
        )(
          div(cls := "absolute inset-0 rounded-full bg-white/5")(),
          div(
            id    := s"token-gauge-bar-$conversationId",
            cls   := "absolute inset-y-0 left-0 rounded-full bg-emerald-500 transition-all duration-500",
            style := "width:0%",
          )(),
          div(cls := "absolute inset-0 opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center pointer-events-none")(
            span(
              id  := s"token-gauge-label-$conversationId",
              cls := "text-[10px] text-gray-300 bg-slate-900/90 px-2 py-0.5 rounded-full pointer-events-none",
            )(""),
          ),
        ),
        div(cls := "mt-1 flex flex-1 min-h-0 flex-col gap-3")(
          div(
            cls := "relative flex-1 min-h-0 rounded border border-white/10 bg-black/20 overflow-hidden flex flex-col"
          )(
            tag("chat-message-stream")(
              id                      := s"messages-$conversationId",
              cls                     := "flex-1 min-h-0 overflow-y-auto p-3 space-y-3 block",
              attr("conversation-id") := conversationId,
              attr("ws-url")          := "/ws/console",
            )(
              raw(messagesFragment(conversation.messages, Some(conversationId)))
            ),
            button(
              id              := s"scroll-bottom-$conversationId",
              `type`          := "button",
              cls             := "hidden absolute bottom-3 right-3 rounded bg-indigo-600 hover:bg-indigo-700 text-white px-2 py-1 text-[11px] font-semibold",
              attr("onclick") := s"document.getElementById('messages-$conversationId')?.scrollToLatest?.()",
            )("Bottom"),
          ),
          inlineTimelineEvents(detailContext, runSessionMeta),
          activityIndicator(conversationId),
          runSessionMeta.fold[Frag](standardChatComposer(conversationId))(meta =>
            runInteractionComposer(
              conversationId = conversationId,
              runControlId = runControlId,
              meta = meta,
            )
          ),
        ),
        tag("ab-side-panel")(
          attr("panel-id") := "context-panel",
          attr("title")    := "Context",
          attr("width")    := "400px",
        )(),
      ),
      runSessionMeta.fold[Frag](frag())(_ => gitPanelStyles),
      JsResources.markedScript,
      if detailContext.graphReports.nonEmpty then JsResources.mermaidScript else frag(),
      tag("link")(
        attr("rel")  := "stylesheet",
        attr("href") := "https://cdn.jsdelivr.net/npm/highlight.js@11.11.1/styles/github-dark.min.css",
      ),
      JsResources.inlineModuleScript("/static/client/components/ab-side-panel.js"),
      JsResources.inlineModuleScript("/static/client/components/ab-message-search.js"),
      JsResources.inlineModuleScript("/static/client/components/ab-icon-button.js"),
      JsResources.inlineModuleScript("/static/client/components/ab-tool-waterfall.js"),
      JsResources.inlineModuleScript("/static/client/components/chat-message-stream.js"),
      runSessionMeta.fold[Frag](JsResources.inlineModuleScript("/static/client/components/message-composer.js"))(_ =>
        frag(
          JsResources.inlineModuleScript("/static/client/components/run-session-controls.js"),
          JsResources.inlineModuleScript("/static/client/components/git-panel.js"),
          JsResources.inlineModuleScript("/static/client/components/ab-git-summary.js"),
        )
      ),
      if detailContext.graphReports.nonEmpty then graphPanelScript(conversationId) else frag(),
    )

  private def buildWorkspaceNav(
    workspaceFolders: List[ChatWorkspaceFolder],
    currentConversationId: Option[String],
    showNewChat: Boolean,
  ): Layout.ChatWorkspaceNav =
    Layout.ChatWorkspaceNav(
      groups = workspaceFolders.map { folder =>
        val chats = folder.chats
          .sortBy(_.updatedAt)(Ordering[java.time.Instant].reverse)
          .take(80)
          .map { chat =>
            val conversationId = sanitizeOptionalString(chat.id).getOrElse("unknown")
            Layout.ChatNavItem(
              conversationId = conversationId,
              title = sanitizeString(chat.title).getOrElse("Untitled chat"),
              href = s"/chat/$conversationId",
              active = currentConversationId.contains(conversationId),
            )
          }
        Layout.ChatWorkspaceGroup(
          id = folder.id,
          label = folder.label,
          chats = chats,
          expanded = chats.exists(_.active) || folder.id == "chat",
        )
      },
      showNewChat = showNewChat,
    )

  /** Groups consecutive ToolCall entries together. Non-ToolCall entries (including ToolResult) are
    * left as individual Left values. A run of 2+ consecutive ToolCall entries becomes a single
    * Right value containing the list. A lone ToolCall stays as Left so it renders with the existing
    * individual card.
    */
  private def groupToolCalls(
    messages: List[ConversationEntry]
  ): List[Either[ConversationEntry, List[ConversationEntry]]] =
    messages.foldRight(List.empty[Either[ConversationEntry, List[ConversationEntry]]]) {
      case (msg, acc) if msg.messageType == MessageType.ToolCall =>
        acc match
          // The head of acc is already an accumulated group — extend it
          case Right(group) :: tail => Right(msg :: group) :: tail
          // Start a new group with just this message (will be promoted to Right if 2+)
          case _                    => Right(List(msg)) :: acc
      case (msg, acc) =>
        // Demote a solo ToolCall group (size == 1) back to a regular Left card
        val normalised = acc match
          case Right(List(single)) :: tail => Left(single) :: tail
          case _                           => acc
        Left(msg) :: normalised
    } match
      // Final pass: demote any trailing solo group
      case Right(List(single)) :: tail => Left(single) :: tail
      case result                      => result

  def messagesFragment(messages: List[ConversationEntry], conversationId: Option[String] = None): String =
    val grouped = groupToolCalls(messages)
    div(cls := "space-y-2 text-gray-100")(
      grouped.zipWithIndex.map { case (entry, idx) =>
        entry match
          case Right(toolCalls) =>
            val toolsJson = toolCalls.map { m =>
              val name     = extractToolSummary(m.content)
              val subtitle = m.content.linesIterator.take(3).mkString(" ").take(80).replace("\"", "'").replace("\n", " ")
              val input    = m.content.take(400).replace("\"", "'").replace("\n", "\\n")
              s"""{"name":"${name.replace("\"", "'")}","subtitle":"$subtitle","input":"$input"}"""
            }.mkString("[", ",", "]")
            div(cls := "flex justify-start my-0.5")(
              tag("ab-tool-waterfall")(
                attr("tools") := toolsJson,
              )()
            )
          case Left(msg) =>
            val prevSender =
              if idx > 0 then
                grouped(idx - 1) match
                  case Left(prev)            => Some(prev.senderType)
                  case Right(prevGroup)      => prevGroup.lastOption.map(_.senderType)
              else None
            messageCard(msg, prevSender, conversationId)
      }
    ).render

  private def messageCard(message: ConversationEntry, prevSender: Option[SenderType] = None, conversationId: Option[String] = None): Frag =
    message.messageType match
      case MessageType.ToolCall   => toolCallCard(message)
      case MessageType.ToolResult => toolResultCard(message)
      case _                      =>
        message.senderType match
          case SenderType.System =>
            div(cls := "flex justify-center", attr("data-sender") := "system")(
              div(
                cls := "rounded-full px-3 py-0.5 text-[10px] bg-amber-500/10 text-amber-300 border border-amber-500/20"
              )(
                message.content
              )
            )
          case sender            =>
            val isUser             = sender == SenderType.User
            val showSenderLabel    = prevSender != Some(sender)
            val (containerClasses, bubbleClasses) =
              if isUser then
                (
                  "flex justify-end items-center group",
                  "max-w-[85%] lg:max-w-[72%] rounded-2xl rounded-br-md border border-indigo-400/25 bg-indigo-500/15 px-4 py-3 text-gray-100",
                )
              else
                (
                  "flex justify-start",
                  "max-w-[85%] lg:max-w-[72%] border-l-2 border-indigo-400/50 pl-4 py-2",
                )

            val inputSelector = conversationId
              .map(cid => s"document.getElementById('chat-input-$cid')")
              .getOrElse("document.querySelector('[id^=\"chat-input-\"]')")

            div(cls := containerClasses, attr("data-sender") := (if isUser then "user" else "assistant"))(
              if isUser then
                button(
                  `type`          := "button",
                  cls             := "opacity-0 group-hover:opacity-100 transition-opacity mr-1.5 flex-shrink-0 p-1 rounded hover:bg-white/10",
                  attr("title")   := "Edit and re-send",
                  attr("onclick") := s"""(function(btn) {
  var t = $inputSelector;
  if (!t) return;
  var bubble = btn.closest('[data-sender="user"]').querySelector('pre, p');
  if (!bubble) return;
  t.value = bubble.textContent.trim();
  t.dispatchEvent(new Event('input', {bubbles:true}));
  t.scrollIntoView({behavior:'smooth', block:'end'});
  t.focus();
})(this)""",
                )(
                  raw("""<svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" class="text-gray-500 group-hover:text-indigo-400 transition-colors"><path stroke-linecap="round" stroke-linejoin="round" d="m16.862 4.487 1.687-1.688a1.875 1.875 0 1 1 2.652 2.652L10.582 16.07a4.5 4.5 0 0 1-1.897 1.13L6 18l.8-2.685a4.5 4.5 0 0 1 1.13-1.897l8.932-8.931Zm0 0L19.5 7.125M18 14v4.75A2.25 2.25 0 0 1 15.75 21H5.25A2.25 2.25 0 0 1 3 18.75V8.25A2.25 2.25 0 0 1 5.25 6H10"/></svg>""")
                )
              else frag(),
              div(cls := bubbleClasses)(
                if showSenderLabel then
                  div(cls := s"text-xs font-semibold mb-2 ${if isUser then "text-indigo-300" else "text-slate-400"}")(
                    message.sender
                  )
                else
                  frag(),
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

  private def extractToolSummary(content: String): String =
    val nameLine = content.linesIterator.find(_.trim.startsWith("name:"))
    nameLine.map(_.trim.stripPrefix("name:").trim).filter(_.nonEmpty)
      .getOrElse(content.take(60).replaceAll("\n", " "))

  private def toolCallCard(message: ConversationEntry): Frag =
    val summary = extractToolSummary(message.content)
    div(cls := "flex justify-start my-0.5")(
      tag("ab-timeline-event")(
        attr("event-type") := "tool",
        attr("title")      := summary,
        attr("expandable") := "true",
        attr("subtitle")   := message.content.take(500),
      )()
    )

  private def toolResultCard(message: ConversationEntry): Frag =
    val firstLine = message.content.linesIterator.nextOption().getOrElse("").take(120)
    div(cls := "flex justify-start my-0.5")(
      tag("ab-timeline-event")(
        attr("event-type") := "tool",
        attr("title")      := s"\u2192 $firstLine",
        attr("expandable") := "true",
        attr("subtitle")   := message.content.take(500),
      )()
    )

  private def runBreadcrumb(meta: RunSessionUiMeta, conversationId: String): Frag =
    div(cls := "flex items-center gap-2 text-[11px] text-gray-400 mb-1 px-1")(
      span(cls := "text-gray-500")("Run:"),
      span(
        cls := s"font-mono font-semibold ${runModeBadgeClass(meta.status).replace("rounded-full border", "").trim}"
      )(
        runModeLabel(meta.status)
      ),
      if meta.breadcrumb.nonEmpty then
        frag(
          meta.breadcrumb.take(3).zipWithIndex.map {
            case (item, idx) =>
              frag(
                if idx > 0 then span(cls := "text-gray-600")("→") else frag(),
                a(
                  href := s"/chat/${item.conversationId}",
                  cls  := (
                    if item.runId == meta.runId then
                      "font-mono text-indigo-300 hover:text-indigo-200"
                    else "font-mono text-gray-400 hover:text-gray-200"
                  ),
                )(item.runId.take(8)),
              )
          }*
        )
      else
        span(cls := "font-mono text-gray-500")(meta.runId.take(8)),
      span(cls := "ml-auto")(
        tag("ab-icon-button")(
          attr("icon")    := "M3.75 9h16.5m-16.5 6.75h16.5",
          attr("tooltip") := "Run chain details",
          attr("size")    := "sm",
          attr("onclick") := """window.dispatchEvent(new CustomEvent('ab-panel-open', {detail:{panelId:'context-panel', title:'Run Chain'}}))""",
        )()
      ),
    )

  private def inlineTimelineEvents(
    detailContext: ChatDetailContext,
    runSessionMeta: Option[RunSessionUiMeta],
  ): Frag =
    val hasContent = detailContext.proofOfWork.isDefined ||
      detailContext.reports.nonEmpty ||
      detailContext.graphReports.nonEmpty ||
      runSessionMeta.isDefined
    if !hasContent then frag()
    else
      div(cls := "space-y-1")(
        runSessionMeta.map { meta =>
          val basePath = s"/api/workspaces/${meta.workspaceId}/runs/${meta.runId}/git"
          tag("ab-git-summary")(
            attr("status-url")   := s"$basePath/status",
            attr("diff-url")     := s"$basePath/diff",
            attr("panel-target") := "context-panel",
          )()
        }.getOrElse(frag()),
        if detailContext.reports.nonEmpty then
          tag("ab-timeline-event")(
            attr("event-type") := "tool",
            attr("title")      := s"Run Reports (${detailContext.reports.size})",
            attr("subtitle")   := "Click to view reports",
            attr("expandable") := "true",
            attr("onclick")    := """window.dispatchEvent(new CustomEvent('ab-panel-open', {detail:{panelId:'context-panel', title:'Reports'}}))""",
          )()
        else frag(),
        if detailContext.graphReports.nonEmpty then
          tag("ab-timeline-event")(
            attr("event-type") := "tool",
            attr("title")      := s"Run Graphs (${detailContext.graphReports.size})",
            attr("subtitle")   := "Click to view graphs",
            attr("expandable") := "true",
            attr("onclick")    := """window.dispatchEvent(new CustomEvent('ab-panel-open', {detail:{panelId:'context-panel', title:'Graphs'}}))""",
          )()
        else frag(),
        detailContext.proofOfWork.map { _ =>
          tag("ab-timeline-event")(
            attr("event-type") := "tool",
            attr("title")      := "Proof of Work",
            attr("subtitle")   := "Click to view proof of work report",
            attr("expandable") := "true",
            attr("onclick")    := """window.dispatchEvent(new CustomEvent('ab-panel-open', {detail:{panelId:'context-panel', title:'Proof of Work'}}))""",
          )()
        }.getOrElse(frag()),
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

  private def reportsPanel(reports: List[TaskReportRow]): Frag =
    if reports.isEmpty then frag()
    else
      tag("details")(cls := "rounded-lg border border-white/10 bg-slate-900/60 p-3")(
        tag("summary")(cls := "cursor-pointer select-none text-sm font-semibold text-slate-200")(
          s"Run Reports (${reports.size})"
        ),
        div(cls := "mt-3 space-y-3")(
          reports.sortBy(_.createdAt).reverse.take(20).map { report =>
            tag("details")(cls := "rounded-md border border-white/10 bg-black/20 p-3")(
              tag("summary")(cls := "cursor-pointer select-none text-xs font-semibold text-slate-200")(
                s"${report.stepName} • ${report.reportType} • ${report.createdAt.toString.take(19).replace("T", " ")}"
              ),
              div(cls := "mt-2 text-sm text-slate-100")(
                if report.reportType.trim.equalsIgnoreCase("markdown") then
                  IssuesView.markdownFragment(report.content)
                else pre(cls := "whitespace-pre-wrap break-words text-xs text-slate-200")(report.content)
              ),
            )
          }
        ),
      )

  private def graphPanel(graphReports: List[TaskReportRow], conversationId: String): Frag =
    if graphReports.isEmpty then frag()
    else
      tag("details")(cls := "rounded-lg border border-white/10 bg-slate-900/60 p-3")(
        tag("summary")(cls := "cursor-pointer select-none text-sm font-semibold text-slate-200")(
          s"Run Graphs (${graphReports.size})"
        ),
        div(cls := "mt-3 space-y-3")(
          graphReports.sortBy(_.createdAt).reverse.take(10).zipWithIndex.map {
            case (report, idx) =>
              div(cls := "rounded-md border border-white/10 bg-black/20 p-3")(
                p(cls := "text-xs font-semibold text-slate-200")(
                  s"${report.stepName} • ${report.createdAt.toString.take(19).replace("T", " ")}"
                ),
                div(
                  cls                               := "mt-2 overflow-auto rounded border border-white/10 bg-slate-950/70 p-2",
                  attr("data-chat-graph-container") := s"$conversationId-$idx",
                )(
                  div(
                    cls                         := "mermaid text-slate-200",
                    attr("data-chat-graph-src") := report.content,
                  )(report.content)
                ),
              )
          }
        ),
      )

  private def memorySidebar(conversationId: String, memorySessionId: Option[String]): Frag =
    frag()

  private def graphPanelScript(conversationId: String): Frag =
    script(
      raw(
        s"""document.addEventListener('DOMContentLoaded', function () {
           |  if (!window.mermaid) return;
           |  window.mermaid.initialize({ startOnLoad: false, securityLevel: 'loose' });
           |  var nodes = Array.from(document.querySelectorAll('[data-chat-graph-container^="${conversationId}-"] .mermaid'));
           |  if (nodes.length === 0) return;
           |  window.mermaid.run({ nodes: nodes }).catch(function () {
           |    nodes.forEach(function (node) {
           |      var source = node.getAttribute('data-chat-graph-src') || node.textContent || '';
           |      node.textContent = source;
           |    });
           |  });
           |});
           |""".stripMargin
      )
    )

  private def activityIndicator(conversationId: String): Frag =
    div(
      id  := s"activity-indicator-$conversationId",
      cls := "hidden px-4 py-2 flex items-center gap-2 text-[11px] text-gray-400 border-t border-white/5 bg-slate-950/70 transition-all",
    )(
      span(id := s"activity-icon-$conversationId", cls := "text-indigo-400")("\u2699"),
      span(id := s"activity-text-$conversationId", cls := "truncate")("Thinking..."),
      span(cls := "ml-auto animate-pulse text-gray-600")("\u25cf"),
    )

  private def standardChatComposer(conversationId: String): Frag =
    div(
      cls := "sticky bottom-0 mt-2 rounded-xl bg-slate-900/98 ring-1 ring-white/10 backdrop-blur"
    )(
      form(
        method                        := "post",
        action                        := s"/chat/$conversationId/messages",
        attr("hx-post")               := s"/chat/$conversationId/messages",
        attr("hx-target")             := s"#messages-$conversationId",
        attr("hx-swap")               := "innerHTML",
        attr("hx-disabled-elt")       := "button[type='submit']",
        attr("hx-on::before-request") := s"""document.getElementById('messages-$conversationId')?.markPending?.();""",
        attr("hx-on::after-request")  := """this.reset();""",
      )(
        input(`type` := "hidden", name := "fragment", value := "true"),
        // Textarea
        div(
          id                           := "chat-composer",
          cls                          := "px-4 pt-3 pb-2",
          attr("data-conversation-id") := conversationId,
          attr("data-agents-endpoint") := "/api/agents",
        )(
          div(cls := "relative")(
            div(attr("data-role") := "write-pane")(
              textarea(
                id                   := s"chat-input-$conversationId",
                name                 := "content",
                placeholder          := "Message...",
                rows                 := 1,
                cls                  := "w-full resize-none bg-transparent border-none text-white placeholder:text-gray-500 focus:outline-none text-sm leading-6 min-h-[1.5rem] max-h-48 overflow-y-auto",
                required,
                attr("autocomplete") := "off",
                attr("spellcheck")   := "false",
                attr("data-role")    := "auto-grow",
              )()
            ),
            div(
              attr("data-role") := "preview-pane",
              cls               := "hidden min-h-[1.5rem] max-h-48 text-sm leading-6 text-gray-100 overflow-auto",
            )(),
            div(
              attr("data-role") := "mentions",
              cls               := "hidden absolute left-0 right-0 bottom-full mb-1 max-h-48 overflow-auto rounded-md border border-white/15 bg-slate-900/95 shadow-lg z-20",
            )(),
          ),
        ),
        // Bottom toolbar
        div(cls := "flex items-center justify-between px-3 pb-2 gap-2")(
          // Left: icon tools
          div(cls := "flex items-center gap-0.5")(
            tag("ab-icon-button")(
              attr("icon")      := "M6 18L18 6",
              attr("tooltip")   := "Slash commands (/)",
              attr("size")      := "sm",
              attr("data-role") := "slash-command",
            )(),
            tag("ab-icon-button")(
              attr("icon")      := "M17.25 6.75 22.5 12l-5.25 5.25m-10.5 0L1.5 12l5.25-5.25m7.5-3-4.5 16.5",
              attr("tooltip")   := "Insert code block (Ctrl+K)",
              attr("size")      := "sm",
              attr("data-role") := "insert-code",
            )(),
            tag("ab-icon-button")(
              attr("icon")      := "M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM12 14a7 7 0 0 0-7 7h14a7 7 0 0 0-7-7Z",
              attr("tooltip")   := "Mention agent (@)",
              attr("size")      := "sm",
              attr("data-role") := "mention-trigger",
            )(),
            tag("ab-icon-button")(
              attr("icon")      := "M2.036 12.322a1.012 1.012 0 0 1 0-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178Z M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z",
              attr("tooltip")   := "Preview markdown (Ctrl+P)",
              attr("size")      := "sm",
              attr("data-role") := "mode-toggle",
            )(),
          ),
          // Right: model selector + send + abort
          div(cls := "flex items-center gap-1.5")(
            tag("ab-badge-select")(
              attr("value")   := "claude-opus-4-6",
              attr("options") := """[{"value":"claude-opus-4-6","label":"Opus 4.6"},{"value":"claude-sonnet-4-6","label":"Sonnet 4.6"},{"value":"claude-haiku-4-5","label":"Haiku 4.5"}]""",
              attr("label")   := "Model",
              id              := "model-selector",
            )(),
            // Abort button (hidden by default, shown when streaming)
            button(
              id              := s"abort-btn-$conversationId",
              `type`          := "button",
              cls             := "hidden rounded-md bg-rose-600/20 border border-rose-500/30 hover:bg-rose-600/30 text-rose-300 px-2 py-1 text-[11px] font-semibold transition-colors",
              attr("onclick") := s"document.getElementById('messages-$conversationId').abort()",
            )("Stop"),
            // Send button (icon, turns indigo when text present)
            button(
              `type`        := "submit",
              id            := s"send-btn-$conversationId",
              cls           := "rounded-md bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 text-white p-1.5 transition-colors",
              attr("title") := "Send message (Ctrl+Enter)",
            )(
              raw("""<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 19V5M5 12l7-7 7 7"/></svg>""")
            ),
          ),
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

  private def runGitPanel(meta: RunSessionUiMeta, conversationId: String): Frag =
    val basePath = s"/api/workspaces/${meta.workspaceId}/runs/${meta.runId}/git"
    tag("details")(
      id                           := s"git-panel-$conversationId",
      cls                          := "mt-3 rounded-lg bg-white/5 ring-1 ring-white/10 overflow-hidden",
      attr("open")                 := "open",
      attr("data-role")            := "git-panel",
      attr("data-workspace-id")    := meta.workspaceId,
      attr("data-run-id")          := meta.runId,
      attr("data-conversation-id") := conversationId,
      attr("data-run-status")      := runModeLabel(meta.status),
      attr("data-status-endpoint") := s"$basePath/status",
      attr("data-diff-endpoint")   := s"$basePath/diff",
      attr("data-log-endpoint")    := s"$basePath/log",
      attr("data-branch-endpoint") := s"$basePath/branch",
      attr("data-apply-endpoint")  := s"/api/workspaces/${meta.workspaceId}/runs/${meta.runId}/apply",
      attr("data-topic")           := s"runs:${meta.runId}:git",
    )(
      tag("summary")(
        cls := "cursor-pointer select-none px-4 py-3 text-sm font-semibold text-white bg-white/5 border-b border-white/10"
      )(
        "Changes"
      ),
      div(cls := "grid gap-3 p-3 lg:grid-cols-3")(
        div(cls := "lg:col-span-2 rounded-lg border border-white/10 bg-black/20 p-3 space-y-3")(
          h3(cls := "text-sm font-semibold text-gray-100")("Files Changed"),
          p(
            cls               := "text-xs text-gray-400",
            attr("data-role") := "summary",
          )("Loading changes..."),
          div(cls := "space-y-3", attr("data-role") := "files-groups")(),
          div(
            cls               := "hidden rounded-lg border border-white/10 bg-black/30 p-3",
            attr("data-role") := "diff-viewer",
          )(
            div(cls := "mb-2 flex items-center justify-between gap-2")(
              h4(cls := "text-xs font-semibold text-gray-200", attr("data-role") := "diff-title")("Diff"),
              button(
                `type`          := "button",
                cls             := "rounded bg-white/10 px-2 py-1 text-[11px] text-gray-200 hover:bg-white/20",
                attr("onclick") := "this.closest('[data-role=\"diff-viewer\"]').classList.add('hidden')",
              )("Close"),
            ),
            pre(
              cls               := "git-diff-lines text-xs overflow-auto rounded bg-black/40 p-2",
              attr("data-role") := "diff-content",
            )(),
          ),
        ),
        div(cls := "space-y-3")(
          div(cls := "rounded-lg border border-white/10 bg-black/20 p-3 space-y-2")(
            h3(cls := "text-sm font-semibold text-gray-100")("Branch Info"),
            div(cls := "text-xs text-gray-300", attr("data-role") := "branch-current")("Loading branch..."),
            div(cls := "text-xs text-gray-400", attr("data-role") := "ahead-behind")(),
            div(cls := "pt-1")(
              button(
                `type`            := "button",
                cls               := "rounded bg-emerald-600 px-2 py-1 text-[11px] font-semibold text-white hover:bg-emerald-500",
                attr("data-role") := "apply-button",
              )("Apply to repo")
            ),
            div(cls := "text-xs text-gray-400", attr("data-role") := "apply-feedback")(),
            div(
              cls               := "text-xs text-gray-400",
              attr("data-role") := "run-status",
            )(s"Run status: ${runModeLabel(meta.status)}"),
          ),
          div(cls := "rounded-lg border border-white/10 bg-black/20 p-3 space-y-2")(
            h3(cls := "text-sm font-semibold text-gray-100")("Commit Log"),
            div(cls := "space-y-2", attr("data-role") := "commit-log")(
              p(cls := "text-xs text-gray-400")("Loading commits...")
            ),
          ),
        ),
      ),
    )

  private def gitPanelStyles: Frag =
    tag("style")(
      raw("""
      .git-diff-lines {
        font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
        color: #e5e7eb;
      }
      .git-diff-line {
        display: grid;
        grid-template-columns: 3.2rem 3.2rem 1fr;
        gap: 0.65rem;
        padding: 0.17rem 0.45rem;
        border-radius: 0.25rem;
        white-space: pre;
        color: #e5e7eb;
      }
      .git-diff-line .line-no {
        color: #9ca3af;
        text-align: right;
        user-select: none;
      }
      .git-diff-line .line-no.new {
        color: #93c5fd;
      }
      .git-diff-line .code {
        color: #e5e7eb;
      }
      .git-diff-meta {
        background: rgba(71, 85, 105, 0.25);
      }
      .git-diff-meta .code {
        color: #cbd5e1;
      }
      .git-diff-hunk {
        background: rgba(79, 70, 229, 0.22);
      }
      .git-diff-hunk .code {
        color: #c7d2fe;
      }
      .git-diff-added {
        background: rgba(16, 185, 129, 0.19);
      }
      .git-diff-added .code {
        color: #bbf7d0;
      }
      .git-diff-deleted {
        background: rgba(244, 63, 94, 0.22);
      }
      .git-diff-deleted .code {
        color: #fecdd3;
      }
      .git-diff-context {
        background: rgba(15, 23, 42, 0.45);
      }
      .git-file-row.flash {
        animation: git-flash 1.2s ease-out;
      }
      @keyframes git-flash {
        0% { background: rgba(99, 102, 241, 0.35); }
        100% { background: transparent; }
      }
      """)
    )

  private def statusDot(status: String): Frag =
    val (dotColor, label) = status.toLowerCase match
      case s if s.contains("running") => ("text-blue-400", "Running")
      case "completed"                => ("text-emerald-400", "Completed")
      case "failed"                   => ("text-rose-400", "Failed")
      case "cancelled"                => ("text-orange-400", "Cancelled")
      case "pending"                  => ("text-amber-400", "Pending")
      case _                          => ("text-gray-400", status.capitalize)
    span(cls := "inline-flex items-center gap-1 rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-gray-300")(
      span(cls := s"text-[10px] $dotColor")("●"),
      label,
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

  private def sanitizeString(value: String): Option[String] =
    Option(value).map(_.trim).filter(_.nonEmpty)

  private def sanitizeOptionalString(value: Option[String]): Option[String] =
    try
      value match
        case Some(v) => sanitizeString(v)
        case _       => None
    catch
      case _: Throwable => None

  private def stripWorkspaceMarker(value: String): Option[String] =
    val Prefix = "workspace:"
    if value.startsWith(Prefix) then None
    else Some(value)

  private def formatTimestamp(instant: java.time.Instant): String =
    instant.toString.take(19).replace("T", " ")
