class IssuesBoard {
  constructor(root) {
    this.root = root;
    this.fragmentUrl = root?.dataset?.fragmentUrl || '/board/fragment';
    this.wsTopic = root?.dataset?.wsTopic || 'activity:feed';
    this.dragIssueId = null;
    this.dragCard = null;
    this.ghost = null;          // semi-transparent placeholder in source column
    this.placeholder = null;    // dashed drop-target shown in hovered column
    this.sourceColumn = null;   // column where drag started
    this.ws = null;
    this._refreshInFlight = false;
    this._refreshPending = false;
    this._pointerDragging = false;
    this._visibleColumns = new Set();
    this._customVisibleColumns = null;
    this.dragFromStatus = null;
    this._toastHost = null;

    this.bindDragDrop();
    this.bindPointerDrag();
    this.bindCollapse();
    this.bindQuickAdd();
    this.bindQuickDispatch();
    this.bindRefreshGuards();
    this.connectWs();
  }

  // ---------------------------------------------------------------------------
  // HTML5 drag & drop
  // ---------------------------------------------------------------------------

  bindDragDrop() {
    if (this._dragDropBound) {
      this._bindColumnListeners();
      return;
    }
    this._dragDropBound = true;

    this.root.addEventListener('dragstart', (event) => {
      if (this._isInteractiveTarget(event.target)) {
        event.preventDefault();
        return;
      }
      const card = event.target.closest('[data-issue-id]');
      if (!card) return;

      this._cleanupGhostArtifacts();
      this.dragCard = card;
      this.dragIssueId = card.dataset.issueId || null;
      this.sourceColumn = card.closest('[data-drop-status]');
      this.dragFromStatus = this._issueCardStatus(card) || this._normalizeStatusToken(this.sourceColumn?.dataset?.dropStatus);
      card.dataset.dragging = 'true';

      event.dataTransfer?.setData('text/plain', this.dragIssueId || '');
      event.dataTransfer.effectAllowed = 'move';

      // Card lift effect applied after paint so browser captures the un-lifted image
      requestAnimationFrame(() => {
        card.classList.add('opacity-40', '-translate-y-0.5', 'shadow-xl');
      });

      this._insertSourceGhost(card);
    });

    this.root.addEventListener('dragend', (event) => {
      const card = event.target.closest('[data-issue-id]');
      card?.classList.remove('opacity-40', '-translate-y-0.5', 'shadow-xl');
      if (card) delete card.dataset.dragging;
      this.root.querySelectorAll('[data-issue-id][data-dragging="true"]').forEach((el) => {
        delete el.dataset.dragging;
      });
      this._cleanupGhostArtifacts();
      this.dragCard = null;
      this.dragIssueId = null;
      this.sourceColumn = null;
      this.dragFromStatus = null;
      this.clearHighlights();
      this._flushPendingRefresh();
    });

    this._bindColumnListeners();
  }

  _bindColumnListeners() {
    this._bindDelegatedDragListeners();
  }

  _bindDelegatedDragListeners() {
    if (this._delegatedDragDropBound) return;
    this._delegatedDragDropBound = true;

    this.root.addEventListener('dragover', (event) => {
      const column = this._eventColumn(event);
      if (!column) return;

      this._ensureDragContext(event);
      if (!this.dragIssueId) return;

      const targetStatus = this._normalizeStatusToken(column.dataset.dropStatus);
      const allowed = this._canDropTo(targetStatus);
      if (allowed) {
        event.preventDefault();
        event.dataTransfer.dropEffect = 'move';
        this._highlightColumn(column, true);
        this._movePlaceholderTo(column);
      } else {
        event.dataTransfer.dropEffect = 'none';
        this._highlightColumn(column, false);
        if (this._placeholderColumn() === column) this._removePlaceholder();
      }
    });

    this.root.addEventListener('dragleave', (event) => {
      const column = this._eventColumn(event);
      if (!column) return;
      const related = event.relatedTarget;
      if (related && column.contains(related)) return;
      this._unhighlightColumn(column);
      if (this._placeholderColumn() === column) this._removePlaceholder();
    });

    this.root.addEventListener('drop', async (event) => {
      const column = this._eventColumn(event);
      if (!column) return;

      event.preventDefault();
      this._unhighlightColumn(column);
      this._removePlaceholder();
      this._ensureDragContext(event);

      const status = this._normalizeStatusToken(column.dataset.dropStatus || '');
      const issueId = this.dragIssueId || event.dataTransfer?.getData('text/plain') || '';
      if (!status || !issueId || !this._canDropTo(status, issueId)) return;

      const moved = await this.patchIssueStatus(issueId, status);
      if (moved) this.refreshBoard(issueId);
      else {
        this._showToast('Could not move issue. Please retry.');
        this._flushPendingRefresh();
      }
    });
  }

