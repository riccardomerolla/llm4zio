import { LitElement } from 'https://cdn.jsdelivr.net/npm/lit@3/+esm';

const createBoardSyncHooks = window.__issuesBoardSync?.createBoardSyncHooks || (() => ({
  _flushPendingRefresh() {},
  refreshBoard() {},
  _flushPostRefreshToast() {},
  connectWs() {},
  onWsMessage() {},
}));

class AbIssuesBoard extends LitElement {
  // Light DOM — content is server-rendered inside the custom element
  createRenderRoot() { return this; }

  // Never re-render — HTMX manages the inner div's content
  shouldUpdate() { return false; }

  connectedCallback() {
    super.connectedCallback();
    // The inner #issues-board-root div is where HTMX does its polling.
    // We operate on it via this.root so htmx.ajax() targets the inner div,
    // keeping HTMX's internal request state completely isolated from
    // LitElement's lifecycle on the outer custom element.
    this.root = this.querySelector('#issues-board-root') || this;
    this.fragmentUrl = this.dataset.fragmentUrl || '/board/fragment';
    this.wsTopic = this.dataset.wsTopic || 'activity:feed';
    this.dragIssueId = null;
    this.dragCard = null;
    this.ghost = null;          // semi-transparent placeholder in source column
    this.placeholder = null;    // dashed drop-target shown in hovered column
    this.sourceColumn = null;   // column where drag started
    this.ws = null;
    this._refreshInFlight = false;
    this._refreshPending = false;
    this._pointerDragging = false;
    this.dragFromStatus = null;
    this._toastHost = null;
    this._pendingPostRefreshToast = null;
    this._loadingCount = 0;
    this._loadingResetTimer = null;
    this._loadingRoot = null;
    this._loadingFill = null;
    this._loadingVisibleSince = 0;

    this.bindDragDrop();
    this.bindPointerDrag();
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
      this._beginLoading();
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
    } finally {
      this._endLoading();
    }

