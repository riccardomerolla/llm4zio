package models

object TaskStep:
  val Discovery: TaskStep      = "Discovery"
  val Analysis: TaskStep       = "Analysis"
  val Mapping: TaskStep        = "Mapping"
  val Transformation: TaskStep = "Transformation"
  val Validation: TaskStep     = "Validation"
  val Documentation: TaskStep  = "Documentation"

  val values: List[TaskStep] =
    List(Discovery, Analysis, Mapping, Transformation, Validation, Documentation)