  // Insert a ghost (dashed placeholder) where the card was in the source column
  _insertSourceGhost(card) {
    this._removeSourceGhost();
    this.ghost = document.createElement('div');
    this.ghost.className = 'rounded-lg border border-dashed border-white/20 bg-white/5 h-[4.5rem] pointer-events-none';
    this.ghost.dataset.boardGhost = 'source';
    card.after(this.ghost);
  }

  _removeSourceGhost() {
    this.ghost?.remove();
    this.ghost = null;
  }

  // Insert/move a dashed placeholder into the hovered destination column
  _movePlaceholderTo(column) {
    if (this._placeholderColumn() === column) return;
    this._removePlaceholder();
    this.placeholder = document.createElement('div');
    this.placeholder.className = 'rounded-lg border-2 border-dashed border-emerald-400/60 bg-emerald-500/10 h-[4.5rem] pointer-events-none';
    this.placeholder.dataset.boardGhost = 'target';
    const cardsArea = column.querySelector('[data-role="column-cards"]');
    if (cardsArea) cardsArea.appendChild(this.placeholder);
    else column.appendChild(this.placeholder);
  }

  _removePlaceholder() {
    this.placeholder?.remove();
    this.placeholder = null;
  }

  _placeholderColumn() {
    return this.placeholder?.closest('[data-drop-status]') || null;
  }

  _cleanupGhostArtifacts() {
    this._removePlaceholder();
    this._removeSourceGhost();
    this.root.querySelectorAll('[data-board-ghost]').forEach((el) => el.remove());
  }

  _eventColumn(event) {
    const fromTarget = event?.target?.closest?.('[data-drop-status]');
    if (fromTarget) return fromTarget;
    const path = event?.composedPath?.() || [];
    for (const node of path) {
      if (node?.matches?.('[data-drop-status]')) return node;
      if (node?.closest) {
        const parent = node.closest('[data-drop-status]');
        if (parent) return parent;
      }
    }
    return null;
  }

  _ensureDragContext(event = null) {
    if (this.dragIssueId && this.dragFromStatus) return;

    const nativeIssueId = event?.dataTransfer?.getData('text/plain') || '';
    const draggingCard = this.root.querySelector('[data-issue-id][data-dragging="true"]');
    const resolvedCard = this.dragCard || draggingCard;
    const resolvedIssueId = this.dragIssueId || nativeIssueId || resolvedCard?.dataset?.issueId || null;
    const resolvedFromStatus = this.dragFromStatus || this._issueCardStatus(resolvedCard);

    if (resolvedCard) this.dragCard = resolvedCard;
    if (resolvedIssueId) this.dragIssueId = resolvedIssueId;
    if (resolvedFromStatus) this.dragFromStatus = resolvedFromStatus;
  }

  _highlightColumn(column, allowed = true) {
    this.clearHighlights();
    if (allowed) {
      column.classList.add('ring-2', 'ring-emerald-400/60', 'bg-emerald-500/10');
      column.classList.remove('cursor-not-allowed');
      column.dataset.dropHighlight = 'allowed';
    } else {
      column.classList.add('ring-2', 'ring-rose-400/60', 'bg-rose-500/10', 'cursor-not-allowed');
      column.dataset.dropHighlight = 'blocked';
    }
  }

  _unhighlightColumn(column) {
    column.classList.remove(
      'ring-2',
      'ring-emerald-400/60',
      'bg-emerald-500/10',
      'ring-rose-400/60',
      'bg-rose-500/10',
      'cursor-not-allowed',
    );
    column.dataset.dropHighlight = 'false';
  }

  clearHighlights() {
    this.root.querySelectorAll('[data-drop-status]').forEach((column) => {
      column.classList.remove(
        'ring-2',
        'ring-emerald-400/60',
        'bg-emerald-500/10',
        'ring-rose-400/60',
        'bg-rose-500/10',
        'cursor-not-allowed',
      );
      column.dataset.dropHighlight = 'false';
    });
  }

  // ---------------------------------------------------------------------------
  // Quick-add inline form
  // ---------------------------------------------------------------------------

