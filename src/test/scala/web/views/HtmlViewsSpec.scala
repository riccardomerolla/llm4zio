package web.views

import java.time.Instant

import zio.*
import zio.json.*
import zio.test.*

import db.*
import models.*
import scalatags.Text.all.{ StringFrag, p as pTag }

object HtmlViewsSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-02-08T10:00:00Z")

  private val runCompleted = MigrationRunRow(
    id = 1L,
    sourceDir = "/src/cobol",
    outputDir = "/out/java",
    status = RunStatus.Completed,
    startedAt = now,
    completedAt = Some(now),
    totalFiles = 10,
    processedFiles = 10,
    successfulConversions = 9,
    failedConversions = 1,
    currentPhase = Some("documentation"),
    errorMessage = None,
  )

  private val runRunning = runCompleted.copy(
    id = 2L,
    status = RunStatus.Running,
    completedAt = None,
    processedFiles = 5,
    successfulConversions = 4,
    failedConversions = 0,
    currentPhase = Some("analysis"),
  )

  private val runFailed = runCompleted.copy(
    id = 3L,
    status = RunStatus.Failed,
    failedConversions = 3,
    errorMessage = Some("Parsing error in PROG1.cbl"),
  )

  private val sampleFile = CobolFileRow(
    id = 10L,
    runId = 1L,
    path = "/src/cobol/PROG1.cbl",
    name = "PROG1.cbl",
    fileType = db.FileType.Program,
    size = 2048L,
    lineCount = 150L,
    encoding = "UTF-8",
    createdAt = now,
  )

  private val sampleAnalysis = CobolAnalysisRow(
    id = 100L,
    runId = 1L,
    fileId = 10L,
    analysisJson =
      """{
      "file":{"path":"/src/cobol/PROG1.cbl","name":"PROG1.cbl","size":2048,"lineCount":150,"lastModified":"2026-02-08T10:00:00Z","encoding":"UTF-8","fileType":"Program"},
      "divisions":{"identification":"IDENTIFICATION DIVISION.","environment":null,"data":"DATA DIVISION.","procedure":"PROCEDURE DIVISION."},
      "variables":[{"name":"WS-COUNTER","level":1,"dataType":"NUMERIC","picture":"9(5)","usage":null}],
      "procedures":[{"name":"MAIN-PARA","paragraphs":["INIT","PROCESS"],"statements":[]}],
      "copybooks":["CPYBOOK1","CPYBOOK2"],
      "complexity":{"cyclomaticComplexity":8,"linesOfCode":150,"numberOfProcedures":3}
    }""",
    createdAt = now,
  )

  private val sampleDep = DependencyRow(
    id = 200L,
    runId = 1L,
    sourceNode = "PROG1",
    targetNode = "CPYBOOK1",
    edgeType = "INCLUDES",
  )

  private val sampleTransformProject = SpringBootProject(
    projectName = "customer-display",
    sourceProgram = "CUSTOMER-DISPLAY.cbl",
    generatedAt = now,
    entities = List(
      JavaEntity(
        className = "CustomerRecord",
        packageName = "com.example.customerdisplay.entity",
        fields = List(
          JavaField(
            name = "customerId",
            javaType = "String",
            cobolSource = "CUST-ID",
            annotations = List("@Id"),
          )
        ),
        annotations = List("@Entity"),
        sourceCode = "public class CustomerRecord {}",
      )
    ),
    services = List(
      JavaService(
        name = "CustomerService",
        methods = List(
          JavaMethod(
            name = "displayCustomer",
            returnType = "String",
            parameters = List(JavaParameter("customerId", "String")),
            body = """return "OK";""",
          )
        ),
      )
    ),
    controllers = List(
      JavaController(
        name = "CustomerController",
        endpoints = List(RestEndpoint("/customers/{id}", HttpMethod.GET, "displayCustomer")),
        basePath = "/api",
      )
    ),
    repositories = List(
      JavaRepository(
        name = "CustomerRepository",
        packageName = "com.example.customerdisplay.repository",
        entityName = "CustomerRecord",
        idType = "String",
        annotations = List("@Repository"),
        sourceCode = "interface CustomerRepository {}",
      )
    ),
    configuration = ProjectConfiguration(
      groupId = "com.example",
      artifactId = "customer-display",
      dependencies = List("spring-boot-starter-web", "spring-boot-starter-data-jpa"),
    ),
    buildFile = BuildFile("maven", "<project/>"),
  )

  private val sampleTransformAnalysis = sampleAnalysis.copy(
    id = 101L,
    analysisJson = sampleTransformProject.toJsonPretty,
  )

  private val sampleValidationReport = ValidationReport(
    projectName = "customer-display",
    validatedAt = now,
    compileResult = CompileResult(success = false, exitCode = 1, output = "Compilation failed"),
    coverageMetrics = CoverageMetrics(
      variablesCovered = 92.5,
      proceduresCovered = 88.0,
      fileSectionCovered = 75.0,
      unmappedItems = List("WS-AMOUNT"),
    ),
    issues = List(
      ValidationIssue(
        severity = Severity.ERROR,
        category = IssueCategory.Compile,
        message = "Compilation error in service",
        file = Some("CustomerService.java"),
        line = Some(42),
        suggestion = Some("Fix method signature"),
      )
    ),
    semanticValidation = SemanticValidation(
      businessLogicPreserved = false,
      confidence = 66.7,
      summary = "Business rules diverge in one branch",
      issues = List(
        ValidationIssue(
          severity = Severity.WARNING,
          category = IssueCategory.Semantic,
          message = "Conditional branch differs from COBOL",
          file = None,
          line = None,
          suggestion = Some("Review branch mapping"),
        )
      ),
    ),
    overallStatus = ValidationStatus.Failed,
  )

  private val sampleValidationAnalysis = sampleAnalysis.copy(
    id = 102L,
    analysisJson = sampleValidationReport.toJsonPretty,
  )

  private val samplePhase = PhaseProgressRow(
    id = 300L,
    runId = 1L,
    phase = "analysis",
    status = "running",
    itemTotal = 10,
    itemProcessed = 5,
    errorCount = 0,
    updatedAt = now,
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("HtmlViewsSpec")(
    suite("Dashboard")(
      test("renders dashboard with summary cards and HTMX refresh") {
        val html = HtmlViews.dashboard(List(runCompleted, runRunning, runFailed), workflowCount = 4)
        assertTrue(
          html.contains("Dashboard"),
          html.contains("Total Runs"),
          html.contains("Success Rate"),
          html.contains("Files Processed"),
          html.contains("Active Runs"),
          html.contains("Workflows"),
          html.contains(">4<"),
          html.contains("""hx-get="/api/runs/recent""""),
          html.contains("""hx-trigger="every 5s""""),
        )
      },
      test("dashboard contains run links") {
        val html = HtmlViews.dashboard(List(runCompleted))
        assertTrue(
          html.contains("/runs/1"),
          html.contains("#1"),
        )
      },
      test("calculates correct success rate") {
        val html = HtmlViews.dashboard(List(runCompleted, runRunning, runFailed))
        assertTrue(html.contains("33%"))
      },
      test("handles empty runs") {
        val html = HtmlViews.dashboard(List.empty)
        assertTrue(
          html.contains("Dashboard"),
          html.contains("0"),
        )
      },
    ),
    suite("Runs")(
      test("list contains pagination and new run link") {
        val html = HtmlViews.runsList(List(runCompleted), pageNumber = 2, pageSize = 20)
        assertTrue(
          html.contains("Migration Runs"),
          html.contains("Page 2"),
          html.contains("/runs/new"),
        )
      },
      test("detail includes SSE attributes") {
        val html = HtmlViews.runDetail(
          runRunning,
          List(samplePhase),
          workflowName = Some("Default Workflow"),
          workflow = WorkflowDefinition.default,
        )
        assertTrue(
          html.contains("""hx-ext="sse""""),
          html.contains("""sse-connect="/runs/2/progress""""),
          html.contains("""sse-swap="phase-progress""""),
          html.contains("""sse-swap="workflow-diagram""""),
          html.contains("classDef completed fill:#065f46"),
          html.contains("classDef running fill:#1e3a5f"),
          html.contains("Run #2"),
        )
      },
      test("detail shows error message for failed runs") {
        val html = HtmlViews.runDetail(runFailed, List.empty)
        assertTrue(
          html.contains("Parsing error in PROG1.cbl"),
          html.contains("Error"),
          html.contains("Retry Failed Step"),
          html.contains("""action="/runs/3/retry""""),
        )
      },
      test("detail shows cancel button for running runs") {
        val html = HtmlViews.runDetail(runRunning, List.empty)
        assertTrue(
          html.contains("Cancel Run"),
          html.contains("""hx-delete="/runs/2""""),
        )
      },
      test("form has required fields") {
        val html = HtmlViews.runForm
        assertTrue(
          html.contains("Start New Migration"),
          html.contains("sourceDir"),
          html.contains("outputDir"),
          html.contains("dryRun"),
          html.contains("""action="/runs""""),
          html.contains("""method="post""""),
        )
      },
      test("recent runs fragment is bare HTML") {
        val html = HtmlViews.recentRunsFragment(List(runCompleted))
        assertTrue(
          !html.contains("<!DOCTYPE html>"),
          !html.contains("<head"),
          html.contains("/runs/1"),
        )
      },
      test("run form handles one-step custom workflow previews") {
        val workflow = WorkflowDefinition(
          id = Some(99L),
          name = "Single Step",
          steps = List(MigrationStep.Discovery),
          isBuiltin = false,
        )
        val html     = HtmlViews.runForm(List(workflow))
        assertTrue(
          html.contains("Single Step"),
          html.contains("""value="99""""),
          html.contains("""step0[\"Discovery\"]"""),
          html.contains("var previews"),
          html.contains(""""99": "graph LR"""),
        )
      },
    ),
    suite("Workflows")(
      test("workflow detail renders Mermaid for single-step workflows") {
        val workflow = WorkflowDefinition(
          id = Some(7L),
          name = "Only Discovery",
          steps = List(MigrationStep.Discovery),
          isBuiltin = false,
        )
        val html     = HtmlViews.workflowDetail(workflow)
        assertTrue(
          html.contains("Only Discovery"),
          html.contains("""step0[&quot;Discovery&quot;]"""),
        )
      }
    ),
    suite("Analysis")(
      test("list includes HTMX search attributes") {
        val html = HtmlViews.analysisList(1L, List(sampleFile), List(sampleAnalysis))
        assertTrue(
          html.contains("""hx-get="/api/analysis/search?runId=1""""),
          html.contains("""hx-trigger="keyup changed delay:300ms""""),
          html.contains("""hx-target="#file-list""""),
          html.contains("PROG1.cbl"),
        )
      },
      test("detail parses and renders analysis JSON") {
        val html = HtmlViews.analysisDetail(sampleFile, sampleAnalysis)
        assertTrue(
          html.contains("PROG1.cbl"),
          html.contains("Divisions"),
          html.contains("Variables"),
          html.contains("WS-COUNTER"),
          html.contains("Procedures"),
          html.contains("MAIN-PARA"),
          html.contains("Copybook Dependencies"),
          html.contains("CPYBOOK1"),
          html.contains("CPYBOOK2"),
          html.contains("Complexity Metrics"),
          html.contains("Raw JSON"),
        )
      },
      test("detail handles invalid JSON gracefully") {
        val badAnalysis = sampleAnalysis.copy(analysisJson = "not-valid-json")
        val html        = HtmlViews.analysisDetail(sampleFile, badAnalysis)
        assertTrue(
          html.contains("Failed to parse analysis JSON"),
          html.contains("not-valid-json"),
        )
      },
      test("detail renders transform report JSON path") {
        val html = HtmlViews.analysisDetail(sampleFile, sampleTransformAnalysis)
        assertTrue(
          html.contains("Transform Report"),
          html.contains("Entities (1)"),
          html.contains("Services (1)"),
          html.contains("Controllers (1)"),
          html.contains("Repositories (1)"),
          html.contains("Build Configuration"),
          html.contains("customer-display"),
          html.contains("CustomerRepository"),
          html.contains("displayCustomer"),
          html.contains("GET"),
        )
      },
      test("detail renders validation report JSON path") {
        val html = HtmlViews.analysisDetail(sampleFile, sampleValidationAnalysis)
        assertTrue(
          html.contains("Validation Report"),
          html.contains("Compilation Result"),
          html.contains("Exit code: 1"),
          html.contains("Coverage Metrics"),
          html.contains("Unmapped Items"),
          html.contains("Issues (1)"),
          html.contains("Semantic Validation"),
          html.contains("Business rules diverge in one branch"),
          html.contains("Suggestion: Review branch mapping"),
        )
      },
      test("search fragment is bare HTML") {
        val html = HtmlViews.analysisSearchFragment(List(sampleFile))
        assertTrue(
          !html.contains("<!DOCTYPE html>"),
          !html.contains("<head"),
          html.contains("PROG1.cbl"),
        )
      },
      test("search fragment handles empty results") {
        val html = HtmlViews.analysisSearchFragment(List.empty)
        assertTrue(
          html.contains("No files match")
        )
      },
    ),
    suite("Settings")(
      test("settings page renders defaults") {
        val html = HtmlViews.settingsPage(Map.empty, None)
        assertTrue(
          html.contains("Settings"),
          html.contains("AI Provider"),
          html.contains("Processing"),
          html.contains("Discovery"),
          html.contains("Features"),
          html.contains("""name="ai.provider""""),
          html.contains("""value="GeminiCli""""),
          html.contains("""selected="selected""""),
          html.contains("""name="features.enableCheckpointing""""),
          html.contains("""name="features.enableBusinessLogicExtractor""""),
        )
      },
      test("settings page renders flash and custom values") {
        val html = HtmlViews.settingsPage(
          Map(
            "ai.provider"                           -> "OpenAi",
            "ai.model"                              -> "gpt-4.1",
            "features.enableCheckpointing"          -> "false",
            "features.enableBusinessLogicExtractor" -> "true",
            "features.verbose"                      -> "true",
          ),
          flash = Some("Settings saved"),
        )
        assertTrue(
          html.contains("Settings saved"),
          html.contains("""value="OpenAi""""),
          html.contains("""value="gpt-4.1""""),
          html.contains("""name="features.enableBusinessLogicExtractor""""),
          html.contains("""name="features.verbose""""),
        )
      },
    ),
    suite("Graph")(
      test("page includes Mermaid diagram and export links") {
        val html = HtmlViews.graphPage(1L, List(sampleDep))
        assertTrue(
          html.contains("Dependency Graph"),
          html.contains("""class="mermaid""""),
          html.contains("PROG1"),
          html.contains("CPYBOOK1"),
          html.contains("Export Mermaid"),
          html.contains("Export JSON"),
          html.contains("/api/graph/1/export?format=mermaid"),
          html.contains("/api/graph/1/export?format=json"),
        )
      },
      test("graph shows empty state when no dependencies") {
        val html = HtmlViews.graphPage(1L, List.empty)
        assertTrue(
          html.contains("No dependencies found")
        )
      },
    ),
    suite("Components")(
      test("status badges have correct colours") {
        val completed = Components.statusBadge(RunStatus.Completed).render
        val failed    = Components.statusBadge(RunStatus.Failed).render
        val running   = Components.statusBadge(RunStatus.Running).render
        val pending   = Components.statusBadge(RunStatus.Pending).render
        val cancelled = Components.statusBadge(RunStatus.Cancelled).render
        assertTrue(
          completed.contains("text-green-400") && completed.contains("Completed"),
          failed.contains("text-red-400") && failed.contains("Failed"),
          running.contains("text-blue-400") && running.contains("Running"),
          pending.contains("text-yellow-400") && pending.contains("Pending"),
          cancelled.contains("text-gray-400") && cancelled.contains("Cancelled"),
        )
      },
      test("progress bar calculates percentage") {
        val bar = Components.progressBar(5, 10).render
        assertTrue(bar.contains("width: 50%"))
      },
      test("progress bar handles zero total") {
        val bar = Components.progressBar(0, 0).render
        assertTrue(bar.contains("width: 0%"))
      },
      test("file type badges render correctly") {
        val program  = Components.fileTypeBadge(db.FileType.Program).render
        val copybook = Components.fileTypeBadge(db.FileType.Copybook).render
        assertTrue(
          program.contains("text-indigo-400") && program.contains("Program"),
          copybook.contains("text-purple-400") && copybook.contains("Copybook"),
        )
      },
    ),
    suite("Layout")(
      test("page includes required scripts") {
        val html = Layout.page("Test", "/")(pTag(StringFrag("test body")))
        assertTrue(
          html.contains("<!DOCTYPE html>"),
          html.contains("tailwindcss"),
          html.contains("htmx.org@2.0.4"),
          html.contains("htmx-ext-sse@2.0.0"),
        )
      },
      test("page highlights active navigation item") {
        val html = Layout.page("Test", "/runs")(pTag(StringFrag("test body")))
        assertTrue(
          html.contains("A-B-Normal"),
          html.contains("href=\"/runs\" class=\"group flex gap-x-3 rounded-md bg-white/5"),
        )
      },
    ),
  )
