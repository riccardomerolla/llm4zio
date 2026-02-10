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

  val version: String = "1.1.0"

  private val systemPrompt =
    """You are an expert COBOL program analyzer with deep knowledge of mainframe COBOL syntax,
      |including CICS, VSAM, and DB2 programs.
      |Your role is to perform precise structural analysis of COBOL programs.
      |
      |Analyze the provided COBOL program and provide a detailed, structured analysis that includes:
      |
      |1. Overall program description
      |2. Data divisions and their purpose
      |3. Procedure divisions and their purpose
      |4. Variables (name, level, type, size, group structure)
      |5. Paragraphs/sections (name, description, logic, variables used, paragraphs called)
      |6. Copybooks referenced
      |7. File access (file name, mode, verbs used, status variable, FD linkage)
      |8. Any embedded SQL or DB2 statements (type, purpose, variables used)
      |9. Any embedded CICS statements (type, purpose, variables used)
      |
      |Your analysis should be structured in a way that can be easily parsed by a Java conversion system.
      |
      |
      |🚨 CRITICAL RESPONSE FORMAT:
      |- Your response MUST be a SINGLE JSON OBJECT starting with '{' and ending with '}'
      |- DO NOT return a JSON ARRAY [...] — we need ONE object {...} for ONE COBOL file
      |- NO markdown code blocks (```) — return raw JSON only
      |- NO explanations before or after the JSON
      |- Start your response with '{' (opening brace) immediately
      |
      |CRITICAL REQUIREMENTS:
      |- Always respond with valid, complete JSON only — no markdown, no explanations, no trailing text
      |- The JSON response MUST be well-formed: every opening brace/bracket must have a matching closing brace/bracket
      |- Do NOT truncate the output — return the entire JSON object even if it is large
      |- Verify that your JSON is syntactically valid before responding
      |- Extract ALL variables, procedures, and copybook references
      |- Calculate accurate complexity metrics
      |- Identify all control flow statements (IF, PERFORM, GOTO, EVALUATE)
      |- Parse PIC clauses correctly for data types
      |- Handle CICS programs: EXEC CICS statements are statement type "EXEC-CICS"
      |- Handle level 77 variables (independent items) — they have their own level number
      |- Handle level 88 condition names
      |- The "divisions" field MUST be a JSON object (not an array)
      |- The "copybooks" field MUST be a flat array of strings (not objects)
      |- Every variable MUST include "level" (int), "dataType" (string), and "name" (string)
      |- Every statement MUST include "lineNumber" (int), "statementType" (string), and "content" (string with the full COBOL statement text)
      |
      |CRITICAL - DIVISIONS FORMAT:
      |- The "divisions" object values MUST be plain COBOL text strings, NOT nested JSON objects
      |- DO NOT structure divisions as JSON objects with keys like {"programId": "...", ...}
      |- DO NOT structure divisions as JSON with {"paragraphs": [], "statements": [], ...}
      |- Instead, each division value should be the RAW COBOL text from that division
      |- Example: "identification": "PROGRAM-ID. ZBANK. AUTHOR. JOHN DOE."
      |- Example: "procedure": "MOVE X TO Y. IF A = B THEN..." (raw text, not structured JSON)
      |
      |CRITICAL - DATA EXTRACTION:
      |- Variables MUST be extracted to the TOP-LEVEL "variables" array, NOT embedded in divisions
      |- Procedures MUST be extracted to the TOP-LEVEL "procedures" array, NOT embedded in divisions
      |- For programs without named paragraphs (inline code), create a procedure named "MAIN-LOGIC" or "PROCEDURE-DIVISION"
      |- ALL statements must be in the procedures[].statements array
      |
      |TEMPLATE_VERSION: 1.1.0
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
       |   - WORKING-STORAGE: All variables (levels 01-49, 77, 88) with PIC clauses
       |   - FILE SECTION: File descriptions (FD)
       |   - LINKAGE SECTION: External parameters
       |   - For each variable, extract: name, level number, dataType (numeric/alphanumeric/group), picture clause
       |4. PROCEDURE DIVISION:
       |   - All paragraph and section names
       |   - Statement types: MOVE, COMPUTE, IF, PERFORM, EVALUATE, DISPLAY, READ, WRITE, OPEN, CLOSE, STOP
       |   - CICS statements: Use statementType "EXEC-CICS" with full EXEC CICS ... END-EXEC as content
       |   - Control flow (PERFORM targets, GO TO targets)
       |   - Each statement MUST have: lineNumber (approximate line in source), statementType, content (the full COBOL text)
       |5. COPY statements - extract all copybook names as a flat list of strings: ["COPY1", "COPY2"]
       |6. Complexity metrics:
       |   - cyclomaticComplexity: Count decision points (IF, EVALUATE, PERFORM UNTIL, EXEC CICS HANDLE)
       |   - linesOfCode: Total non-comment lines
       |   - numberOfProcedures: Count of paragraphs/sections
       |
       |━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
       |🎯 YOUR RESPONSE FORMAT:
       |━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
       |
       |Return a SINGLE JSON OBJECT (not an array):
       |
       |{
       |  "file": {...},
       |  "divisions": {...},
       |  "variables": [...],
       |  "procedures": [...],
       |  "copybooks": [...],
       |  "complexity": {...}
       |}
       |
       |⚠️  Start with '{' — NOT with '['
       |⚠️  End with '}' — NOT with ']'
       |⚠️  ONE object for ONE file
       |
       |Respond with the JSON object now (no markdown, no array, no explanation).
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
       |Combine analysis across all divisions into one complete CobolAnalysis JSON response.
       |
       |━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
       |🎯 YOUR RESPONSE FORMAT:
       |━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
       |
       |Return a SINGLE JSON OBJECT (not an array):
       |
       |{
       |  "file": {...},
       |  "divisions": {...},
       |  "variables": [...],
       |  "procedures": [...],
       |  "copybooks": [...],
       |  "complexity": {...}
       |}
       |
       |⚠️  Start with '{' — NOT with '['
       |⚠️  End with '}' — NOT with ']'
       |⚠️  ONE object for ONE file
       |
       |Respond with the JSON object now (no markdown, no array, no explanation).
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
       |
       |${PromptHelpers.fewShotExample(
        "CICS COBOL program with named paragraphs",
        """PROGRAM-ID. ZBANK.
         |DATA DIVISION.
         |WORKING-STORAGE SECTION.
         |COPY ZBNKSET.
         |77 WS-REC-LEN PIC S9(4) COMP.
         |77 WS-FILE-NAME PIC X(8) VALUE 'VSAMZBNK'.
         |01 WS-FILE-REC.
         |   05 WS-ACCNO PIC 9(10).
         |   05 WS-PIN PIC 9(10).
         |01 ACCNO PIC 9(10).
         |PROCEDURE DIVISION.
         |MAIN-LOGIC.
         |    MOVE LOGACCI TO WS-ACCNO.
         |    EXEC CICS READ DATASET(WS-FILE-NAME)
         |              INTO(WS-FILE-REC)
         |              RIDFLD(WS-ACCNO)
         |              LENGTH(WS-REC-LEN)
         |              UPDATE RESP(WS-RESP-CODE)
         |    END-EXEC.
         |    IF WS-RESP-CODE NOT = ZEROS
         |       DISPLAY 'Error reading file'
         |    END-IF.
         |""",
        """{
         |  "file": { "name": "ZBANK.cbl", "path": "/cobol/ZBANK.cbl", "size": 2048, "lastModified": "2026-01-15T13:00:00Z", "encoding": "UTF-8", "fileType": "Program" },
         |  "divisions": {
         |    "identification": "PROGRAM-ID. ZBANK.",
         |    "environment": null,
         |    "data": "WORKING-STORAGE SECTION. COPY ZBNKSET. 77 WS-REC-LEN...",
         |    "procedure": "MOVE LOGACCI TO WS-ACCNO. EXEC CICS READ..."
         |  },
         |  "variables": [
         |    { "name": "WS-REC-LEN", "level": 77, "dataType": "numeric", "picture": "S9(4)", "usage": "COMP" },
         |    { "name": "WS-FILE-NAME", "level": 77, "dataType": "alphanumeric", "picture": "X(8)", "usage": null },
         |    { "name": "WS-FILE-REC", "level": 1, "dataType": "group", "picture": null, "usage": null },
         |    { "name": "WS-ACCNO", "level": 5, "dataType": "numeric", "picture": "9(10)", "usage": null },
         |    { "name": "WS-PIN", "level": 5, "dataType": "numeric", "picture": "9(10)", "usage": null },
         |    { "name": "ACCNO", "level": 1, "dataType": "numeric", "picture": "9(10)", "usage": null }
         |  ],
         |  "procedures": [
         |    {
         |      "name": "MAIN-LOGIC",
         |      "paragraphs": ["MAIN-LOGIC"],
         |      "statements": [
         |        { "lineNumber": 12, "statementType": "MOVE", "content": "MOVE LOGACCI TO WS-ACCNO" },
         |        { "lineNumber": 13, "statementType": "EXEC-CICS", "content": "EXEC CICS READ DATASET(WS-FILE-NAME) INTO(WS-FILE-REC) RIDFLD(WS-ACCNO) LENGTH(WS-REC-LEN) UPDATE RESP(WS-RESP-CODE) END-EXEC" },
         |        { "lineNumber": 19, "statementType": "IF", "content": "IF WS-RESP-CODE NOT = ZEROS" },
         |        { "lineNumber": 20, "statementType": "DISPLAY", "content": "DISPLAY 'Error reading file'" }
         |      ]
         |    }
         |  ],
         |  "copybooks": ["ZBNKSET"],
         |  "complexity": {
         |    "cyclomaticComplexity": 2,
         |    "linesOfCode": 22,
         |    "numberOfProcedures": 1
         |  }
         |}""",
      )}
       |
       |${PromptHelpers.fewShotExample(
        "CICS COBOL with inline code (no named paragraphs)",
        """PROGRAM-ID. ZBANK3.
         |DATA DIVISION.
         |WORKING-STORAGE SECTION.
         |77 WS-REC-LEN PIC S9(4) COMP.
         |77 WS-FILE-NAME PIC X(8) VALUE 'VSAMZBNK'.
         |01 WS-FILE-REC.
         |   05 WS-ACCNO PIC 9(10).
         |   05 WS-BALANCE PIC 9(10).
         |01 ACCNO PIC 9(10).
         |01 SCREEN-STATE PIC 9 VALUE 0.
         |01 ACTION PIC X.
         |PROCEDURE DIVISION.
         |    PERFORM WITH TEST BEFORE UNTIL ACTION = 'Q'
         |      IF SCREEN-STATE = 0
         |        MOVE LOW-VALUES TO ZLOGINO
         |        EXEC CICS SEND MAP('ZLOGIN') MAPSET('ZBNKSET')
         |          ERASE
         |        END-EXEC
         |        EXEC CICS RECEIVE MAP('ZLOGIN') MAPSET('ZBNKSET')
         |          INTO(ZLOGINI)
         |        END-EXEC
         |        MOVE LOGACCI TO WS-ACCNO
         |        EXEC CICS READ DATASET(WS-FILE-NAME)
         |                  INTO(WS-FILE-REC)
         |                  RIDFLD(WS-ACCNO)
         |                  LENGTH(WS-REC-LEN)
         |                  RESP(WS-RESP-CODE)
         |        END-EXEC
         |        IF WS-RESP-CODE = ZEROS
         |          MOVE 1 TO SCREEN-STATE
         |        END-IF
         |      END-IF
         |    END-PERFORM.
         |    EXEC CICS RETURN END-EXEC.
         |""",
        """{
         |  "file": { "name": "ZBANK3.cbl", "path": "/cobol/ZBANK3.cbl", "size": 3072, "lastModified": "2026-02-10T14:00:00Z", "encoding": "UTF-8", "fileType": "Program" },
         |  "divisions": {
         |    "identification": "PROGRAM-ID. ZBANK3.",
         |    "environment": null,
         |    "data": "WORKING-STORAGE SECTION. 77 WS-REC-LEN PIC S9(4) COMP...",
         |    "procedure": "PERFORM WITH TEST BEFORE UNTIL ACTION = 'Q'... EXEC CICS RETURN END-EXEC"
         |  },
         |  "variables": [
         |    { "name": "WS-REC-LEN", "level": 77, "dataType": "numeric", "picture": "S9(4)", "usage": "COMP" },
         |    { "name": "WS-FILE-NAME", "level": 77, "dataType": "alphanumeric", "picture": "X(8)", "usage": null },
         |    { "name": "WS-FILE-REC", "level": 1, "dataType": "group", "picture": null, "usage": null },
         |    { "name": "WS-ACCNO", "level": 5, "dataType": "numeric", "picture": "9(10)", "usage": null },
         |    { "name": "WS-BALANCE", "level": 5, "dataType": "numeric", "picture": "9(10)", "usage": null },
         |    { "name": "ACCNO", "level": 1, "dataType": "numeric", "picture": "9(10)", "usage": null },
         |    { "name": "SCREEN-STATE", "level": 1, "dataType": "numeric", "picture": "9", "usage": null },
         |    { "name": "ACTION", "level": 1, "dataType": "alphanumeric", "picture": "X", "usage": null }
         |  ],
         |  "procedures": [
         |    {
         |      "name": "PROCEDURE-DIVISION",
         |      "paragraphs": ["PROCEDURE-DIVISION"],
         |      "statements": [
         |        { "lineNumber": 12, "statementType": "PERFORM", "content": "PERFORM WITH TEST BEFORE UNTIL ACTION = 'Q'" },
         |        { "lineNumber": 13, "statementType": "IF", "content": "IF SCREEN-STATE = 0" },
         |        { "lineNumber": 14, "statementType": "MOVE", "content": "MOVE LOW-VALUES TO ZLOGINO" },
         |        { "lineNumber": 15, "statementType": "EXEC-CICS", "content": "EXEC CICS SEND MAP('ZLOGIN') MAPSET('ZBNKSET') ERASE END-EXEC" },
         |        { "lineNumber": 18, "statementType": "EXEC-CICS", "content": "EXEC CICS RECEIVE MAP('ZLOGIN') MAPSET('ZBNKSET') INTO(ZLOGINI) END-EXEC" },
         |        { "lineNumber": 21, "statementType": "MOVE", "content": "MOVE LOGACCI TO WS-ACCNO" },
         |        { "lineNumber": 22, "statementType": "EXEC-CICS", "content": "EXEC CICS READ DATASET(WS-FILE-NAME) INTO(WS-FILE-REC) RIDFLD(WS-ACCNO) LENGTH(WS-REC-LEN) RESP(WS-RESP-CODE) END-EXEC" },
         |        { "lineNumber": 27, "statementType": "IF", "content": "IF WS-RESP-CODE = ZEROS" },
         |        { "lineNumber": 28, "statementType": "MOVE", "content": "MOVE 1 TO SCREEN-STATE" },
         |        { "lineNumber": 32, "statementType": "END-PERFORM", "content": "END-PERFORM" },
         |        { "lineNumber": 33, "statementType": "EXEC-CICS", "content": "EXEC CICS RETURN END-EXEC" }
         |      ]
         |    }
         |  ],
         |  "copybooks": [],
         |  "complexity": {
         |    "cyclomaticComplexity": 3,
         |    "linesOfCode": 33,
         |    "numberOfProcedures": 1
         |  }
         |}""",
      )}
       |""".stripMargin
