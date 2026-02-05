package prompts

import models.CobolAnalysis

/** Prompt templates for dependency graph extraction
  *
  * Generates prompts for the DependencyMapperAgent to build dependency graphs from COBOL analysis data.
  *
  * Responsibilities:
  *   - Analyze COPY statements and program calls
  *   - Build dependency graph with nodes and edges
  *   - Calculate complexity metrics
  *   - Identify shared copybooks as service candidates
  *   - Generate DependencyGraph JSON
  *
  * Features:
  *   - Processes multiple COBOL analyses
  *   - Extracts CALL statements automatically
  *   - Identifies service candidates (3+ usage threshold)
  *   - Comprehensive few-shot examples
  */
object DependencyMapperPrompts:

  val version: String = "1.0.0"

  private val systemPrompt =
    """You are an expert in COBOL program dependency analysis and architecture mapping.
      |Your role is to build accurate dependency graphs from COBOL analysis data.
      |
      |CRITICAL REQUIREMENTS:
      |- Always respond with valid JSON only, no markdown, no explanations
      |- Identify ALL COPY statements as Include edges
      |- Identify ALL CALL statements as Call edges
      |- Calculate node complexity accurately
      |- Identify shared copybooks as service candidates (used by 3+ programs)
      |- Create unique node IDs (use program/copybook name as ID)
      |
      |TEMPLATE_VERSION: 1.0.0
      |""".stripMargin

  /** Extract dependency graph from multiple COBOL analyses
    *
    * @param analyses
    *   List of CobolAnalysis results
    * @return
    *   Formatted prompt for Gemini CLI
    */
  def extractDependencies(analyses: List[CobolAnalysis]): String =
    val analysesJson = analyses.map(formatAnalysisSummary).mkString(",\n")

    s"""$systemPrompt
       |
       |Build a complete dependency graph from these COBOL program analyses.
       |
       |ANALYSES (${analyses.length} programs):
       |[$analysesJson]
       |
       |${PromptHelpers.schemaReference("DependencyGraph")}
       |
       |${PromptHelpers.validationRules(
        List(
          "nodes (must include all programs and copybooks)",
          "edges (must capture all COPY and CALL relationships)",
          "serviceCandidates (copybooks used by 3+ programs)",
        )
      )}
       |
       |$fewShotExamples
       |
       |Build the graph:
       |1. NODES:
       |   - Create a node for each COBOL program (nodeType: Program)
       |   - Create a node for each unique copybook (nodeType: Copybook)
       |   - Set complexity from analysis.complexity.cyclomaticComplexity
       |   - Use program/copybook name as the node ID
       |
       |2. EDGES:
       |   - For each copybook in analysis.copybooks: create edge (program -> copybook, edgeType: Includes)
       |   - For each CALL statement in procedures: create edge (caller -> callee, edgeType: Calls)
       |   - Extract CALL targets from statement.content (e.g., "CALL 'SUBPROG'" -> edge to SUBPROG)
       |
       |3. SERVICE CANDIDATES:
       |   - Count how many programs use each copybook
       |   - If copybook is used by 3 or more programs, add to serviceCandidates
       |   - These represent shared data structures that should become shared services
       |
       |Respond with JSON only.
       |""".stripMargin

  /** Format analysis summary for inclusion in prompt
    *
    * @param analysis
    *   CobolAnalysis to format
    * @return
    *   JSON-formatted analysis summary
    */
  private def formatAnalysisSummary(analysis: CobolAnalysis): String =
    s"""{
       |  "programName": "${analysis.file.name}",
       |  "copybooks": [${analysis.copybooks.map(c => s""""$c"""").mkString(", ")}],
       |  "complexity": ${analysis.complexity.cyclomaticComplexity},
       |  "callStatements": [${extractCallStatements(analysis)}]
       |}""".stripMargin

  /** Extract CALL statements from procedures
    *
    * @param analysis
    *   CobolAnalysis containing procedures
    * @return
    *   Comma-separated JSON array of CALL statement contents
    */
  private def extractCallStatements(analysis: CobolAnalysis): String =
    val calls =
      for
        proc <- analysis.procedures
        stmt <- proc.statements
        if stmt.statementType == "CALL"
      yield s""""${stmt.content}""""
    calls.mkString(", ")

  private val fewShotExamples =
    s"""
       |${PromptHelpers.fewShotExample(
        "Simple dependency graph with shared copybook",
        """[
         |  {
         |    "programName": "CUSTPROG",
         |    "copybooks": ["CUSTREC", "ERRCODE"],
         |    "complexity": 5,
         |    "callStatements": ["CALL 'VALIDATE'"]
         |  },
         |  {
         |    "programName": "ORDERPROG",
         |    "copybooks": ["CUSTREC", "ORDERREC"],
         |    "complexity": 8,
         |    "callStatements": []
         |  },
         |  {
         |    "programName": "VALIDATE",
         |    "copybooks": ["ERRCODE"],
         |    "complexity": 3,
         |    "callStatements": []
         |  }
         |]""",
        """{
         |  "nodes": [
         |    { "id": "CUSTPROG", "name": "CUSTPROG", "nodeType": "Program", "complexity": 5 },
         |    { "id": "ORDERPROG", "name": "ORDERPROG", "nodeType": "Program", "complexity": 8 },
         |    { "id": "VALIDATE", "name": "VALIDATE", "nodeType": "Program", "complexity": 3 },
         |    { "id": "CUSTREC", "name": "CUSTREC", "nodeType": "Copybook", "complexity": 0 },
         |    { "id": "ERRCODE", "name": "ERRCODE", "nodeType": "Copybook", "complexity": 0 },
         |    { "id": "ORDERREC", "name": "ORDERREC", "nodeType": "Copybook", "complexity": 0 }
         |  ],
         |  "edges": [
         |    { "from": "CUSTPROG", "to": "CUSTREC", "edgeType": "Includes" },
         |    { "from": "CUSTPROG", "to": "ERRCODE", "edgeType": "Includes" },
         |    { "from": "CUSTPROG", "to": "VALIDATE", "edgeType": "Calls" },
         |    { "from": "ORDERPROG", "to": "CUSTREC", "edgeType": "Includes" },
         |    { "from": "ORDERPROG", "to": "ORDERREC", "edgeType": "Includes" },
         |    { "from": "VALIDATE", "to": "ERRCODE", "edgeType": "Includes" }
         |  ],
         |  "serviceCandidates": ["CUSTREC", "ERRCODE"]
         |}""",
      )}
       |
       |${PromptHelpers.fewShotExample(
        "Complex graph with multiple program calls",
        """[
         |  {
         |    "programName": "MAINPROG",
         |    "copybooks": ["SHARED"],
         |    "complexity": 10,
         |    "callStatements": ["CALL 'SUBPROG1'", "CALL 'SUBPROG2'"]
         |  },
         |  {
         |    "programName": "SUBPROG1",
         |    "copybooks": ["SHARED", "UTIL"],
         |    "complexity": 4,
         |    "callStatements": ["CALL 'LOGGER'"]
         |  },
         |  {
         |    "programName": "SUBPROG2",
         |    "copybooks": ["SHARED"],
         |    "complexity": 6,
         |    "callStatements": ["CALL 'LOGGER'"]
         |  },
         |  {
         |    "programName": "LOGGER",
         |    "copybooks": ["UTIL"],
         |    "complexity": 2,
         |    "callStatements": []
         |  }
         |]""",
        """{
         |  "nodes": [
         |    { "id": "MAINPROG", "name": "MAINPROG", "nodeType": "Program", "complexity": 10 },
         |    { "id": "SUBPROG1", "name": "SUBPROG1", "nodeType": "Program", "complexity": 4 },
         |    { "id": "SUBPROG2", "name": "SUBPROG2", "nodeType": "Program", "complexity": 6 },
         |    { "id": "LOGGER", "name": "LOGGER", "nodeType": "Program", "complexity": 2 },
         |    { "id": "SHARED", "name": "SHARED", "nodeType": "Copybook", "complexity": 0 },
         |    { "id": "UTIL", "name": "UTIL", "nodeType": "Copybook", "complexity": 0 }
         |  ],
         |  "edges": [
         |    { "from": "MAINPROG", "to": "SHARED", "edgeType": "Includes" },
         |    { "from": "MAINPROG", "to": "SUBPROG1", "edgeType": "Calls" },
         |    { "from": "MAINPROG", "to": "SUBPROG2", "edgeType": "Calls" },
         |    { "from": "SUBPROG1", "to": "SHARED", "edgeType": "Includes" },
         |    { "from": "SUBPROG1", "to": "UTIL", "edgeType": "Includes" },
         |    { "from": "SUBPROG1", "to": "LOGGER", "edgeType": "Calls" },
         |    { "from": "SUBPROG2", "to": "SHARED", "edgeType": "Includes" },
         |    { "from": "SUBPROG2", "to": "LOGGER", "edgeType": "Calls" },
         |    { "from": "LOGGER", "to": "UTIL", "edgeType": "Includes" }
         |  ],
         |  "serviceCandidates": ["SHARED", "UTIL"]
         |}""",
      )}
       |
       |${PromptHelpers.fewShotExample(
        "Graph with isolated programs and no shared copybooks",
        """[
         |  {
         |    "programName": "STANDALONE1",
         |    "copybooks": ["LOCAL1"],
         |    "complexity": 3,
         |    "callStatements": []
         |  },
         |  {
         |    "programName": "STANDALONE2",
         |    "copybooks": ["LOCAL2"],
         |    "complexity": 5,
         |    "callStatements": []
         |  }
         |]""",
        """{
         |  "nodes": [
         |    { "id": "STANDALONE1", "name": "STANDALONE1", "nodeType": "Program", "complexity": 3 },
         |    { "id": "STANDALONE2", "name": "STANDALONE2", "nodeType": "Program", "complexity": 5 },
         |    { "id": "LOCAL1", "name": "LOCAL1", "nodeType": "Copybook", "complexity": 0 },
         |    { "id": "LOCAL2", "name": "LOCAL2", "nodeType": "Copybook", "complexity": 0 }
         |  ],
         |  "edges": [
         |    { "from": "STANDALONE1", "to": "LOCAL1", "edgeType": "Includes" },
         |    { "from": "STANDALONE2", "to": "LOCAL2", "edgeType": "Includes" }
         |  ],
         |  "serviceCandidates": []
         |}""",
      )}
       |""".stripMargin
