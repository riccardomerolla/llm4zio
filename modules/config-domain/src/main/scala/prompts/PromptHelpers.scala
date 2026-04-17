package prompts

/** Shared utilities for prompt template construction
  *
  * Provides common functions for:
  *   - COBOL code formatting
  *   - Division-based chunking for large files
  *   - Schema reference generation
  *   - Validation rule formatting
  *   - Few-shot example formatting
  *   - Token estimation
  */
object PromptHelpers:

  /** Format COBOL code for inclusion in prompts with markdown code blocks
    *
    * @param code
    *   COBOL source code
    * @return
    *   Formatted code block
    */
  def formatCobolCode(code: String): String =
    s"""```cobol
       |$code
       |```""".stripMargin

  /** Generate JSON schema documentation reference from case class name
    *
    * @param className
    *   Name of the case class (e.g., "CobolAnalysis")
    * @return
    *   Schema reference instructions for Gemini
    */
  def schemaReference(className: String): String =
    s"Respond with valid JSON matching the $className schema."

  /** Build chunked context for large COBOL files by splitting on divisions
    *
    * @param cobolCode
    *   Complete COBOL source code
    * @return
    *   Map of division name to division content
    */
  def chunkByDivision(cobolCode: String): Map[String, String] =
    val divisions = Map(
      "IDENTIFICATION" -> extractDivision(cobolCode, "IDENTIFICATION DIVISION"),
      "ENVIRONMENT"    -> extractDivision(cobolCode, "ENVIRONMENT DIVISION"),
      "DATA"           -> extractDivision(cobolCode, "DATA DIVISION"),
      "PROCEDURE"      -> extractDivision(cobolCode, "PROCEDURE DIVISION"),
    )
    divisions.filter(_._2.nonEmpty)

  /** Extract a specific COBOL division from source code
    *
    * Finds content between "{DIVISION_NAME} DIVISION." and the next division or end of file
    *
    * @param code
    *   Complete COBOL source code
    * @param divisionName
    *   Name of division to extract (e.g., "IDENTIFICATION DIVISION")
    * @return
    *   Division content or empty string if not found
    */
  private def extractDivision(code: String, divisionName: String): String =
    val lines           = code.linesIterator.toList
    val divisionPattern = s"$divisionName\\.".r

    // Find start index of this division
    val startIdx = lines.indexWhere(line => divisionPattern.findFirstIn(line.trim.toUpperCase).isDefined)

    if startIdx == -1 then
      ""
    else
      // Find end index (next division or end of file)
      val nextDivisionIdx = lines.drop(startIdx + 1).indexWhere { line =>
        val upper = line.trim.toUpperCase
        upper.contains("DIVISION.") && !upper.startsWith("*")
      }

      val endIdx = if nextDivisionIdx == -1 then lines.length else startIdx + 1 + nextDivisionIdx

      lines.slice(startIdx, endIdx).mkString("\n")

  /** Format strict validation requirements for required fields
    *
    * @param fields
    *   List of required field names
    * @return
    *   Formatted validation rules text
    */
  def validationRules(fields: List[String]): String =
    val rules = fields.map(f => s"  - $f is REQUIRED and cannot be null or empty")
    s"""
       |STRICT VALIDATION REQUIREMENTS:
       |${rules.mkString("\n")}
       |
       |If any required field is missing or invalid, the response will be rejected.
       |""".stripMargin

  /** Create a few-shot example block with consistent formatting
    *
    * @param description
    *   Brief description of what this example demonstrates
    * @param input
    *   Example input (COBOL code, analysis data, etc.)
    * @param output
    *   Expected JSON output
    * @return
    *   Formatted example block
    */
  def fewShotExample(description: String, input: String, output: String): String =
    s"""
       |---
       |EXAMPLE: $description
       |
       |Input:
       |$input
       |
       |Expected Output:
       |$output
       |---
       |""".stripMargin

  /** Estimate token count for chunking decisions (rough heuristic)
    *
    * Uses approximation of ~4 characters per token
    *
    * @param text
    *   Text to estimate tokens for
    * @return
    *   Estimated token count
    */
  def estimateTokens(text: String): Int =
    // Rough estimate: ~4 characters per token
    text.length / 4

  /** Check if COBOL code should be chunked based on estimated token size
    *
    * @param cobolCode
    *   COBOL source code
    * @param maxTokens
    *   Maximum tokens before chunking (default 8000)
    * @return
    *   True if code should be chunked
    */
  def shouldChunk(cobolCode: String, maxTokens: Int = 8000): Boolean =
    estimateTokens(cobolCode) > maxTokens
