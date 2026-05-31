# Plan

## Group 1: WebJar Runtime Asset Plumbing
- [x] Add pinned WebJar dependencies for the browser libraries currently loaded from public CDNs.
- [x] Serve WebJar resources from the application under a local `/webjars` URL path.
- [x] Keep the existing `/static` route aligned with the actual static resource directory.

## Group 2: Local Library References
- [x] Centralize local browser library URLs in shared web resources.
- [x] Replace Scala-rendered CDN script and stylesheet references with local WebJar URLs.
- [x] Replace web component CDN imports with local browser module URLs.

## Group 3: Regression Coverage and Validation
- [x] Add tests that fail when runtime views or browser assets reference public CDN hosts.
- [x] Run formatting, compile, and focused tests.
- [x] Review the implementation and apply any remarks.
- [x] Mark all completed tasks in this plan.