    this.refreshBoard();
  }

  bindRefreshGuards() {
    if (this._refreshGuardsBound) return;
    this._refreshGuardsBound = true;

    // Persist column scroll positions across HTMX fragment refreshes
    this._savedScrolls = {};

    this.root.addEventListener('htmx:beforeRequest', (event) => {
      const requestTarget = event?.detail?.target;
      if (requestTarget !== this.root) return;
      if (!this._shouldDeferRefresh()) return;
      event.preventDefault();
      this._refreshPending = true;
      this._endLoading();
    });

    this.root.addEventListener('htmx:beforeSwap', (event) => {
      const requestTarget = event?.detail?.target;
      if (requestTarget !== this.root) return;

      // Save each column's card-list scroll offset keyed by status token
      this.root.querySelectorAll('[data-column-cards]').forEach(el => {
        const key = el.dataset.columnCards;
        if (key) this._savedScrolls[key] = el.scrollTop;
      });

      if (!this._shouldDeferRefresh()) return;
      event.preventDefault();
      this._refreshPending = true;
      this._endLoading();
    });

    this.root.addEventListener('htmx:afterSwap', (event) => {
      const requestTarget = event?.detail?.target;
      if (requestTarget !== this.root) return;

      // ab-board-layout defers _applyState() via Promise.resolve() (microtask).
      // The microtask runs before any macrotask, so a double-rAF here guarantees
      // we restore scroll only after expanded columns are visible.
      const saved = this._savedScrolls;
      requestAnimationFrame(() => requestAnimationFrame(() => {
        this.root.querySelectorAll('[data-column-cards]').forEach(el => {
          const key = el.dataset.columnCards;
          if (key && saved[key]) el.scrollTop = saved[key];
        });
        this._savedScrolls = {};
      }));

      this._endLoading();
    });

    this.root.addEventListener('htmx:responseError', (event) => {
      const requestTarget = event?.detail?.target;
      if (requestTarget !== this.root) return;
      this._endLoading();
    });

    this.root.addEventListener('htmx:sendError', (event) => {
      const requestTarget = event?.detail?.target;
      if (requestTarget !== this.root) return;
      this._endLoading();
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
      this._beginLoading();
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
        const autoAssign = await this.autoAssignIssue(issueId, workspaceId || null);
        if (!autoAssign.assigned) {
          this._pendingPostRefreshToast = {
            message: autoAssign.message || 'Issue moved to Todo, but auto-assign failed.',
            type: 'warning',
          };
        }
      }
      return true;
    } catch (_error) {
      this._showToast('Could not update issue status. Check your connection and retry.');
      return false;
    } finally {
      this._endLoading();
    }
  }

  async quickAssign(issueId, agentName) {
    try {
      this._beginLoading();
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
    } finally {
      this._endLoading();
    }
  }

  async autoAssignIssue(issueId, workspaceId = null) {
    try {
      this._beginLoading();
      const payload = {};
      if (workspaceId && workspaceId.trim()) payload.workspaceId = workspaceId.trim();
      const response = await fetch(`/api/issues/${encodeURIComponent(issueId)}/auto-assign`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify(payload),
      });
      if (!response.ok) {
        return {
          assigned: false,
          message: await this._responseErrorMessage(response, 'Could not auto-assign issue'),
        };
      }
      const payloadJson = await response.json();
      if (payloadJson?.assigned === true) {
        return { assigned: true };
      }
      return {
        assigned: false,
        message: payloadJson?.reason
          ? `Issue moved to Todo, but auto-assign did not dispatch a run: ${String(payloadJson.reason).trim()}`
          : 'Issue moved to Todo, but auto-assign did not dispatch a run.',
      };
    } catch (_error) {
      return {
        assigned: false,
        message: 'Could not auto-assign issue. Check your connection and retry.',
      };
    } finally {
      this._endLoading();
    }
  }

  _ensureLoadingBar() {
    if (this._loadingRoot?.isConnected && this._loadingFill?.isConnected) {
      return { root: this._loadingRoot, fill: this._loadingFill };
    }

    const loadingRoot = document.querySelector('[data-board-loading]');
    const loadingFill = loadingRoot?.querySelector('[data-board-loading-fill]') || null;
    this._loadingRoot = loadingRoot || null;
    this._loadingFill = loadingFill;
    return { root: this._loadingRoot, fill: this._loadingFill };
  }

  _beginLoading() {
    this._loadingCount += 1;
    const { root, fill } = this._ensureLoadingBar();
    if (!root || !fill) return;

    if (this._loadingResetTimer) {
      window.clearTimeout(this._loadingResetTimer);
      this._loadingResetTimer = null;
    }

    this._loadingVisibleSince = Date.now();
    this.root.dataset.boardBusy = 'true';
    root.style.opacity = '1';
    fill.style.transitionDuration = '480ms';
    fill.style.transform = 'scaleX(0.28)';
    requestAnimationFrame(() => {
      fill.style.transform = 'scaleX(0.78)';
    });
  }

  _endLoading() {
    if (this._loadingCount > 0) this._loadingCount -= 1;
    if (this._loadingCount > 0) return;

    const { root, fill } = this._ensureLoadingBar();
    delete this.root.dataset.boardBusy;
    if (!root || !fill) return;

    const visibleForMs = Date.now() - this._loadingVisibleSince;
    const remainingMs = Math.max(0, 320 - visibleForMs);

    fill.style.transitionDuration = '160ms';
    fill.style.transform = 'scaleX(1)';

    this._loadingResetTimer = window.setTimeout(() => {
      root.style.opacity = '0';
      fill.style.transitionDuration = '0ms';
      fill.style.transform = 'scaleX(0)';
      this._loadingResetTimer = null;
      this._loadingVisibleSince = 0;
    }, remainingMs + 160);
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
      case 'archived': return 'Archived';
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
      backlog: new Set(['todo', 'done', 'canceled', 'duplicated', 'archived']),
      todo: new Set(['backlog', 'in_progress', 'done', 'canceled', 'duplicated', 'archived']),
      in_progress: new Set(['human_review', 'done', 'canceled', 'duplicated', 'archived']),
      human_review: new Set(['rework', 'merging', 'done', 'canceled', 'duplicated', 'archived']),
      rework: new Set(['in_progress', 'done', 'canceled', 'duplicated', 'archived']),
      merging: new Set(['done', 'canceled', 'duplicated', 'archived']),
      done: new Set(['backlog', 'archived']),
      canceled: new Set(['backlog', 'archived']),
      duplicated: new Set(['archived']),
      archived: new Set(['backlog']),
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

}

Object.assign(AbIssuesBoard.prototype, createBoardSyncHooks({
  beforeRefresh() {
    this._beginLoading();
  },
  afterRefresh(landedIssueId) {
    this.bindDragDrop();
    this.bindQuickAdd();
    this.bindQuickDispatch();
    this._flashLandedCard(landedIssueId);
  },
  afterSettle() {
    this._endLoading();
  },
}));

if (!customElements.get('ab-issues-board')) {
  customElements.define('ab-issues-board', AbIssuesBoard);
}
