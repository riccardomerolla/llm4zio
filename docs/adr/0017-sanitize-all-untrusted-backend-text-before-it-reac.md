# 17. Sanitize all untrusted backend text before it reaches the terminal (TerminalSafe)

- Status: Accepted

## Context
A flow renders large amounts of text the library does not control: backend stderr, assistant messages, and tool-call output produced by LLMs and subprocesses. Echoing this raw to a terminal is a real security and integrity hazard — embedded ANSI CSI/OSC escapes and C0/C1 control bytes can rewrite the screen, spoof prompts, alter the cursor, or smuggle clipboard/title operations. The runner needs a single chokepoint where untrusted text is neutralised before styling.

## Decision
Route all untrusted text through TerminalSafe (runner), which strips ANSI CSI/OSC escape sequences and C0/C1 control bytes before any styling is applied, across backend stderr, assistant messages, and tool output. Colour itself is applied by the runner's own Palette/fansi after sanitisation, and auto-disables off-TTY or under NO_COLOR. The sanitiser is part of the terminal surface, not the orchestration, so the typed FlowEvent stream carries data and the runner decides how to render it safely.

## Alternatives considered
Pass backend text through to the terminal unmodified — rejected as a control-sequence injection hazard (screen spoofing, cursor/title manipulation). Sanitize only at each call site that prints — rejected as error-prone and easy to forget; a single TerminalSafe chokepoint guarantees coverage. Rely on the terminal emulator to be safe — rejected because emulator behaviour varies and untrusted escapes can still cause confusing or malicious output.

## Consequences
Untrusted text cannot drive the operator's terminal, only display as inert characters. There is one auditable place responsible for sanitisation, kept in the runner alongside rendering. The trade-off is that any legitimately-colored output from a backend is stripped and re-styled by the runner's own palette rather than passed through.
