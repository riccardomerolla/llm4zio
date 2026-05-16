package config.boundary

import zio.test.*

/** Locks the SettingsValidator changes for the `pat.triage.*` keys
  * introduced for the Pat auto-triage daemon. Covers the prefix gate,
  * the checkbox normalisation, and the numeric validation.
  */
object SettingsValidatorPatSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("SettingsValidator — pat.triage.*")(
    test("pat.triage.enabled is allowed and normalises to true/false") {
      assertTrue(
        SettingsValidator.isAllowedKey("pat.triage.enabled"),
        SettingsValidator.validate("pat.triage.enabled", "on").contains("true"),
        SettingsValidator.validate("pat.triage.enabled", "off").contains("false"),
        SettingsValidator.validate("pat.triage.enabled", "").contains("false"),
      )
    },
    test("pat.triage.intervalSeconds rejects 0/negative; accepts positive integers") {
      assertTrue(
        SettingsValidator.validate("pat.triage.intervalSeconds", "60").contains("60"),
        SettingsValidator.validate("pat.triage.intervalSeconds", "0").isLeft,
        SettingsValidator.validate("pat.triage.intervalSeconds", "-1").isLeft,
        SettingsValidator.validate("pat.triage.intervalSeconds", "abc").isLeft,
      )
    },
    test("unknown pat.* key still accepted (prefix gate is lenient by design)") {
      assertTrue(
        SettingsValidator.isAllowedKey("pat.triage.somethingNew"),
        SettingsValidator.validate("pat.triage.somethingNew", "value").contains("value"),
      )
    },
  )
