# Azure DevOps — spec-driven development with a worker pool

llm4zio can run spec-driven development end-to-end inside Azure DevOps: a card you move
through board states drives gemini to draft a spec, you approve it, and llm4zio implements
it to a pull request. This guide is the **deployment** half (pipelines, board, agent image,
secrets); the library + flow scripts live in `examples/ado-spec.sc` and
`examples/ado-implement.sc`.

Design rationale and the decisions behind it: `.claude/plans/azure-devops-integration.md`.

## How it works

```
card → Refine        ── poll ──▶  ado-spec.sc       drafts Acceptance Criteria, → Spec Review
   (human reviews/edits the Acceptance Criteria on the card, approves)
card → Approved      ── poll ──▶  ado-implement.sc  branch → tests → implement → PR, → In Review
   (PR gated by branch policies; merge → Done)
```

- **Workers = a self-hosted ADO agent pool.** Each poll dispatch runs one llm4zio flow on an
  agent, then the agent is free again. ADO's run queue + parallelism is the worker pool.
- **State lives in git** (branch `llm4zio/wi-<id>`, committed `specs/wi-<id>.md` +
  `.llm4zio/wi-<id>.md`), so an ephemeral agent and crash-resume both work — a re-run resumes.
- **The spec lives on the card** (`Microsoft.VSTS.Common.AcceptanceCriteria`), so humans edit
  it in Azure Boards and `ado-implement.sc` reads the edited version back as the contract.
- **Idempotency:** the dispatcher tags a card `llm4zio-claimed` before dispatching, so a card
  is never picked up twice concurrently.

## 1. Board configuration

Add these states to your work item type (Refine and Spec Review are the new ones; Approved /
In Review usually exist):

`New → Refine → Spec Review → Approved → In Review → Done`

`Acceptance Criteria` is a standard field on the Agile/Scrum/CMMI processes. On the Basic
process, add it (or set `LLM4ZIO_ADO_AC_FIELD` and adjust the script).

Put **branch policies** on your default branch (require a reviewer + build validation) — this
is the real merge gate; llm4zio only ever opens the PR.

## 2. Agent pool image

A self-hosted agent (container or VM) with:

- JDK 21, [scala-cli](https://scala-cli.virtuslab.org/)
- the [`gemini` CLI](https://github.com/google-gemini/gemini-cli), logged in (see §4)
- `git`, `mvn` (or whatever `LLM4ZIO_TEST_CMD` / `LLM4ZIO_BUILD_CMD` you set)

No `az` CLI is needed — `AdoTool` talks REST directly.

## 3. Dispatcher pipeline (scheduled poll)

A single scheduled pipeline polls the board, claims cards, and queues one worker run per card.

```yaml
# azure-pipelines-dispatch.yml
schedules:
  - cron: "*/10 * * * *"        # every 10 minutes
    displayName: llm4zio poll
    branches: { include: [ main ] }
    always: true

pool: { name: 'llm4zio-pool' }   # your self-hosted pool

steps:
  - checkout: self
    persistCredentials: true      # so git push is pre-authenticated via System.AccessToken
  - script: |
      set -euo pipefail
      # Refine → draft spec; Approved → implement. Skip cards already claimed.
      for STATE in Refine Approved; do
        IDS=$(az boards query --wiql \
          "SELECT [System.Id] FROM workitems \
           WHERE [System.State]='$STATE' AND [System.Tags] NOT CONTAINS 'llm4zio-claimed'" \
          --query "[].id" -o tsv)
        for ID in $IDS; do
          az boards work-item update --id "$ID" --fields "System.Tags=llm4zio-claimed" >/dev/null
          if [ "$STATE" = "Refine" ]; then SCRIPT=ado-spec.sc; else SCRIPT=ado-implement.sc; fi
          scala-cli run "examples/$SCRIPT" -- "$ID"
        done
      done
    displayName: Poll, claim, and run
    env:
      AZURE_DEVOPS_EXT_PAT: $(System.AccessToken)   # for the `az boards` claim calls
      SYSTEM_ACCESSTOKEN:   $(System.AccessToken)   # AdoTool + git push
      GEMINI_API_KEY:       $(GEMINI_API_KEY)       # see §4
```

> Runs flows inline for simplicity. To parallelise across the pool, replace the inner
> `scala-cli run` with `az pipelines run --name worker --parameters workItemId=$ID` and put the
> `scala-cli run` in a separate **worker** pipeline. The claim tag keeps either shape idempotent.

`SYSTEM_ACCESSTOKEN` is exposed only when *"Allow scripts to access the OAuth token"* is on for
the job (or via `env:` as above). Give the build identity **Contribute**, **Contribute to pull
requests**, and work-item read/write on the project.

## 4. Gemini credentials

Pick one; deferred at design time, it's a credential swap, not a redesign:

- **API key (simplest):** store `GEMINI_API_KEY` in a Key Vault-backed variable group, expose it
  as `env:` above. The CLI authenticates headlessly.
- **Vertex AI + Workload Identity Federation (hardening):** federate the ADO service connection
  to a GCP service account; set `GOOGLE_GENAI_USE_VERTEXAI=true` and ADC on the agent. No
  long-lived secret.

## 5. Configuration the scripts read

| Variable | Source in a pipeline | Meaning |
| --- | --- | --- |
| `SYSTEM_COLLECTIONURI` | predefined | `https://dev.azure.com/<org>` |
| `SYSTEM_TEAMPROJECT` | predefined | project name |
| `BUILD_REPOSITORY_NAME` | predefined | Git repo name |
| `SYSTEM_ACCESSTOKEN` | OAuth token | ADO REST auth (`AdoTool`) + git push |
| `GEMINI_API_KEY` | variable group | gemini CLI auth |
| `LLM4ZIO_TEST_CMD` / `LLM4ZIO_BUILD_CMD` | optional | test / build-compile gates (default `mvn`) |

For local runs outside a pipeline, set `LLM4ZIO_ADO_ORG_URL`, `LLM4ZIO_ADO_PROJECT`,
`LLM4ZIO_ADO_REPO`, and `LLM4ZIO_ADO_PAT` instead.
