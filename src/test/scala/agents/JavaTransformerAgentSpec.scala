package agents

import java.nio.file.{ Files, Path }

import zio.*
import zio.test.*

import core.{ AIService, FileService, ResponseParser }
import models.*

object JavaTransformerAgentSpec extends ZIOSpecDefault:

  private val entityJson: String =
    """{
      |  "className": "Customer",
      |  "packageName": "com.example.custprog.entity",
      |  "fields": [
      |    { "name": "customerId", "javaType": "Long", "cobolSource": "CUSTOMER-ID", "annotations": ["@Id"] }
      |  ],
      |  "annotations": ["@Entity", "@Table(name = \"customer\")"],
      |  "sourceCode": "public class Customer {}"
      |}""".stripMargin

  private val serviceJson: String =
    """{
      |  "name": "CustomerService",
      |  "methods": [
      |    { "name": "process", "returnType": "String", "parameters": [], "body": "return \"ok\";" }
      |  ]
      |}""".stripMargin

  private val controllerJson: String =
    """{
      |  "name": "CustomerController",
      |  "basePath": "/api/customers",
      |  "endpoints": [
      |    { "path": "/process", "method": "POST", "methodName": "process" }
      |  ]
      |}""".stripMargin

  def spec: Spec[Any, Any] = suite("JavaTransformerAgentSpec")(
    test("transform generates Spring Boot project structure") {
      ZIO.scoped {
        for
          tempDir       <- ZIO.attemptBlocking(Files.createTempDirectory("transformer-spec"))
          analysis       = sampleAnalysis("CUSTPROG.cbl")
          graph          = DependencyGraph.empty
          project       <- JavaTransformerAgent
                             .transform(analysis, graph)
                             .provide(
                               FileService.live,
                               ResponseParser.live,
                               mockAIService(List(entityJson, serviceJson, controllerJson)),
                               ZLayer.succeed(MigrationConfig(sourceDir = tempDir, outputDir = tempDir)),
                               JavaTransformerAgent.live,
                             )
          projectDir     = tempDir.resolve(project.projectName.toLowerCase)
          pomExists     <- ZIO.attemptBlocking(Files.exists(projectDir.resolve("pom.xml")))
          appExists     <- ZIO.attemptBlocking(
                             Files.exists(
                               projectDir
                                 .resolve("src/main/java")
                                 .resolve("com/example")
                                 .resolve(project.projectName.toLowerCase)
                                 .resolve("Application.java")
                             )
                           )
          openApiExists <- ZIO.attemptBlocking(
                             Files.exists(
                               projectDir
                                 .resolve("src/main/java")
                                 .resolve("com/example")
                                 .resolve(project.projectName.toLowerCase)
                                 .resolve("config")
                                 .resolve("OpenApiConfig.java")
                             )
                           )
          repoExists    <- ZIO.attemptBlocking(
                             Files.exists(
                               projectDir
                                 .resolve("src/main/java")
                                 .resolve("com/example")
                                 .resolve(project.projectName.toLowerCase)
                                 .resolve("repository")
                                 .resolve("CustomerRepository.java")
                             )
                           )
          yamlExists    <- ZIO.attemptBlocking(
                             Files.exists(projectDir.resolve("src/main/resources/application.yml"))
                           )
        yield assertTrue(
          project.projectName == "CUSTPROG",
          pomExists,
          appExists,
          openApiExists,
          repoExists,
          yamlExists,
        )
      }
    }
  )

  private def mockAIService(outputs: List[String]): ULayer[AIService] =
    ZLayer.fromZIO {
      for
        ref <- Ref.make(outputs)
      yield new AIService {
        override def execute(prompt: String): ZIO[Any, AIError, AIResponse] =
          ref.modify {
            case head :: tail => (AIResponse(head), tail)
            case Nil          => (AIResponse("{}"), Nil)
          }

        override def executeWithContext(prompt: String, context: String): ZIO[Any, AIError, AIResponse] =
          execute(prompt)

        override def isAvailable: ZIO[Any, Nothing, Boolean] =
          ZIO.succeed(true)
      }
    }

  private def sampleAnalysis(name: String): CobolAnalysis =
    CobolAnalysis(
      file = CobolFile(
        path = Path.of(s"/tmp/$name"),
        name = name,
        size = 10,
        lineCount = 2,
        lastModified = java.time.Instant.parse("2026-02-06T00:00:00Z"),
        encoding = "UTF-8",
        fileType = FileType.Program,
      ),
      divisions = CobolDivisions(
        identification = Some("PROGRAM-ID."),
        environment = None,
        data = None,
        procedure = None,
      ),
      variables = List.empty,
      procedures = List.empty,
      copybooks = List.empty,
      complexity = ComplexityMetrics(1, 2, 1),
    )
