package shared.web

import scalatags.Text.all.*

object IssuesMarkdownSupport:

  def markdownFragment(markdown: String): Frag =
    val normalized = markdown.replace("\r\n", "\n")
    val lines      = normalized.split("\n", -1).toList
    val lineCount  = lines.length

    def collectWhile[A](start: Int)(f: String => Option[A]): (List[A], Int) =
      if start >= lineCount then (Nil, start)
      else
        f(lines(start)) match
          case Some(value) =>
            val (tail, next) = collectWhile(start + 1)(f)
            (value :: tail, next)
          case None        => (Nil, start)

    def collectUntil(start: Int, stop: String => Boolean): (List[String], Int) =
      if start >= lineCount || stop(lines(start)) then (Nil, start)
      else
        val (tail, next) = collectUntil(start + 1, stop)
        (lines(start) :: tail, next)

    def headingFrag(level: Int, textValue: String): Frag =
      val headingCls = "mt-5 mb-2 font-semibold text-slate-50"
      level match
        case 1 => h1(cls := s"$headingCls text-2xl")(renderInline(textValue))
        case 2 => h2(cls := s"$headingCls text-xl")(renderInline(textValue))
        case 3 => h3(cls := s"$headingCls text-lg")(renderInline(textValue))
        case 4 => h4(cls := s"$headingCls text-base")(renderInline(textValue))
        case _ => h5(cls := s"$headingCls text-sm")(renderInline(textValue))

    def isMarkdownTableHeader(idx: Int): Boolean =
      if idx + 1 >= lineCount then false
      else
        val header = parseMarkdownTableRow(lines(idx))
        val sep    = parseMarkdownTableRow(lines(idx + 1))
        header.nonEmpty && isMarkdownTableDivider(sep, header.length)

    def parseAt(idx: Int): List[Frag] =
      if idx >= lineCount then Nil
      else
        val line = lines(idx)
        if line.trim.isEmpty then parseAt(idx + 1)
        else if line.trim.startsWith("```") then
          val lang                  = line.trim.stripPrefix("```").trim
          val (codeLines, fenceIdx) = collectUntil(idx + 1, l => l.trim.startsWith("```"))
          val next                  = if fenceIdx < lineCount then fenceIdx + 1 else fenceIdx
          val block                 = div(cls := "my-4 rounded-lg border border-white/20 bg-slate-800 p-0")(
            if lang.nonEmpty then
              div(cls := "border-b border-white/15 px-3 py-1 text-xs uppercase tracking-wide text-slate-400")(lang)
            else (),
            pre(cls := "overflow-auto px-3 py-3 text-sm leading-6 text-slate-100")(codeLines.mkString("\n")),
          )
          block :: parseAt(next)
        else if isMarkdownTableHeader(idx) then
          val (tableLines, next) =
            collectWhile(idx)(current => Option.when(current.trim.nonEmpty && current.contains("|"))(current))
          markdownTableFrag(tableLines) :: parseAt(next)
        else
          headingLevel(line) match
            case Some((level, textValue)) =>
              headingFrag(level, textValue) :: parseAt(idx + 1)
            case None                     =>
              if line.trim.startsWith(">") then
                val (quoteLines, next) =
                  collectWhile(idx)(current =>
                    Option.when(current.trim.startsWith(">"))(current.trim.stripPrefix(">").trim)
                  )
                blockquote(cls := "my-3 border-l-4 border-indigo-400/40 pl-3 text-slate-300")(
                  paragraphWithBreaks(quoteLines)
                ) :: parseAt(next)
              else
                unorderedItem(line) match
                  case Some(_) =>
                    val (items, next) = collectWhile(idx)(current => unorderedItem(current))
                    ul(cls := "my-3 list-disc space-y-1 pl-6 text-slate-100")(items.map(item =>
                      li(renderInline(item))
                    )) :: parseAt(
                      next
                    )
                  case None    =>
                    orderedItem(line) match
                      case Some(_) =>
                        val (items, next) = collectWhile(idx)(current => orderedItem(current))
                        ol(cls := "my-3 list-decimal space-y-1 pl-6 text-slate-100")(items.map(item =>
                          li(renderInline(item))
                        )) :: parseAt(next)
                      case None    =>
                        val (paragraphLines, next) = collectWhile(idx)(current =>
                          Option.when(current.trim.nonEmpty && !startsBlock(current))(current)
                        )
                        p(cls := "my-3 whitespace-normal text-sm leading-7 text-slate-100")(
                          paragraphWithBreaks(paragraphLines)
                        ) :: parseAt(next)

    div(parseAt(0))

  private def paragraphWithBreaks(lines: List[String]): Seq[Frag] =
    lines.zipWithIndex.flatMap {
      case (line, idx) =>
        val parts = renderInline(line)
        if idx < lines.length - 1 then parts :+ br() else parts
    }

  private def renderInline(text: String): Seq[Frag] =
    def flush(buffer: String, acc: List[Frag]): List[Frag] =
      if buffer.nonEmpty then buffer :: acc else acc

    def loop(i: Int, buffer: String, acc: List[Frag]): List[Frag] =
      if i >= text.length then flush(buffer, acc).reverse
      else if text.startsWith("**", i) then
        val end = text.indexOf("**", i + 2)
        if end > i + 1 then
          val updated = strong(renderInline(text.substring(i + 2, end))) :: flush(buffer, acc)
          loop(end + 2, "", updated)
        else loop(i + 1, buffer + text.charAt(i), acc)
      else if text.charAt(i) == '*' then
        val end = text.indexOf('*', i + 1)
        if end > i then
          val updated = em(renderInline(text.substring(i + 1, end))) :: flush(buffer, acc)
          loop(end + 1, "", updated)
        else loop(i + 1, buffer + text.charAt(i), acc)
      else if text.charAt(i) == '`' then
        val end = text.indexOf('`', i + 1)
        if end > i then
          val updated =
            code(cls := "rounded bg-black/30 px-1 py-0.5 text-slate-100")(text.substring(i + 1, end)) :: flush(
              buffer,
              acc,
            )
          loop(end + 1, "", updated)
        else loop(i + 1, buffer + text.charAt(i), acc)
      else if text.charAt(i) == '[' then
        val closeBracket = text.indexOf(']', i + 1)
        val openParen    = if closeBracket >= 0 then text.indexOf('(', closeBracket + 1) else -1
        val closeParen   = if openParen >= 0 then text.indexOf(')', openParen + 1) else -1
        if closeBracket > i && openParen == closeBracket + 1 && closeParen > openParen then
          val label          = text.substring(i + 1, closeBracket)
          val urlCandidate   = text.substring(openParen + 1, closeParen)
          val linkFrag: Frag = safeHref(urlCandidate) match
            case Some(urlHref) =>
              a(
                href := urlHref,
                cls  := "text-indigo-300 underline decoration-indigo-400/60 underline-offset-2 hover:text-indigo-200",
              )(label)
            case None          => span(s"[$label]($urlCandidate)")
          loop(closeParen + 1, "", linkFrag :: flush(buffer, acc))
        else loop(i + 1, buffer + text.charAt(i), acc)
      else loop(i + 1, buffer + text.charAt(i), acc)

    loop(0, "", Nil)

  private def safeHref(href: String): Option[String] =
    val normalized = href.trim
    val lower      = normalized.toLowerCase
    if lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("/") || lower.startsWith("#")
    then Some(normalized)
    else None

  private def headingLevel(line: String): Option[(Int, String)] =
    val trimmed = line.trim
    val hashes  = trimmed.takeWhile(_ == '#').length
    if hashes >= 1 && hashes <= 6 && trimmed.drop(hashes).startsWith(" ") then
      Some((hashes, trimmed.drop(hashes).trim))
    else None

  private def unorderedItem(line: String): Option[String] =
    val trimmed = line.trim
    if trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") then Some(trimmed.drop(2).trim)
    else None

  private def orderedItem(line: String): Option[String] =
    val trimmed = line.trim
    val dotIdx  = trimmed.indexOf('.')
    if dotIdx > 0 && trimmed.take(dotIdx).forall(_.isDigit) && trimmed.lift(dotIdx + 1).contains(' ') then
      Some(trimmed.drop(dotIdx + 2).trim)
    else None

  private def startsBlock(line: String): Boolean =
    line.trim.startsWith("```") ||
    headingLevel(line).isDefined ||
    line.trim.startsWith(">") ||
    unorderedItem(line).isDefined ||
    orderedItem(line).isDefined

  private def markdownTableFrag(lines: List[String]): Frag =
    val parsedRows = lines.map(parseMarkdownTableRow).filter(_.nonEmpty)
    parsedRows match
      case header :: divider :: tail if isMarkdownTableDivider(divider, header.length) =>
        val bodyRows = tail.map(normalizeRow(_, header.length))
        div(cls := "my-4 overflow-x-auto")(
          table(cls := "min-w-full divide-y divide-white/10 rounded-lg border border-white/10 bg-black/20 text-sm")(
            thead(
              tr(header.map(col =>
                th(cls := "px-3 py-2 text-left font-semibold text-slate-100")(inlineWithBreaks(col))
              ))
            ),
            tbody(
              bodyRows.map { row =>
                tr(
                  cls := "odd:bg-white/0 even:bg-white/5",
                  row.map(col => td(cls := "px-3 py-2 text-slate-200")(inlineWithBreaks(col))),
                )
              }
            ),
          )
        )
      case _                                                                           =>
        p(cls := "my-3 whitespace-normal text-sm leading-7 text-slate-100")(lines.mkString("\n"))

  private def parseMarkdownTableRow(line: String): List[String] =
    val trimmed  = line.trim
    val stripped = trimmed.stripPrefix("|").stripSuffix("|")
    stripped.split("\\|", -1).toList.map(_.trim)

  private def isMarkdownTableDivider(row: List[String], expectedCols: Int): Boolean =
    row.length == expectedCols && row.forall(cell => cell.matches("^:?-{3,}:?$"))

  private def normalizeRow(row: List[String], size: Int): List[String] =
    if row.length >= size then row.take(size) else row ++ List.fill(size - row.length)("")

  private def inlineWithBreaks(text: String): Seq[Frag] =
    val parts = text.split("(?i)<br\\s*/?>", -1).toList
    parts.zipWithIndex.flatMap {
      case (part, idx) =>
        val rendered = renderInline(part)
        if idx < parts.length - 1 then rendered :+ br() else rendered
    }
