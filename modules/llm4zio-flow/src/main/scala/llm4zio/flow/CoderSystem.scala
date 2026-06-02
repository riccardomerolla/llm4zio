package llm4zio.flow

/** Centralized coder system-prompt text. The runtime owns version control: coders edit files only; the flow stages,
  * commits, branches, and pushes. Prepended to coder chats by default (see [[Chat.start]]) so behaviour is consistent
  * across flows instead of being hand-typed per example.
  */
object CoderSystem:
  val gitOwnership: String =
    "The runtime manages version control. Do NOT run git commit, git push, or create/switch branches yourself — " +
      "just edit files in the working tree. The flow handles staging, commits, branches, and pushes."