  bindQuickAdd() {
    // Use event delegation on this.root so it works after HTMX injects content.
    // Guard with a flag so we only register once (constructor + refreshBoard both call this).
    if (this._quickAddBound) return;
    this._quickAddBound = true;

    this.root.addEventListener('click', (event) => {
      // Toggle button
      const toggleBtn = event.target.closest('[data-quick-add-toggle]');
      if (toggleBtn) {
        event.stopPropagation();
        this._openQuickAdd(toggleBtn.dataset.quickAddToggle);
        return;
      }
      // Submit button
      const submitBtn = event.target.closest('[data-quick-add-submit]');
      if (submitBtn) {
        this._submitQuickAdd(submitBtn.dataset.quickAddSubmit);
        return;
      }
      // Cancel button
      const cancelBtn = event.target.closest('[data-quick-add-cancel]');
      if (cancelBtn) {
        this._closeQuickAdd(cancelBtn.dataset.quickAddCancel);
        return;
      }
    });

    this.root.addEventListener('keydown', (event) => {
      const input = event.target.closest('[data-quick-add-title]');
      if (!input) return;
      if (event.key === 'Enter') this._submitQuickAdd(input.dataset.quickAddTitle);
      if (event.key === 'Escape') this._closeQuickAdd(input.dataset.quickAddTitle);
    });

    this.root.addEventListener('focusout', (event) => {
      const input = event.target.closest('[data-quick-add-title]');
      if (!input) return;
      requestAnimationFrame(() => this._flushPendingRefresh());
    });

    // Outside-click dismissal on document
    this._quickAddOutsideHandler = (event) => {
      if (!event.target.closest('[data-quick-add-form]') && !event.target.closest('[data-quick-add-toggle]')) {
        this.root.querySelectorAll('[data-quick-add-form]:not(.hidden)').forEach((form) => {
          form.classList.add('hidden');
          const titleInput = form.querySelector('[data-quick-add-title]');
          if (titleInput) titleInput.value = '';
        });
        this._flushPendingRefresh();
      }
    };
    document.addEventListener('click', this._quickAddOutsideHandler);
  }

  bindQuickDispatch() {
    if (this._quickAssignBound) return;
    this._quickAssignBound = true;

    this.root.addEventListener('click', async (event) => {
      const btn = event.target.closest('[data-quick-assign-action]');
      if (!btn) return;

      const issueId = btn.dataset.quickAssignAction || '';
      if (!issueId) return;

      const select = this.root.querySelector(`[data-quick-assign-agent="${CSS.escape(issueId)}"]`);
      const agentName = select?.value?.trim() || '';
      if (!agentName) {
        this._showToast('Select an agent before assigning.', 'warning');
        return;
      }

      btn.disabled = true;
      const original = btn.textContent;
      btn.textContent = '...';
      try {
        const assigned = await this.quickAssign(issueId, agentName);
        if (assigned) this.refreshBoard(issueId);
        else this._flushPendingRefresh();
      } finally {
        btn.disabled = false;
        btn.textContent = original || 'Assign';
      }
    });
  }

  _openQuickAdd(statusToken) {
    // Close any other open forms first
    this.root.querySelectorAll('[data-quick-add-form]').forEach((form) => {
      form.classList.add('hidden');
    });
    const form = this.root.querySelector(`[data-quick-add-form="${CSS.escape(statusToken)}"]`);
    if (!form) return;
    form.classList.remove('hidden');
    const titleInput = form.querySelector('[data-quick-add-title]');
    titleInput?.focus();
  }

  _closeQuickAdd(statusToken) {
    const form = this.root.querySelector(`[data-quick-add-form="${CSS.escape(statusToken)}"]`);
    if (!form) return;
    form.classList.add('hidden');
    const titleInput = form.querySelector('[data-quick-add-title]');
    if (titleInput) titleInput.value = '';
    this._flushPendingRefresh();
  }

  async _submitQuickAdd(statusToken) {
    const form = this.root.querySelector(`[data-quick-add-form="${CSS.escape(statusToken)}"]`);
    if (!form) return;

    const titleInput     = form.querySelector('[data-quick-add-title]');
    const prioritySelect = form.querySelector('[data-quick-add-priority]');
    const estimateSelect = form.querySelector('[data-quick-add-estimate]');
    const title          = titleInput?.value?.trim() || '';
    const priority       = prioritySelect?.value || 'Medium';
    const estimate       = estimateSelect?.value || '';

    if (!title) {
      titleInput?.focus();
      return;
    }

    this._closeQuickAdd(statusToken);

    try {
      await fetch('/api/issues', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({
          title,
          priority,
          estimate,
          status: this.toIssueStatus(statusToken),
          description: title,
          issueType: 'task',
        }),
      });
    } catch (_ignored) {
      // Best effort; board refresh will show current state
    }

