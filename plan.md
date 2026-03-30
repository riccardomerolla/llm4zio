## Board Refresh And Cancel Flow

### Group 1: Status correctness
- [x] Stop filtering canceled issues out of the board data set so drag-and-drop into Canceled remains visible after refresh.
- [x] Align board status loading and rendering with the supported board columns and verify canceled/archive behavior for workspace-backed board issues.
- [x] Add focused controller and view coverage for canceled issues on the board.

### Group 2: Drag-and-drop feedback
- [x] Add a lightweight top loading bar for board page and fragment refresh activity.
- [x] Surface in-flight feedback during drag-and-drop mutations and refreshes so the user can tell when the board is updating.
- [x] Add focused client-side rendering coverage for the new loading affordance where practical.

### Group 3: Fragment performance
- [x] Reduce board and fragment latency by removing unnecessary work from the refresh path and parallelizing independent data loads.
- [x] Add lightweight timing instrumentation for board page and fragment rendering to make future slowdowns visible in logs and responses.
- [x] Run formatting, targeted tests, and a review pass for the completed changes before finishing.
