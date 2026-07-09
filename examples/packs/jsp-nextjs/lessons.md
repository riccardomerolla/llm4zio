- Server-side validation messages in legacy JSP apps are contract, not copy: port
  them verbatim into the SPA and assert them verbatim in tests — "close enough"
  wording breaks screen-scraping downstream consumers and user muscle memory.
- Hidden form fields and session attributes are the legacy app's state model: make
  each one an explicit, named piece of client state before porting any screen that
  touches it.
