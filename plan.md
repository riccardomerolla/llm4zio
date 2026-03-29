## Group 1: Workspace Template Wizard Experience

- [x] Replace the static install-heavy workspace templates page with a wizard-oriented layout centered on the selected template.
- [x] Present the seven standard questions in a guided format and include conditional run-mode details that match the current workspace model.
- [x] Surface the user prompt and the generated next issue cards prominently so the page explains the full scaffolding and planning flow.
- [x] Review the completed wizard view for clarity, consistency with the existing design system, and alignment with the workspace-template skill.

## Group 2: Tests and Verification

- [x] Add focused view and route coverage for the new workspace template wizard content.
- [x] Run formatting and the relevant test suites, fixing any regressions discovered during verification.
- [x] Review the final changes for correctness, regressions, and adherence to the Scala 3 + ZIO project guidelines.

## Group 3: Wizard UX Revision

- [x] Rework the workspace template page styling to recover the cleaner card layout and calmer typography from the earlier version.
- [x] Replace the static question preview with an interactive wizard flow that lets the user move through the seven standard questions while keeping the prompt editor visible.
- [x] Add a live brief panel that reflects the current wizard answers, including conditional run-mode details, scaffold outputs, and next issue cards.
- [x] Update tests and verification for the revised interactive wizard experience, then review the final result for visual and behavioral regressions.

## Group 4: Template Selector Regression

- [x] Restore the original workspace-template card styling from commit 82ab6d71211fe4a251ae3d3dd27591456adcb15f while preserving the interactive wizard flow below it.
- [x] Fix the template-selection behavior so switching cards updates the active template panel, wizard answers, and live brief reliably.
- [x] Refresh the focused view and controller checks, then run formatting and verification for the selector regression fix.

## Group 5: Inline Script Emission Fix

- [x] Fix the workspace template inline script generation so the browser receives valid JavaScript instead of raw Scala margin markers.
- [x] Re-verify template selection in the rendered page and keep focused checks aligned with the fix.
