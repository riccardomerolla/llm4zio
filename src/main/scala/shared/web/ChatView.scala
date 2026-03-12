package shared.web

import java.net.URLEncoder

import conversation.entity.api.{ ChatConversation, ConversationEntry, ConversationSessionMeta, MessageType, SenderType }
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
      div(cls := "chat-detail-shell relative flex flex-col gap-2 overflow-hidden")(
        div(cls := "mb-1 flex items-center gap-2")(
          tag("ab-icon-button")(
            attr("icon")    := "M10.5 19.5 3 12m0 0 7.5-7.5M3 12h18",
            attr("tooltip") := "Back to Home",
            attr("href")    := "/",
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
        div(cls := "mb-1 flex flex-wrap items-center gap-2 text-[11px] text-slate-300")(
          statusDot(conversation.status),
          span(cls := "inline-flex items-center rounded-full bg-white/5 px-2.5 py-0.5 text-slate-300")(
            s"${conversation.messages.length} msgs"
          ),
          runSessionMeta.fold[Frag](frag()) { meta =>
            frag(
              span(
                id  := s"run-mode-badge-$conversationId",
                cls := s"rounded-full px-2.5 py-0.5 font-semibold ${runModeBadgeClass(meta.status)}",
              )(runModeLabel(meta.status)),
              span(
                id  := s"run-attached-count-$conversationId",
                cls := "rounded-full bg-white/5 px-2.5 py-0.5 text-slate-300",
              )(
                span(cls := "mr-1 text-gray-400")(""),
                span(attr("data-role") := "count")(meta.attachedUsersCount.toString),
                " attached",
              ),
              span(
                id  := s"run-active-indicator-$conversationId",
                cls := s"rounded-full bg-emerald-500/15 px-2.5 py-0.5 text-emerald-300 ${
                    if isRunActive(meta.status) then "" else "hidden"
                  }",
              )("● Live"),
            )
          },
        ),
        runSessionMeta.fold[Frag](frag())(meta => runBreadcrumb(meta)),
        div(
          id                    := s"token-gauge-$conversationId",
          cls                   := "relative w-full cursor-pointer group overflow-visible h-[2px]",
          attr("onclick")       := """window.dispatchEvent(new CustomEvent('ab-panel-open', {{detail:{{panelId:'context-panel', title:'Token Usage'}}}}))""",
          role                  := "progressbar",
          attr("aria-label")    := "Context window usage",
          attr("aria-valuenow") := "0",
          attr("aria-valuemax") := "100",
        )(
          div(cls := "absolute inset-0 rounded-full bg-white/10")(),
          div(
            id    := s"token-gauge-bar-$conversationId",
            cls   := "absolute inset-y-0 left-0 rounded-full bg-emerald-500 transition-all duration-500",
            style := "width:0%",
          )(),
          div(
            cls := "absolute inset-0 opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center pointer-events-none"
          )(
            span(
              id  := s"token-gauge-label-$conversationId",
              cls := "text-[10px] text-gray-300 bg-slate-900/90 px-2 py-0.5 rounded-full pointer-events-none",
            )("")
          ),
        ),
        div(cls := "mt-1 flex flex-1 min-h-0 overflow-hidden")(
          div(cls := "flex-1 min-w-0 flex flex-col gap-3 min-h-0 overflow-hidden")(
            div(
              cls := "relative flex-1 min-h-0 overflow-hidden rounded-2xl bg-slate-950/55 ring-1 ring-white/5 flex flex-col"
            )(
              tag("chat-message-stream")(
                id                      := s"messages-$conversationId",
                cls                     := "flex-1 min-h-0 overflow-y-auto p-4 space-y-3 block",
                attr("conversation-id") := conversationId,
                attr("ws-url")          := "/ws/console",
              )(
                raw(messagesFragment(conversation.messages, Some(conversationId)))
              ),
              button(
                id              := s"scroll-bottom-$conversationId",
                `type`          := "button",
                cls             := "hidden absolute bottom-3 right-3 rounded-full bg-indigo-600/90 hover:bg-indigo-500 text-white px-2.5 py-1 text-[11px] font-semibold",
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
            cls              := "chat-context-dock",
            attr("panel-id") := "context-panel",
            attr("title")    := "Context",
            attr("width")    := "25rem",
          )(),
        ),
      ),
      chatDetailStyles,
      runSessionMeta.fold[Frag](frag())(_ => gitPanelStyles),
      JsResources.markedScript,
      markdownRenderScript,
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
      runSessionMeta.fold[Frag](frag())(meta => gitPanelHtml(meta, conversationId)),
      runSessionMeta.fold[Frag](frag())(_ => gitPanelActivateScript(conversationId)),
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
              messageCount = chat.messages.length,
              createdAt = chat.updatedAt,
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

  /** Groups consecutive ToolCall entries together. Non-ToolCall entries (including ToolResult) are left as individual
    * Left values. A run of 2+ consecutive ToolCall entries becomes a single Right value containing the list. A lone
    * ToolCall stays as Left so it renders with the existing individual card.
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
      case (msg, acc)                                            =>
        // Demote a solo ToolCall group (size == 1) back to a regular Left card
        val normalised = acc match
          case Right(List(single)) :: tail => Left(single) :: tail
          case _                           => acc
        Left(msg) :: normalised
    } match
      // Final pass: demote any trailing solo group
      case Right(List(single)) :: tail => Left(single) :: tail
      case result                      => result

  /** Merges consecutive assistant Text entries that form a split fenced code block.
    *
    * Some LLM streaming backends persist code blocks as individual lines: Entry("```rust"), Entry("fn main() {"),
    * Entry("}"), Entry("```") This pass stitches them back into one entry with the full fenced block content so the
    * markdown renderer can produce a proper code block.
    */
  private def isAgentTextMessage(entry: ConversationEntry): Boolean =
    entry.messageType != MessageType.ToolCall && entry.messageType != MessageType.ToolResult

  private def isFenceOpen(content: String): Boolean =
    val t = content.trim
    t.startsWith("```") && !t.drop(3).contains("`") && !t.drop(3).contains("\n")

  private def collectFenceBody(
    senderType: SenderType,
    messages: List[ConversationEntry],
  ): (List[String], List[ConversationEntry]) =
    messages match
      case head :: tail
           if head.senderType == senderType
           && isAgentTextMessage(head)
           && head.content.trim == "```" =>
        (Nil, tail) // found closing fence
      case head :: tail
           if head.senderType == senderType
           && isAgentTextMessage(head)
           && !isFenceOpen(head.content) =>
        val (rest, after) = collectFenceBody(senderType, tail)
        (head.content :: rest, after)
      case _ => (Nil, messages) // bail — unexpected entry

  private def mergeCodeBlockFragments(messages: List[ConversationEntry]): List[ConversationEntry] =
    messages match
      case Nil          => Nil
      case head :: tail
           if isAgentTextMessage(head)
           && head.senderType != SenderType.User
           && isFenceOpen(head.content) =>
        val lang              = head.content.trim.drop(3).trim
        val (codeLines, rest) = collectFenceBody(head.senderType, tail)
        val mergedContent     = s"```${lang}\n${codeLines.mkString("\n")}\n```"
        head.copy(content = mergedContent, messageType = MessageType.Text) :: mergeCodeBlockFragments(rest)
      case head :: tail => head :: mergeCodeBlockFragments(tail)

  def messagesFragment(messages: List[ConversationEntry], conversationId: Option[String] = None): String =
    val grouped = groupToolCalls(mergeCodeBlockFragments(messages))
    div(cls := "space-y-2 text-gray-100")(
      grouped.zipWithIndex.map {
        case (entry, idx) =>
          entry match
            case Right(toolCalls) =>
              val toolsJson = toolCalls.map { m =>
                val name     = extractToolSummary(m.content)
                val subtitle =
                  m.content.linesIterator.take(3).mkString(" ").take(80).replace("\"", "'").replace("\n", " ")
                val input    = m.content.take(400).replace("\"", "'").replace("\n", "\\n")
                s"""{"name":"${name.replace("\"", "'")}","subtitle":"$subtitle","input":"$input"}"""
              }.mkString("[", ",", "]")
              div(cls := "flex justify-start my-0.5")(
                tag("ab-tool-waterfall")(
                  attr("tools") := toolsJson
                )()
              )
            case Left(msg)        =>
              val prevSender =
                if idx > 0 then
                  grouped(idx - 1) match
                    case Left(prev)       => Some(prev.senderType)
                    case Right(prevGroup) => prevGroup.lastOption.map(_.senderType)
                else None
              messageCard(msg, prevSender, conversationId)
      }
    ).render

  private def messageCard(
    message: ConversationEntry,
    prevSender: Option[SenderType] = None,
    conversationId: Option[String] = None,
  ): Frag =
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
            val isUser                            = sender == SenderType.User
            val showSenderLabel                   = prevSender != Some(sender)
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
                  raw(
                    """<svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" class="text-gray-500 group-hover:text-indigo-400 transition-colors"><path stroke-linecap="round" stroke-linejoin="round" d="m16.862 4.487 1.687-1.688a1.875 1.875 0 1 1 2.652 2.652L10.582 16.07a4.5 4.5 0 0 1-1.897 1.13L6 18l.8-2.685a4.5 4.5 0 0 1 1.13-1.897l8.932-8.931Zm0 0L19.5 7.125M18 14v4.75A2.25 2.25 0 0 1 15.75 21H5.25A2.25 2.25 0 0 1 3 18.75V8.25A2.25 2.25 0 0 1 5.25 6H10"/></svg>"""
                  )
                )
              else frag(),
              div(cls := bubbleClasses)(
                if showSenderLabel then
                  div(cls := s"text-xs font-semibold mb-2 ${if isUser then "text-indigo-300" else "text-slate-400"}")(
                    message.sender
                  )
                else
                  frag()
                ,
                if !isUser && looksLikeMarkdown(message.content) then
                  div(
                    cls                 := "md-chat text-sm leading-6 text-gray-50 [&_p]:my-2 [&_p]:leading-7 [&_ul]:my-2 [&_ul]:list-disc [&_ul]:pl-5 [&_ol]:my-2 [&_ol]:list-decimal [&_ol]:pl-5 [&_li]:my-0.5 [&_h1]:mt-4 [&_h1]:mb-1 [&_h1]:text-xl [&_h1]:font-semibold [&_h2]:mt-3 [&_h2]:mb-1 [&_h2]:text-lg [&_h2]:font-semibold [&_h3]:mt-2 [&_h3]:mb-1 [&_h3]:font-medium [&_a]:text-indigo-300 [&_a]:underline [&_a]:underline-offset-2 [&_blockquote]:my-2 [&_blockquote]:border-l-4 [&_blockquote]:border-indigo-400/40 [&_blockquote]:pl-3 [&_blockquote]:text-slate-300 [&_table]:my-3 [&_table]:w-full [&_table]:text-sm [&_th]:text-left [&_th]:font-semibold [&_th]:pb-1 [&_td]:py-1 [&_td]:pr-4 [&_hr]:my-3 [&_hr]:border-white/20",
                    attr("data-raw-md") := message.content,
                  )()
                else
                  pre(
                    cls := "whitespace-pre-wrap break-words text-sm leading-6 text-gray-50 font-sans m-0"
                  )(
                    message.content
                  ),
              ),
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

  private def runBreadcrumb(meta: RunSessionUiMeta): Frag =
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
        span(cls := "font-mono text-gray-500")(meta.runId.take(8))
      ,
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
      div(cls := "space-y-1 rounded-xl bg-slate-950/35 px-2 py-2 ring-1 ring-white/5")(
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

  private val markdownRenderScript: Frag =
    script(raw("""(function () {
  var _markedConfigured = false;
  window.renderMarkdownEls = function () {
    document.querySelectorAll('[data-raw-md]:not([data-md-done])').forEach(function (el) {
      el.setAttribute('data-md-done', '1');
      var md = el.getAttribute('data-raw-md') || '';
      try {
        el.innerHTML = window.marked ? window.marked.parse(md) : '<pre>' + md.replace(/&/g,'&amp;').replace(/</g,'&lt;') + '</pre>';
      } catch (e) { el.textContent = md; }
    });
  };
  function setupMarked() {
    if (!window.marked) { setTimeout(setupMarked, 30); return; }
    if (!_markedConfigured) {
      _markedConfigured = true;
      try {
        window.marked.use({ renderer: {
          code: function (token) {
            var text = typeof token === 'object' ? (token.text || '') : String(token || '');
            var lang = (typeof token === 'object' ? (token.lang || '') : '').trim();
            var hdr = lang ? '<div class="border-b border-white/15 px-3 py-1 text-xs uppercase tracking-wide text-slate-400">' + lang + '</div>' : '';
            return '<div class="my-4 rounded-lg border border-white/20 bg-slate-800 p-0">' + hdr +
              '<pre class="overflow-auto px-3 py-3 text-sm leading-6 text-slate-100 m-0 whitespace-pre"><code>' + text + '</code></pre></div>';
          },
          codespan: function (token) {
            var text = typeof token === 'object' ? (token.text || '') : String(token || '');
            return '<code class="rounded bg-slate-700/60 px-1 py-0.5 text-slate-100 text-[0.85em]">' + text + '</code>';
          }
        }});
      } catch (_) {}
    }
    window.renderMarkdownEls();
  }
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', setupMarked); }
  else { setupMarked(); }
})();"""))

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
          )
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
              raw(
                """<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 19V5M5 12l7-7 7 7"/></svg>"""
              )
            ),
          ),
        ),
      )
    )

  private def runInteractionComposer(conversationId: String, runControlId: String, meta: RunSessionUiMeta): Frag =
    div(
      id                           := runControlId,
      cls                          := "sticky bottom-0 mt-3 rounded-[1.4rem] bg-slate-900/90 ring-1 ring-white/10 px-3 pt-2.5 pb-2 backdrop-blur-xl shadow-[0_12px_40px_rgba(2,6,23,0.45)]",
      attr("data-conversation-id") := conversationId,
      attr("data-run-id")          := meta.runId,
      attr("data-workspace-id")    := meta.workspaceId,
      attr("data-status")          := runStateCode(meta.status),
      attr("data-attached-count")  := meta.attachedUsersCount.toString,
      attr("data-is-attached")     := "false",
    )(
      form(attr("data-role") := "run-form")(
        textarea(
          attr("data-role") := "run-input",
          rows              := 2,
          placeholder       := runInputPlaceholder(runStateCode(meta.status)),
          cls               := "w-full resize-none bg-transparent px-1 py-1 text-white placeholder:text-gray-500 focus:outline-none font-mono text-sm disabled:opacity-60",
          if isRunInputEnabled(runStateCode(meta.status)) then () else disabled,
          attr("title")     := runInputTooltip(runStateCode(meta.status)),
        )(),
        div(cls := "flex items-center gap-1.5 pt-1.5 border-t border-white/[0.06]")(
          span(
            cls               := "flex-1 min-w-0 text-[11px] text-slate-500 truncate",
            attr("data-role") := "feedback",
          )(""),
          button(
            `type`            := "button",
            cls               := s"rounded-full bg-cyan-600/90 hover:bg-cyan-500 text-white px-2.5 py-1 text-[11px] font-semibold tracking-[0.01em] ${
                if showAttach(runStateCode(meta.status)) then "" else "hidden"
              }",
            attr("data-role") := "attach",
          )("Attach"),
          button(
            `type`            := "button",
            cls               := "hidden rounded-full bg-slate-600/80 hover:bg-slate-500 text-white px-2.5 py-1 text-[11px] font-semibold tracking-[0.01em]",
            attr("data-role") := "detach",
          )("Detach"),
          button(
            `type`            := "button",
            cls               := s"hidden rounded-full bg-amber-600/90 hover:bg-amber-500 text-white px-2.5 py-1 text-[11px] font-semibold tracking-[0.01em] ${
                if runStateCode(meta.status).startsWith("running") then "" else "hidden"
              }",
            attr("data-role") := "interrupt",
          )("Interrupt"),
          button(
            `type`            := "button",
            cls               := s"rounded-full bg-emerald-600/90 hover:bg-emerald-500 text-white px-2.5 py-1 text-[11px] font-semibold tracking-[0.01em] ${
                if showContinue(runStateCode(meta.status)) then "" else "hidden"
              }",
            attr("data-role") := "continue",
          )("Continue"),
          button(
            `type`            := "button",
            cls               := s"rounded-full bg-rose-600/90 hover:bg-rose-500 text-white px-2.5 py-1 text-[11px] font-semibold tracking-[0.01em] ${
                if showCancel(runStateCode(meta.status)) then "" else "hidden"
              }",
            attr("data-role") := "cancel",
          )("Cancel"),
          button(
            `type`             := "submit",
            cls                := "ml-1 inline-flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full bg-cyan-600/95 text-white transition-colors hover:bg-cyan-500 disabled:opacity-50",
            attr("data-role")  := "send",
            attr("aria-label") := "Send to run",
            attr("title")      := "Send to run",
            if isRunInputEnabled(runStateCode(meta.status)) then () else disabled,
          )(
            raw(
              """<svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 19V5M5 12l7-7 7 7"/></svg>"""
            )
          ),
        ),
      )
    )

  private def gitPanelHtml(meta: RunSessionUiMeta, conversationId: String): Frag =
    val basePath = s"/api/workspaces/${meta.workspaceId}/runs/${meta.runId}/git"
    div(
      id                           := s"git-panel-$conversationId",
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
      style                        := "display:none",
    )(
      div(cls := "space-y-3 p-1")(
        p(cls := "text-xs text-gray-400", attr("data-role") := "summary")("Loading changes..."),
        div(cls := "space-y-1", attr("data-role") := "files-groups")(),
        div(
          cls               := "hidden rounded-lg border border-white/10 bg-black/20 p-3",
          attr("data-role") := "diff-viewer",
        )(
          div(cls := "mb-2 flex items-center justify-between gap-2")(
            h4(
              cls               := "text-xs font-semibold text-gray-200 font-mono truncate",
              attr("data-role") := "diff-title",
            )("Diff"),
            button(
              `type`          := "button",
              cls             := "flex-shrink-0 rounded bg-white/10 px-2 py-1 text-[11px] text-gray-200 hover:bg-white/20",
              attr("onclick") := """this.closest('[data-role="diff-viewer"]').classList.add('hidden')""",
            )("Close"),
          ),
          pre(
            cls               := "git-diff-lines text-xs overflow-auto rounded bg-black/40 p-2 max-h-[20rem]",
            attr("data-role") := "diff-content",
          )(),
        ),
      ),
      div(cls := "border-t border-white/10 mx-1 my-2")(),
      div(cls := "p-1 space-y-2")(
        div(cls := "text-xs text-gray-300", attr("data-role") := "branch-current")("Loading branch..."),
        div(cls := "text-xs text-gray-400", attr("data-role") := "ahead-behind")(),
        div(
          button(
            `type`            := "button",
            cls               := "mt-1 rounded-full bg-emerald-600/90 hover:bg-emerald-500 px-3 py-1 text-[11px] font-semibold text-white",
            attr("data-role") := "apply-button",
          )("Apply to repo")
        ),
        div(cls := "text-xs text-rose-300", attr("data-role") := "apply-feedback")(),
      ),
      div(cls := "border-t border-white/10 mx-1 my-2")(),
      div(cls := "p-1 space-y-1")(
        h4(cls := "text-xs font-semibold text-gray-300 mb-1")("Commits"),
        div(cls := "space-y-1.5", attr("data-role") := "commit-log")(
          p(cls := "text-xs text-gray-400")("Loading commits...")
        ),
      ),
    )

  private def gitPanelActivateScript(conversationId: String): Frag =
    script(
      raw(
        s"""(function(){
           |  var gp = document.getElementById('git-panel-$conversationId');
           |  if (!gp) return;
           |  window.__gitPanelEl = gp;
           |  window.addEventListener('ab-panel-open', function(e) {
           |    var d = (e && e.detail) || {};
           |    if (d.title !== 'Git Changes') return;
           |    requestAnimationFrame(function() {
           |      var c = document.getElementById('panel-context-panel-content');
           |      if (c && window.__gitPanelEl) {
           |        window.__gitPanelEl.style.display = '';
           |        c.replaceChildren(window.__gitPanelEl);
           |      }
           |    });
           |  });
           |})();
           |""".stripMargin
      )
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
      .git-diff-meta,
      .git-diff-hunk {
        grid-template-columns: 1fr;
      }
      .git-diff-meta .line-no,
      .git-diff-hunk .line-no {
        display: none;
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

  private def chatDetailStyles: Frag =
    tag("style")(
      raw("""
      /* Chat shell fills exactly the available below-header space so the two
         columns (conversation + side panel) can scroll independently. */
      .chat-detail-shell {
        height: calc(100vh - 2rem);   /* desktop: only main.py-4 to offset  */
        padding-top: 0.125rem;
      }
      @media (max-width: 1023px) {
        .chat-detail-shell {
          height: calc(100vh - 5.5rem); /* mobile: py-4 main + ~3.5rem topbar */
        }
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
    span(cls := "inline-flex items-center gap-1 rounded-full bg-white/5 px-2.5 py-0.5 text-gray-300")(
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
    state == "running:paused"

  private def showAttach(state: String): Boolean =
    state.startsWith("running")

  private def showCancel(state: String): Boolean =
    state == "pending" || state.startsWith("running")

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
