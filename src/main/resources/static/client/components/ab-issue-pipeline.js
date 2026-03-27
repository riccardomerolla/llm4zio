function parseStepLines(raw) {
  return String(raw || '')
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .map((line) => {
      const parts = line.split('|');
      const agentId = String(parts[0] || '').trim();
      const promptOverride = String(parts[1] || '').trim();
      const continueFlag = String(parts[2] || '').trim().toLowerCase();
      return {
        agentId,
        promptOverride: promptOverride.length > 0 ? promptOverride : null,
        continueOnFailure: continueFlag === 'true' || continueFlag === '1' || continueFlag === 'yes',
      };
    })
    .filter((step) => step.agentId.length > 0);
}

async function callJson(url, method, body) {
  const response = await fetch(url, {
    method,
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await response.text();
  if (!response.ok) throw new Error(text || `${method} ${url} failed`);
  return text ? JSON.parse(text) : {};
}

function renderPipelines(select, pipelines) {
  if (!select) return;
  select.innerHTML = '';
  if (!Array.isArray(pipelines) || pipelines.length === 0) {
    const option = document.createElement('option');
    option.value = '';
    option.textContent = 'No pipelines';
    select.appendChild(option);
    return;
  }

  pipelines.forEach((pipeline) => {
    const option = document.createElement('option');
    option.value = pipeline.id;
    option.textContent = `${pipeline.name} (${Array.isArray(pipeline.steps) ? pipeline.steps.length : 0} steps)`;
    select.appendChild(option);
  });
}

function setOutput(node, value) {
  if (!node) return;
  node.textContent = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
}

async function initPipelineUi(root) {
  const issueId = root.dataset.issueId;
  const defaultWorkspaceId = root.dataset.workspaceId || '';

  const select = root.querySelector('[data-pipeline-select="true"]');
  const mode = root.querySelector('[data-pipeline-mode="true"]');
  const workspace = root.querySelector('[data-pipeline-workspace="true"]');
  const runButton = root.querySelector('[data-run-pipeline="true"]');

  const nameInput = root.querySelector('[data-pipeline-name="true"]');
  const stepsInput = root.querySelector('[data-pipeline-steps="true"]');
  const createButton = root.querySelector('[data-create-pipeline="true"]');
  const output = root.querySelector('[data-pipeline-output="true"]');

  if (workspace && !workspace.value && defaultWorkspaceId) {
    workspace.value = defaultWorkspaceId;
  }

  const refresh = async () => {
    try {
      const pipelines = await callJson('/api/pipelines', 'GET');
      renderPipelines(select, pipelines);
    } catch (err) {
      setOutput(output, `Failed to load pipelines: ${String(err)}`);
    }
  };

  if (createButton) {
    createButton.addEventListener('click', async () => {
      const name = String(nameInput?.value || '').trim();
      const steps = parseStepLines(stepsInput?.value || '');
      if (!name || steps.length === 0) {
        setOutput(output, 'Provide pipeline name and at least one step line.');
        return;
      }

      createButton.disabled = true;
      try {
        const created = await callJson('/api/pipelines', 'POST', { name, steps });
        setOutput(output, created);
        await refresh();
      } catch (err) {
        setOutput(output, `Create pipeline failed: ${String(err)}`);
      } finally {
        createButton.disabled = false;
      }
    });
  }

  if (runButton) {
    runButton.addEventListener('click', async () => {
      const pipelineId = String(select?.value || '').trim();
      const modeValue = String(mode?.value || 'Sequential').trim();
      const workspaceId = String(workspace?.value || '').trim();
      if (!pipelineId) {
        setOutput(output, 'Select a pipeline first.');
        return;
      }

      runButton.disabled = true;
      try {
        const result = await callJson(`/api/issues/${encodeURIComponent(issueId)}/run-pipeline`, 'POST', {
          pipelineId,
          mode: modeValue,
          workspaceId: workspaceId || null,
        });
        setOutput(output, result);
      } catch (err) {
        setOutput(output, `Run pipeline failed: ${String(err)}`);
      } finally {
        runButton.disabled = false;
      }
    });
  }

  await refresh();
}

document.querySelectorAll('[data-issue-pipeline-root="true"]').forEach((node) => {
  initPipelineUi(node);
});
