package io.github.riccardomerolla.zio.quickstart.error

enum QuickstartError:
  case InvalidAudience(input: String, reason: String)
  case InvalidTemplate(template: String, reason: String)