    this.refreshBoard();
  }

  bindRefreshGuards() {
    if (this._refreshGuardsBound) return;
    this._refreshGuardsBound = true;

    this.root.addEventListener('htmx:beforeRequest', (event) => {
      const requestTarget = event?.detail?.target;
      if (requestTarget !== this.root) return;
      if (!this._shouldDeferRefresh()) return;
      event.preventDefault();
      this._refreshPending = true;
    });

    this.root.addEventListener('htmx:beforeSwap', (event) => {
      const requestTarget = event?.detail?.target;
      if (requestTarget !== this.root) return;
      if (!this._shouldDeferRefresh()) return;
      event.preventDefault();
      this._refreshPending = true;
    });

    // HTMX polling swaps board HTML directly; re-apply client-side visibility state
    // so hidden columns stay hidden across fragment refreshes.
    this.root.addEventListener('htmx:afterSwap', (event) => {
      const requestTarget = event?.detail?.target;
      if (requestTarget !== this.root) return;
      this.bindCollapse();
    });
  }

  // ---------------------------------------------------------------------------
  // Column visibility / hidden columns lane (persisted in localStorage)
  // ---------------------------------------------------------------------------

  bindCollapse() {
    if (!this._columnVisibilityBound) {
      this._columnVisibilityBound = true;

      this.root.addEventListener('click', (event) => {
        const hideBtn = event.target.closest('[data-collapse-toggle]');
        if (hideBtn) {
          const statusToken = hideBtn.dataset.collapseToggle;
          this._hideColumn(statusToken);
          return;
        }

        const showBtn = event.target.closest('[data-show-column]');
        if (showBtn) {
          const statusToken = showBtn.dataset.showColumn;
          this._showColumn(statusToken);
        }
      });

      this._customVisibleColumns = this._loadVisibleColumns();
      window.addEventListener('resize', () => {
        if (this._customVisibleColumns) {
          this._resizeColumnsToViewport();
          return;
        }
        this._syncVisibleColumns();
      });
    }

    this._syncVisibleColumns();
  }

  _visibleColumnsKey() {
    return 'board-visible-columns:v1';
  }

  _allBoardColumns() {
    return Array.from(this.root.querySelectorAll('[data-column-status]'));
  }

  _defaultVisibleColumns() {
    const showHumanReview = window.matchMedia('(min-width: 1280px)').matches;
    return showHumanReview
      ? ['backlog', 'todo', 'in_progress', 'human_review']
      : ['backlog', 'todo', 'in_progress'];
  }

  _canHideColumn(statusToken) {
    return ['human_review', 'rework', 'merging', 'done', 'canceled', 'duplicated'].includes(String(statusToken || ''));
  }

  _loadVisibleColumns() {
    try {
      const raw = localStorage.getItem(this._visibleColumnsKey());
      if (!raw) return null;
      const parsed = JSON.parse(raw);
      if (!Array.isArray(parsed)) return null;
      return parsed.map((v) => String(v || '').trim()).filter((v) => v.length > 0);
    } catch (_ignored) {
      return null;
    }
  }

  _saveVisibleColumns() {
    localStorage.setItem(this._visibleColumnsKey(), JSON.stringify(Array.from(this._visibleColumns)));
  }

  _syncVisibleColumns() {
    const statusTokens = this._allBoardColumns()
      .map((col) => col.dataset.columnStatus)
      .filter((v) => v && v.length > 0);
    const source = this._customVisibleColumns || this._defaultVisibleColumns();
    const visible = source.filter((status) => statusTokens.includes(status));

    this._visibleColumns = new Set(visible);
    this._applyColumnVisibility();
  }

  _applyColumnVisibility() {
    this._allBoardColumns().forEach((col) => {
      const statusToken = col.dataset.columnStatus;
      col.classList.toggle('hidden', !this._visibleColumns.has(statusToken));
    });
    this._renderHiddenColumnLane();
    this._resizeColumnsToViewport();
  }

  _hideColumn(statusToken) {
    if (!statusToken || !this._visibleColumns.has(statusToken)) return;
    if (!this._canHideColumn(statusToken)) return;
    if (this._visibleColumns.size <= 3) return;

    this._visibleColumns.delete(statusToken);
    this._customVisibleColumns = Array.from(this._visibleColumns);
    this._saveVisibleColumns();
    this._applyColumnVisibility();
  }

  _showColumn(statusToken) {
    if (!statusToken || this._visibleColumns.has(statusToken)) return;
    this._visibleColumns.add(statusToken);
    this._customVisibleColumns = Array.from(this._visibleColumns);
    this._saveVisibleColumns();
    this._applyColumnVisibility();
  }

  _renderHiddenColumnLane() {
    const lane = this.root.querySelector('[data-hidden-columns-column]');
    const list = this.root.querySelector('[data-hidden-columns-list]');
    const countNode = this.root.querySelector('[data-hidden-columns-count]');
    if (!lane || !list || !countNode) return;

    const hiddenColumns = [];
    this._allBoardColumns().forEach((col) => {
      const statusToken = col.dataset.columnStatus || '';
      if (!statusToken || this._visibleColumns.has(statusToken)) return;
      hiddenColumns.push({
        statusToken,
        label: col.dataset.columnLabel || statusToken,
        count: col.querySelector(`[data-column-count="${statusToken}"]`)?.textContent || '0',
      });
    });

    countNode.textContent = String(hiddenColumns.length);
    list.textContent = '';

    if (hiddenColumns.length === 0) {
      const empty = document.createElement('p');
      empty.className = 'rounded border border-dashed border-white/10 px-2 py-3 text-xs text-slate-500';
      empty.textContent = 'No hidden columns';
      list.appendChild(empty);
      return;
    }

    hiddenColumns.forEach(({ statusToken, label, count }) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'w-full rounded border border-white/10 bg-slate-800/70 px-2 py-1.5 text-left text-xs text-slate-200 hover:bg-slate-700';
      button.dataset.showColumn = statusToken;
      button.textContent = `${label} (${count})`;
      list.appendChild(button);
    });
  }

  _resizeColumnsToViewport() {
    const laneColumns = Array.from(this.root.querySelectorAll('[data-board-column]'))
      .filter((el) => !el.classList.contains('hidden'));
    if (laneColumns.length === 0) return;

    const rootWidth = this.root.clientWidth;
    if (!rootWidth || rootWidth <= 0) return;

    const style = window.getComputedStyle(this.root);
    const gap = parseFloat(style.columnGap || style.gap || '12') || 12;
    const minWidth = 280;
    const available = rootWidth - (gap * (laneColumns.length - 1));
    const target = Math.floor(available / laneColumns.length);
    const laneWidth = Math.max(minWidth, target);

    laneColumns.forEach((col) => {
      col.style.flex = `0 0 ${laneWidth}px`;
      col.style.width = `${laneWidth}px`;
    });
  }

  // ---------------------------------------------------------------------------
  // Touch / pointer drag (basic support for mobile/tablet)
  // ---------------------------------------------------------------------------

  bindPointerDrag() {
    if (this._pointerDragBound) return;
    this._pointerDragBound = true;

    let dragging = false;
    let pointerCard = null;
    let clone = null;
    let startX = 0, startY = 0;
    let offsetX = 0, offsetY = 0;

    const onPointerDown = (event) => {
      if (event.pointerType === 'mouse') return; // handled by native DnD
      if (this._isInteractiveTarget(event.target)) return;
      const card = event.target.closest('[data-issue-id]');
      if (!card) return;

      dragging = false;
      pointerCard = card;
      startX = event.clientX;
      startY = event.clientY;

      const rect = card.getBoundingClientRect();
      offsetX = event.clientX - rect.left;
      offsetY = event.clientY - rect.top;
    };

    const onPointerMove = (event) => {
      if (!pointerCard) return;
      const dx = Math.abs(event.clientX - startX);
      const dy = Math.abs(event.clientY - startY);
      if (!dragging && dx < 8 && dy < 8) return;

      if (!dragging) {
        dragging = true;
        this._pointerDragging = true;
        this._cleanupGhostArtifacts();
        this.dragIssueId = pointerCard.dataset.issueId || null;
        this.dragCard = pointerCard;
        this.sourceColumn = pointerCard.closest('[data-drop-status]');
        this.dragFromStatus = this._issueCardStatus(pointerCard) || this._normalizeStatusToken(this.sourceColumn?.dataset?.dropStatus);
        pointerCard.dataset.dragging = 'true';

        pointerCard.classList.add('opacity-40');
        this._insertSourceGhost(pointerCard);

        // Create floating visual clone
        clone = pointerCard.cloneNode(true);
        clone.style.cssText = `position:fixed;pointer-events:none;z-index:9999;width:${pointerCard.offsetWidth}px;opacity:0.9;box-shadow:0 8px 32px rgba(0,0,0,0.5);`;
        document.body.appendChild(clone);
      }

      if (clone) {
        clone.style.left = `${event.clientX - offsetX}px`;
        clone.style.top = `${event.clientY - offsetY}px`;
      }

      // Highlight column under pointer
      const el = document.elementFromPoint(event.clientX, event.clientY);
      const col = el?.closest('[data-drop-status]');
      if (col) {
        const targetStatus = this._normalizeStatusToken(col.dataset.dropStatus);
        const allowed = this._canDropTo(targetStatus);
        this._highlightColumn(col, allowed);
        if (allowed) this._movePlaceholderTo(col);
        else if (this._placeholderColumn() === col) this._removePlaceholder();
      }
    };

    const onPointerUp = async (event) => {
      if (!dragging || !pointerCard) {
        pointerCard = null;
        return;
      }

      clone?.remove();
      clone = null;
      pointerCard.classList.remove('opacity-40');
      delete pointerCard.dataset.dragging;
      this._cleanupGhostArtifacts();
      this.clearHighlights();

      const el = document.elementFromPoint(event.clientX, event.clientY);
      const col = el?.closest('[data-drop-status]');
      const status = col?.dataset?.dropStatus || '';
      const issueId = this.dragIssueId || '';

      dragging = false;
      this._pointerDragging = false;
      pointerCard = null;
      this.dragIssueId = null;
      this.dragCard = null;
      this.sourceColumn = null;
      this.dragFromStatus = null;

      if (status && issueId && this._canDropTo(status, issueId)) {
        const moved = await this.patchIssueStatus(issueId, status);
        if (moved) this.refreshBoard(issueId);
        else {
          this._showToast('Could not move issue. Please retry.');
          this._flushPendingRefresh();
        }
      } else {
        this._flushPendingRefresh();
      }
    };

    this.root.addEventListener('pointerdown', onPointerDown);
    window.addEventListener('pointermove', onPointerMove);
    window.addEventListener('pointerup', onPointerUp);
  }

  // ---------------------------------------------------------------------------
  // API & board refresh
  // ---------------------------------------------------------------------------

  async patchIssueStatus(issueId, status) {
    const card = this.root.querySelector(`[data-issue-id="${CSS.escape(issueId)}"]`);
    const currentStatus = this._issueCardStatus(card);
    const targetStatus = this._normalizeStatusToken(status);
    if (!targetStatus || !this.isTransitionAllowed(currentStatus, targetStatus)) return false;

    const payload = { status: this.toIssueStatus(targetStatus) };
    const currentAgent = card?.dataset?.assignedAgent || '';
    if (currentAgent.trim()) payload.agentName = currentAgent.trim();

    if (targetStatus === 'done') payload.resultData = 'Status updated from board';
    if (targetStatus === 'rework') payload.reason = 'Marked rework from board';
    if (targetStatus === 'canceled') payload.reason = 'Canceled from board';
    if (targetStatus === 'duplicated') payload.reason = 'Marked duplicated from board';

    try {
      const response = await fetch(`/api/issues/${encodeURIComponent(issueId)}/status`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify(payload),
      });
      if (!response.ok) {
        this._showToast(await this._responseErrorMessage(response, 'Could not update issue status'));
        return false;
      }
      if (card) card.dataset.issueStatus = targetStatus;

      // Moving to Todo should trigger auto-assignment based on required capabilities.
      // Keep this best-effort and non-blocking for board state transitions.
      const hasAssignedAgent = (card?.dataset?.assignedAgent || '').trim().length > 0;
      if (targetStatus === 'todo' && !hasAssignedAgent) {
        const workspaceId = (card?.dataset?.workspaceId || '').trim();
        const autoAssigned = await this.autoAssignIssue(issueId, workspaceId || null);
        if (!autoAssigned) {
          this._showToast('Issue moved to Todo, but auto-assign failed.', 'warning');
        }
      }
      return true;
    } catch (_error) {
      this._showToast('Could not update issue status. Check your connection and retry.');
      return false;
    }
  }

  async quickAssign(issueId, agentName) {
    try {
      const card = this.root.querySelector(`[data-issue-id="${CSS.escape(issueId)}"]`);
      const workspaceId = (card?.dataset?.workspaceId || '').trim();
      const body = { agentName };
      if (workspaceId) body.workspaceId = workspaceId;
      const response = await fetch(`/api/issues/${encodeURIComponent(issueId)}/assign`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify(body),
      });
      if (!response.ok) {
        this._showToast(await this._responseErrorMessage(response, 'Could not assign issue'));
        return false;
      }
      if (card) card.dataset.assignedAgent = agentName;
      return true;
    } catch (_error) {
      this._showToast('Could not assign issue. Check your connection and retry.');
      return false;
    }
  }

  async autoAssignIssue(issueId, workspaceId = null) {
    try {
      const payload = {};
      if (workspaceId && workspaceId.trim()) payload.workspaceId = workspaceId.trim();
      const response = await fetch(`/api/issues/${encodeURIComponent(issueId)}/auto-assign`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify(payload),
      });
      if (!response.ok) {
        this._showToast(await this._responseErrorMessage(response, 'Could not auto-assign issue'));
        return false;
      }
      return response.ok;
    } catch (_error) {
      this._showToast('Could not auto-assign issue. Check your connection and retry.');
      return false;
    }
  }

  _ensureToastHost() {
    if (this._toastHost && this._toastHost.isConnected) return this._toastHost;
    this.root.classList.add('relative');

    let host = this.root.querySelector('[data-inline-toast-host]');
    if (!host) {
      host = document.createElement('div');
      host.dataset.inlineToastHost = 'true';
      host.className = 'pointer-events-none absolute right-3 top-3 z-50 flex max-w-xs flex-col gap-2';
      this.root.appendChild(host);
    }

    this._toastHost = host;
    return host;
  }

  _showToast(message, type = 'error', timeoutMs = 2600) {
    const text = String(message || '').trim();
    if (!text) return;

    const host = this._ensureToastHost();
    const toast = document.createElement('div');
    const tone = type === 'warning'
      ? 'border-amber-300/40 bg-amber-500/20 text-amber-100'
      : type === 'success'
        ? 'border-emerald-300/40 bg-emerald-500/20 text-emerald-100'
        : 'border-rose-300/40 bg-rose-500/20 text-rose-100';

    toast.className = `pointer-events-auto rounded-md border px-3 py-2 text-xs shadow-lg backdrop-blur transition-all duration-200 opacity-0 -translate-y-1 ${tone}`;
    toast.textContent = text;
    host.appendChild(toast);

    requestAnimationFrame(() => {
      toast.classList.remove('opacity-0', '-translate-y-1');
    });

    const removeToast = () => {
      toast.classList.add('opacity-0', '-translate-y-1');
      window.setTimeout(() => toast.remove(), 200);
    };
    window.setTimeout(removeToast, timeoutMs);
  }

  async _responseErrorMessage(response, fallback) {
    try {
      const contentType = response.headers.get('content-type') || '';
      if (contentType.includes('application/json')) {
        const payload = await response.json();
        const message = payload?.message || payload?.error || payload?.detail || payload?.cause;
        if (message) return `${fallback}: ${String(message).trim()}`;
      } else {
        const text = (await response.text()).trim();
        if (text) return `${fallback}: ${text.slice(0, 140)}`;
      }
    } catch (_ignored) {
      // fallback below
    }
    return fallback;
  }

  _isInteractiveTarget(target) {
    const el = target instanceof Element ? target : null;
    if (!el) return false;
    return Boolean(
      el.closest(
        'button,select,input,textarea,a,label,[data-quick-assign-action],[data-quick-assign-agent],[data-quick-add-toggle],[data-quick-add-submit],[data-quick-add-cancel]',
      ),
    );
  }

  toIssueStatus(statusToken) {
    const rawToken = String(statusToken || '').trim().toLowerCase();
    switch (rawToken) {
      case 'open': return 'Open';
      case 'assigned': return 'Assigned';
      case 'completed': return 'Completed';
      case 'failed': return 'Failed';
      case 'skipped': return 'Skipped';
      default: break;
    }

    switch (this._normalizeStatusToken(statusToken)) {
      case 'backlog': return 'Backlog';
      case 'todo': return 'Todo';
      case 'in_progress': return 'InProgress';
      case 'human_review': return 'HumanReview';
      case 'rework': return 'Rework';
      case 'merging': return 'Merging';
      case 'done': return 'Done';
      case 'canceled': return 'Canceled';
      case 'duplicated': return 'Duplicated';
      default: return 'Backlog';
    }
  }

  _issueCardStatus(card) {
    return this._normalizeStatusToken(card?.dataset?.issueStatus || '');
  }

  _canDropTo(targetStatus, issueId = null) {
    const fromStatus = this.dragFromStatus || this._issueCardStatus(
      issueId ? this.root.querySelector(`[data-issue-id="${CSS.escape(issueId)}"]`) : this.dragCard,
    );
    return this.isTransitionAllowed(fromStatus, targetStatus);
  }

  _normalizeStatusToken(rawStatus) {
    const token = String(rawStatus || '')
      .trim()
      .toLowerCase()
      .replace(/[\s-]+/g, '_');

    switch (token) {
      case 'open': return 'backlog';
      case 'assigned': return 'todo';
      case 'completed': return 'done';
      case 'failed': return 'rework';
      case 'skipped': return 'canceled';
      case 'inprogress': return 'in_progress';
      case 'humanreview': return 'human_review';
      default: return token;
    }
  }

  isTransitionAllowed(fromStatusToken, toStatusToken) {
    const from = this._normalizeStatusToken(fromStatusToken);
    const to = this._normalizeStatusToken(toStatusToken);
    if (!from || !to || from === to) return false;

    const matrix = {
      backlog: new Set(['todo', 'done', 'canceled', 'duplicated']),
      todo: new Set(['backlog', 'in_progress', 'done', 'canceled', 'duplicated']),
      in_progress: new Set(['human_review', 'done', 'canceled', 'duplicated']),
      human_review: new Set(['rework', 'merging', 'done', 'canceled', 'duplicated']),
      rework: new Set(['in_progress', 'done', 'canceled', 'duplicated']),
      merging: new Set(['done', 'canceled', 'duplicated']),
      done: new Set([]),
      canceled: new Set(['backlog']),
      duplicated: new Set([]),
    };
    return matrix[from]?.has(to) === true;
  }

  _flashLandedCard(issueId) {
    if (!issueId) return;
    const landed = this.root.querySelector(`[data-issue-id="${CSS.escape(issueId)}"]`);
    if (landed) {
      landed.classList.add('bg-white/10');
      setTimeout(() => landed.classList.remove('bg-white/10'), 300);
    }
  }

  _shouldDeferRefresh() {
    if (this.dragIssueId || this.dragCard || this.sourceColumn || this.placeholder || this._pointerDragging) {
      return true;
    }

    const activeElement = document.activeElement;
    if (activeElement && activeElement.closest && activeElement.closest('[data-quick-add-form]')) {
      return true;
    }

    const openForms = this.root.querySelectorAll('[data-quick-add-form]:not(.hidden)');
    for (const form of openForms) {
      const titleInput = form.querySelector('[data-quick-add-title]');
      if (titleInput?.value?.trim()) return true;
    }

    return false;
  }

  _flushPendingRefresh() {
    if (!this._refreshPending) return;
    if (this._refreshInFlight) return;
    if (this._shouldDeferRefresh()) return;
    this._refreshPending = false;
    this.refreshBoard();
  }

  refreshBoard(landedIssueId = null) {
    if (this._refreshInFlight) {
      this._refreshPending = true;
      return;
    }

    if (this._shouldDeferRefresh()) {
      this._refreshPending = true;
      return;
    }

    this._refreshInFlight = true;

    const onSettled = () => {
      this._refreshInFlight = false;
      this._flushPendingRefresh();
    };

    if (window.htmx?.ajax) {
      window.htmx.ajax('GET', this.fragmentUrl, {
        target: this.root,
        swap: 'innerHTML',
      }).then(() => {
        this.bindDragDrop();
        this.bindCollapse();
        this.bindQuickAdd();
        this.bindQuickDispatch();
        this._flashLandedCard(landedIssueId);
      }).catch(() => {}).finally(onSettled);
      return;
    }

    fetch(this.fragmentUrl)
      .then((response) => response.ok ? response.text() : Promise.reject(new Error('refresh failed')))
      .then((html) => {
        // html is server-rendered markup from our own trusted endpoint
        this.root.innerHTML = html; // nosec: trusted server HTML, same origin
        this.bindDragDrop();
        this.bindCollapse();
        this.bindQuickAdd();
        this.bindQuickDispatch();
        this._flashLandedCard(landedIssueId);
      })
      .catch(() => {})
      .finally(onSettled);
  }

  // ---------------------------------------------------------------------------
  // WebSocket
  // ---------------------------------------------------------------------------

  connectWs() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    this.ws = new WebSocket(`${protocol}//${window.location.host}/ws/console`);
    this.ws.onopen = () => {
      this.ws?.send(JSON.stringify({ Subscribe: { topic: this.wsTopic, params: {} } }));
    };
    this.ws.onmessage = (event) => this.onWsMessage(event.data);
  }

  onWsMessage(raw) {
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch (_ignored) {
      return;
    }

    const evt = parsed?.Event;
    if (!evt || evt.topic !== this.wsTopic) return;
    if (evt.eventType === 'activity-feed') {
      this.refreshBoard();
    }
  }
}

document.querySelectorAll('#issues-board-root').forEach((root) => {
  if (!root.__issuesBoard) {
    root.__issuesBoard = new IssuesBoard(root);
  }
});
