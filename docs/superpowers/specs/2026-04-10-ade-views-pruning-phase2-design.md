# ADE Views Pruning Phase 2 — Governance + Daemons → Settings Tabs

## Goal

Move Governance and Daemons from standalone pages to tabs within the Settings page, then remove them as standalone nav items. This consolidates admin-oriented pages into a single Settings hub.

## Architecture

Both views currently call `Layout.page(...)` to render standalone pages. We change them to use a shared settings tab shell.

**Key constraint:** GovernanceView is in `governance-domain` module, which doesn't depend on `shared-web` (where `SettingsView` lives). Solution: extract the tab shell into `shared-web-core`, which all domain modules already depend on.

## Changes

### New: SettingsShell in shared-web-core

Create `modules/shared-web-core/src/main/scala/shared/web/SettingsShell.scala`:
- Contains the tab list (9 tabs: AI Models, Channels, Gateway, Issue Templates, Governance, Daemons, System, Advanced Config, Demo)
- Provides `page(activeTab, pageTitle)(bodyContent*)` that wraps content in `Layout.page` with the tab bar
- All domain modules can use this since they depend on `shared-web-core`

### Migrate: SettingsView

`modules/shared-web/src/main/scala/shared/web/SettingsView.scala`:
- Replace inline `settingsShell` method with delegation to `SettingsShell.page`
- Remove the `tabs` list (now in SettingsShell)
- All existing tab content methods unchanged

### Migrate: GovernanceView + Controller

`modules/governance-domain/src/main/scala/governance/boundary/GovernanceView.scala`:
- Change `Layout.page("Governance", "/governance")` → `SettingsShell.page("governance", "Settings — Governance")`
- Remove standalone page header (the settings shell provides context)

`modules/governance-domain/src/main/scala/governance/boundary/GovernanceController.scala`:
- Change route from `GET /governance` to `GET /settings/governance`

### Migrate: DaemonsView + Controller

`modules/daemon-domain/src/main/scala/daemon/boundary/DaemonsView.scala`:
- Change `Layout.page(...)` → `SettingsShell.page("daemons", "Settings — Daemons")`

`src/main/scala/daemon/boundary/DaemonsController.scala`:
- Change routes from `/daemons/*` to `/settings/daemons/*`
- Action redirects change from `/daemons` to `/settings/daemons`

### Update: AdeRouteModule

`src/main/scala/app/boundary/AdeRouteModule.scala`:
- Remove GovernanceController routes and GovernancePolicyRepository dependency
- Remove DaemonsController routes and dependency
- Governance routes now served by GovernanceController directly (included via ApplicationDI)
- Daemons routes now served by DaemonsController directly

### Update: Layout.scala navigation

`modules/shared-web-core/src/main/scala/shared/web/Layout.scala`:
- Remove `adeGroup` entirely (after removing Governance, Daemons, only SDLC Dashboard and Board remain — both already exist in coreGatewayGroup)
- Remove the ADE dropdown from the top nav bar
- Settings nav item activePredicate already covers `/settings/*` paths

### Update: ApplicationDI

`src/main/scala/app/ApplicationDI.scala`:
- Adjust route wiring: governance and daemons routes must still be included in the HTTP app, just not via AdeRouteModule

### Update: Tests

- Update LayoutSpec: remove adeGroup assertions, ADE dropdown assertions
- Update any test referencing `/governance` or `/daemons` URLs

## URL Changes

| Before | After |
|--------|-------|
| `/governance` | `/settings/governance` |
| `/daemons` | `/settings/daemons` |
| `/daemons/{id}/start` | `/settings/daemons/{id}/start` |
| `/daemons/{id}/stop` | `/settings/daemons/{id}/stop` |
| `/daemons/{id}/restart` | `/settings/daemons/{id}/restart` |
| `/daemons/{id}/enable` | `/settings/daemons/{id}/enable` |
| `/daemons/{id}/disable` | `/settings/daemons/{id}/disable` |

## Tab Order (9 tabs)

AI Models, Channels, Gateway, Issue Templates, Governance, Daemons, System, Advanced Config, Demo
