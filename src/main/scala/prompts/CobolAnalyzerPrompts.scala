package prompts

import models.CobolFile

/** Prompt templates for COBOL structure analysis
  *
  * Generates prompts for the CobolAnalyzerAgent to perform deep structural analysis of COBOL programs using Gemini CLI.
  *
  * Responsibilities:
  *   - Parse COBOL divisions (IDENTIFICATION, ENVIRONMENT, DATA, PROCEDURE)
  *   - Extract variables, data structures, and types
  *   - Identify control flow (IF, PERFORM, GOTO, EVALUATE statements)
  *   - Detect copybook dependencies
  *   - Calculate complexity metrics
  *   - Generate structured CobolAnalysis JSON
  *
  * Features:
  *   - Smart chunking for large files (division-based splitting)
  *   - Comprehensive few-shot examples
  *   - Strict validation requirements
  *   - Version tracking
  */
object CobolAnalyzerPrompts:

  val version: String = "1.0.0"

  private val systemPrompt =
    """You are an expert COBOL program analyzer with deep knowledge of mainframe COBOL syntax.
      |Your role is to perform precise structural analysis of COBOL programs.
      |
      |CRITICAL REQUIREMENTS:
      |- Always respond with valid JSON only, no markdown, no explanations
      |- Extract ALL variables, procedures, and copybook references
      |- Calculate accurate complexity metrics
      |- Identify all control flow statements (IF, PERFORM, GOTO, EVALUATE)
      |- Parse PIC clauses correctly for data types
      |
      |TEMPLATE_VERSION: 1.0.0
      |""".stripMargin

  /** Analyze complete COBOL program structure
    *
    * Entry point that decides whether to analyze the file in one shot or chunk it by divisions.
    *
    * @param cobolFile
    *   Metadata about the COBOL file
    * @param cobolCode
    *   Complete COBOL source code
    * @return
    *   Formatted prompt for Gemini CLI
    */
  def analyzeStructure(cobolFile: CobolFile, cobolCode: String): String =
    val chunks = PromptHelpers.chunkByDivision(cobolCode)

    if chunks.size == 1 || cobolCode.length < 10000 then
      // Small file - analyze in one shot
      analyzeComplete(cobolFile, cobolCode)
    else
      // Large file - analyze by division (chunked approach)
      analyzeChunked(cobolFile, chunks)

  /** Analyze complete COBOL file in a single prompt (for small files)
    *
    * @param cobolFile
    *   File metadata
    * @param cobolCode
    *   Complete COBOL source
    * @return
    *   Complete analysis prompt
    */
  private def analyzeComplete(cobolFile: CobolFile, cobolCode: String): String =
    s"""$systemPrompt
       |
       |Analyze this COBOL program and extract its complete structure.
       |
       |FILE: ${cobolFile.name}
       |${PromptHelpers.formatCobolCode(cobolCode)}
       |
       |${PromptHelpers.schemaReference("CobolAnalysis")}
       |
       |${PromptHelpers.validationRules(
        List(
          "divisions",
          "variables",
          "procedures",
          "copybooks",
          "complexity",
        )
      )}
       |
       |$fewShotExamples
       |
       |Extract:
       |1. IDENTIFICATION DIVISION - program-id, author, date-written
       |2. ENVIRONMENT DIVISION - file controls, special-names
       |3. DATA DIVISION:
       |   - WORKING-STORAGE: All 01-49 level variables with PIC clauses
       |   - FILE SECTION: File descriptions (FD)
       |   - LINKAGE SECTION: External parameters
       |4. PROCEDURE DIVISION:
       |   - All paragraph and section names
       |   - Statement types (MOVE, COMPUTE, IF, PERFORM, etc.)
       |   - Control flow (PERFORM targets, GO TO targets)
       |5. COPY statements - extract all copybook names
       |6. Complexity metrics:
       |   - cyclomaticComplexity: Count decision points (IF, EVALUATE, PERFORM UNTIL)
       |   - linesOfCode: Total non-comment lines
       |   - numberOfProcedures: Count of paragraphs/sections
       |
       |Respond with JSON only.
       |""".stripMargin

  /** Analyze large COBOL file using division-based chunking
    *
    * For files over 10K characters, analyze each division separately and request combined output.
    *
    * @param cobolFile
    *   File metadata
    * @param chunks
    *   Map of division name to division content
    * @return
    *   Chunked analysis prompt
    */
  private def analyzeChunked(cobolFile: CobolFile, chunks: Map[String, String]): String =
    val divisionsFormatted = chunks
      .map {
        case (name, content) =>
          s"""
           |### $name DIVISION:
           |${PromptHelpers.formatCobolCode(content)}
           |""".stripMargin
      }
      .mkString("\n")

    s"""$systemPrompt
       |
       |Analyze this COBOL program by divisions. The file is large, so divisions are provided separately.
       |Combine your analysis across all divisions into a single CobolAnalysis JSON response.
       |
       |FILE: ${cobolFile.name}
       |
       |$divisionsFormatted
       |
       |${PromptHelpers.schemaReference("CobolAnalysis")}
       |
       |${PromptHelpers.validationRules(
        List(
          "divisions",
          "variables",
          "procedures",
          "copybooks",
          "complexity",
        )
      )}
       |
       |Extract from each division:
       |1. IDENTIFICATION: program-id, author, date-written
       |2. ENVIRONMENT: file controls, special-names
       |3. DATA: All variables from WORKING-STORAGE, FILE SECTION, LINKAGE
       |4. PROCEDURE: All paragraphs, sections, statements, control flow
       |5. COPY statements across all divisions
       |6. Calculate overall complexity metrics
       |
       |Respond with JSON only.
       |""".stripMargin

  private val fewShotExamples =
    s"""
       |${PromptHelpers.fewShotExample(
        "Simple COBOL program with file I/O",
        """IDENTIFICATION DIVISION.
         |PROGRAM-ID. CUSTPROG.
         |AUTHOR. JOHN DOE.
         |
         |DATA DIVISION.
         |WORKING-STORAGE SECTION.
         |01 WS-CUSTOMER-ID    PIC 9(5).
         |01 WS-CUSTOMER-NAME  PIC X(30).
         |01 WS-BALANCE        PIC 9(7)V99.
         |
         |PROCEDURE DIVISION.
         |MAIN-PARA.
         |    MOVE 12345 TO WS-CUSTOMER-ID.
         |    IF WS-BALANCE > 1000
         |       PERFORM CREDIT-CHECK
         |    END-IF.
         |    STOP RUN.
         |
         |CREDIT-CHECK.
         |    DISPLAY 'Credit check for ' WS-CUSTOMER-NAME.
         |""",
        """{
         |  "file": { "name": "CUSTPROG.cbl", "path": "/cobol/CUSTPROG.cbl", "size": 512, "lastModified": "2026-01-15T10:00:00Z", "encoding": "UTF-8", "fileType": "Program" },
         |  "divisions": {
         |    "identification": "PROGRAM-ID. CUSTPROG. AUTHOR. JOHN DOE.",
         |    "environment": null,
         |    "data": "WORKING-STORAGE SECTION. 01 WS-CUSTOMER-ID...",
         |    "procedure": "MAIN-PARA. MOVE 12345..."
         |  },
         |  "variables": [
         |    { "name": "WS-CUSTOMER-ID", "level": 1, "dataType": "numeric", "picture": "9(5)", "usage": null },
         |    { "name": "WS-CUSTOMER-NAME", "level": 1, "dataType": "alphanumeric", "picture": "X(30)", "usage": null },
         |    { "name": "WS-BALANCE", "level": 1, "dataType": "numeric", "picture": "9(7)V99", "usage": null }
         |  ],
         |  "procedures": [
         |    {
         |      "name": "MAIN-PARA",
         |      "paragraphs": ["MAIN-PARA"],
         |      "statements": [
         |        { "lineNumber": 11, "statementType": "MOVE", "content": "MOVE 12345 TO WS-CUSTOMER-ID" },
         |        { "lineNumber": 12, "statementType": "IF", "content": "IF WS-BALANCE > 1000" },
         |        { "lineNumber": 13, "statementType": "PERFORM", "content": "PERFORM CREDIT-CHECK" },
         |        { "lineNumber": 15, "statementType": "STOP", "content": "STOP RUN" }
         |      ]
         |    },
         |    {
         |      "name": "CREDIT-CHECK",
         |      "paragraphs": ["CREDIT-CHECK"],
         |      "statements": [
         |        { "lineNumber": 17, "statementType": "DISPLAY", "content": "DISPLAY 'Credit check for ' WS-CUSTOMER-NAME" }
         |      ]
         |    }
         |  ],
         |  "copybooks": [],
         |  "complexity": {
         |    "cyclomaticComplexity": 2,
         |    "linesOfCode": 17,
         |    "numberOfProcedures": 2
         |  }
         |}""",
      )}
       |
       |${PromptHelpers.fewShotExample(
        "COBOL with copybooks and file I/O",
        """IDENTIFICATION DIVISION.
         |PROGRAM-ID. FILEPROC.
         |
         |ENVIRONMENT DIVISION.
         |INPUT-OUTPUT SECTION.
         |FILE-CONTROL.
         |    SELECT CUSTOMER-FILE ASSIGN TO 'CUSTFILE'
         |        ORGANIZATION IS SEQUENTIAL.
         |
         |DATA DIVISION.
         |FILE SECTION.
         |FD CUSTOMER-FILE.
         |01 CUSTOMER-RECORD.
         |   COPY CUSTREC.
         |
         |WORKING-STORAGE SECTION.
         |   COPY ERRCODE.
         |01 WS-EOF-FLAG       PIC X VALUE 'N'.
         |
         |PROCEDURE DIVISION.
         |MAIN-PROCESS.
         |    OPEN INPUT CUSTOMER-FILE.
         |    PERFORM READ-RECORDS UNTIL WS-EOF-FLAG = 'Y'.
         |    CLOSE CUSTOMER-FILE.
         |    STOP RUN.
         |
         |READ-RECORDS.
         |    READ CUSTOMER-FILE
         |        AT END MOVE 'Y' TO WS-EOF-FLAG
         |    END-READ.
         |""",
        """{
         |  "file": { "name": "FILEPROC.cbl", "path": "/cobol/FILEPROC.cbl", "size": 1024, "lastModified": "2026-01-15T11:00:00Z", "encoding": "UTF-8", "fileType": "Program" },
         |  "divisions": {
         |    "identification": "PROGRAM-ID. FILEPROC.",
         |    "environment": "INPUT-OUTPUT SECTION. FILE-CONTROL. SELECT CUSTOMER-FILE...",
         |    "data": "FILE SECTION. FD CUSTOMER-FILE...",
         |    "procedure": "MAIN-PROCESS. OPEN INPUT..."
         |  },
         |  "variables": [
         |    { "name": "CUSTOMER-RECORD", "level": 1, "dataType": "group", "picture": null, "usage": null },
         |    { "name": "WS-EOF-FLAG", "level": 1, "dataType": "alphanumeric", "picture": "X", "usage": null }
         |  ],
         |  "procedures": [
         |    {
         |      "name": "MAIN-PROCESS",
         |      "paragraphs": ["MAIN-PROCESS"],
         |      "statements": [
         |        { "lineNumber": 20, "statementType": "OPEN", "content": "OPEN INPUT CUSTOMER-FILE" },
         |        { "lineNumber": 21, "statementType": "PERFORM", "content": "PERFORM READ-RECORDS UNTIL WS-EOF-FLAG = 'Y'" },
         |        { "lineNumber": 22, "statementType": "CLOSE", "content": "CLOSE CUSTOMER-FILE" },
         |        { "lineNumber": 23, "statementType": "STOP", "content": "STOP RUN" }
         |      ]
         |    },
         |    {
         |      "name": "READ-RECORDS",
         |      "paragraphs": ["READ-RECORDS"],
         |      "statements": [
         |        { "lineNumber": 26, "statementType": "READ", "content": "READ CUSTOMER-FILE AT END MOVE 'Y' TO WS-EOF-FLAG" }
         |      ]
         |    }
         |  ],
         |  "copybooks": ["CUSTREC", "ERRCODE"],
         |  "complexity": {
         |    "cyclomaticComplexity": 2,
         |    "linesOfCode": 26,
         |    "numberOfProcedures": 2
         |  }
         |}""",
      )}
       |
       |${PromptHelpers.fewShotExample(
        "Complex COBOL with EVALUATE and nested IFs",
        """IDENTIFICATION DIVISION.
         |PROGRAM-ID. COMPLEX.
         |
         |DATA DIVISION.
         |WORKING-STORAGE SECTION.
         |01 WS-STATUS-CODE    PIC 99.
         |01 WS-AMOUNT         PIC 9(7)V99.
         |01 WS-RESULT         PIC X(20).
         |
         |PROCEDURE DIVISION.
         |PROCESS-STATUS.
         |    EVALUATE WS-STATUS-CODE
         |        WHEN 10
         |            MOVE 'ACTIVE' TO WS-RESULT
         |        WHEN 20
         |            IF WS-AMOUNT > 5000
         |                MOVE 'HIGH-VALUE' TO WS-RESULT
         |            ELSE
         |                MOVE 'MEDIUM-VALUE' TO WS-RESULT
         |            END-IF
         |        WHEN 30
         |            MOVE 'INACTIVE' TO WS-RESULT
         |        WHEN OTHER
         |            MOVE 'UNKNOWN' TO WS-RESULT
         |    END-EVALUATE.
         |    STOP RUN.
         |""",
        """{
         |  "file": { "name": "COMPLEX.cbl", "path": "/cobol/COMPLEX.cbl", "size": 768, "lastModified": "2026-01-15T12:00:00Z", "encoding": "UTF-8", "fileType": "Program" },
         |  "divisions": {
         |    "identification": "PROGRAM-ID. COMPLEX.",
         |    "environment": null,
         |    "data": "WORKING-STORAGE SECTION. 01 WS-STATUS-CODE...",
         |    "procedure": "PROCESS-STATUS. EVALUATE WS-STATUS-CODE..."
         |  },
         |  "variables": [
         |    { "name": "WS-STATUS-CODE", "level": 1, "dataType": "numeric", "picture": "99", "usage": null },
         |    { "name": "WS-AMOUNT", "level": 1, "dataType": "numeric", "picture": "9(7)V99", "usage": null },
         |    { "name": "WS-RESULT", "level": 1, "dataType": "alphanumeric", "picture": "X(20)", "usage": null }
         |  ],
         |  "procedures": [
         |    {
         |      "name": "PROCESS-STATUS",
         |      "paragraphs": ["PROCESS-STATUS"],
         |      "statements": [
         |        { "lineNumber": 10, "statementType": "EVALUATE", "content": "EVALUATE WS-STATUS-CODE" },
         |        { "lineNumber": 11, "statementType": "WHEN", "content": "WHEN 10" },
         |        { "lineNumber": 12, "statementType": "MOVE", "content": "MOVE 'ACTIVE' TO WS-RESULT" },
         |        { "lineNumber": 13, "statementType": "WHEN", "content": "WHEN 20" },
         |        { "lineNumber": 14, "statementType": "IF", "content": "IF WS-AMOUNT > 5000" },
         |        { "lineNumber": 15, "statementType": "MOVE", "content": "MOVE 'HIGH-VALUE' TO WS-RESULT" },
         |        { "lineNumber": 17, "statementType": "MOVE", "content": "MOVE 'MEDIUM-VALUE' TO WS-RESULT" },
         |        { "lineNumber": 19, "statementType": "WHEN", "content": "WHEN 30" },
         |        { "lineNumber": 20, "statementType": "MOVE", "content": "MOVE 'INACTIVE' TO WS-RESULT" },
         |        { "lineNumber": 21, "statementType": "WHEN", "content": "WHEN OTHER" },
         |        { "lineNumber": 22, "statementType": "MOVE", "content": "MOVE 'UNKNOWN' TO WS-RESULT" },
         |        { "lineNumber": 24, "statementType": "STOP", "content": "STOP RUN" }
         |      ]
         |    }
         |  ],
         |  "copybooks": [],
         |  "complexity": {
         |    "cyclomaticComplexity": 5,
         |    "linesOfCode": 24,
         |    "numberOfProcedures": 1
         |  }
         |}""",
      )}
       |""".stripMargin
