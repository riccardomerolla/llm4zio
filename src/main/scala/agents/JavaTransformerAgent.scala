package agents

import java.nio.file.Path

import zio.*
import zio.json.*

import llm4zio.core.{LlmService, LlmError}
import llm4zio.tools.JsonSchema
import core.{ FileService, Logger }
import models.*
import prompts.PromptTemplates

/** JavaTransformerAgent - Transform COBOL programs into a unified Spring Boot project
  *
  * Responsibilities:
  *   - Convert COBOL data structures to Java classes/records
  *   - Transform PROCEDURE DIVISION to service methods
  *   - Generate Spring Boot annotations and configurations
  *   - Implement REST endpoints for program entry points
  *   - Create Spring Data JPA entities from file definitions
  *   - Handle error scenarios with try-catch blocks
  *   - Sanitize names to produce valid Java identifiers
  *   - Verify generated source for hallucinated package declarations
  *   - Attempt compilation and retry with AI-driven fixes
  *
  * Interactions:
  *   - Input from: CobolAnalyzerAgent, DependencyMapperAgent
  *   - Output consumed by: ValidationAgent, DocumentationAgent
  */
trait JavaTransformerAgent:
  def transform(
    analyses: List[CobolAnalysis],
    dependencyGraph: DependencyGraph,
  ): ZIO[Any, TransformError, SpringBootProject]

object JavaTransformerAgent:
  def transform(
    analyses: List[CobolAnalysis],
    dependencyGraph: DependencyGraph,
  ): ZIO[JavaTransformerAgent, TransformError, SpringBootProject] =
    ZIO.serviceWithZIO[JavaTransformerAgent](_.transform(analyses, dependencyGraph))

  val live
    : ZLayer[LlmService & FileService & MigrationConfig, Nothing, JavaTransformerAgent] =
    ZLayer.fromFunction {
      (
        llmService: LlmService,
        fileService: FileService,
        config: MigrationConfig,
      ) =>
        new JavaTransformerAgent {
          override def transform(
            analyses: List[CobolAnalysis],
            dependencyGraph: DependencyGraph,
          ): ZIO[Any, TransformError, SpringBootProject] =
            for
              _              <- Logger.info(s"Transforming ${analyses.size} COBOL program(s) to unified Spring Boot project")
              pairs          <- ZIO.foreach(analyses) { analysis =>
                                  for
                                    entity     <- generateEntity(analysis)
                                    service    <- generateService(analysis, dependencyGraph)
                                    controller <- generateController(analysis, service.name)
                                    repos       = generateRepositories(entity)
                                  yield (entity, service, controller, repos)
                                }
              now            <- Clock.instant
              allEntities     = pairs.map(_._1)
              allServices     = pairs.map(_._2)
              allControllers  = pairs.map(_._3)
              allRepositories = pairs.flatMap(_._4)
              projectName     = config.projectName.getOrElse(deriveProjectName(analyses))
              project         = buildUnifiedProject(
                                  projectName,
                                  analyses,
                                  now,
                                  allEntities,
                                  allServices,
                                  allControllers,
                                  allRepositories,
                                )
              basePkg         = s"${config.basePackage}.${sanitizeJavaIdentifier(project.projectName).toLowerCase}"
              mismatches      = verifyPackageDeclarations(project, basePkg)
              _              <-
                ZIO.when(mismatches.nonEmpty)(
                  Logger.warn(
                    s"Found ${mismatches.size} hallucinated package declaration(s), fixing: ${mismatches.map(_._1).mkString(", ")}"
                  )
                )
              fixedProject    = if mismatches.nonEmpty then fixPackageDeclarations(project, basePkg) else project
              _              <- writeProject(fixedProject, config.outputDir)
              projectDir      = config.outputDir.resolve(
                                  sanitizeJavaIdentifier(fixedProject.projectName).toLowerCase
                                )
              finalProject   <- {
                if config.maxCompileRetries > 0
                then
                  compilationRetryLoop(fixedProject, projectDir, basePkg, config.maxCompileRetries)
                else ZIO.succeed(fixedProject)
              }
              _              <- Logger.info(s"Generated unified Spring Boot project: ${finalProject.projectName}")
            yield finalProject

          // ---------------------------------------------------------------------------
          // AI generation
          // ---------------------------------------------------------------------------

          private def generateEntity(analysis: CobolAnalysis): ZIO[Any, TransformError, JavaEntity] =
            val prompt = PromptTemplates.JavaTransformer.generateEntity(analysis)
            val schema = buildJsonSchema()
            for
              entity   <- llmService
                            .executeStructured[JavaEntity](prompt, schema)
                            .mapError(convertError(analysis.file.name))
              enriched  = ensureEntityDefaults(entity, analysis)
            yield enriched

          private def generateService(
            analysis: CobolAnalysis,
            graph: DependencyGraph,
          ): ZIO[Any, TransformError, JavaService] =
            val deps   = graph.serviceCandidates
            val prompt = PromptTemplates.JavaTransformer.generateService(analysis, deps)
            val schema = buildJsonSchema()
            for
              service  <- llmService
                            .executeStructured[JavaService](prompt, schema)
                            .mapError(convertError(analysis.file.name))
            yield service

          private def generateController(
            analysis: CobolAnalysis,
            serviceName: String,
          ): ZIO[Any, TransformError, JavaController] =
            val prompt = PromptTemplates.JavaTransformer.generateController(analysis, serviceName)
            val schema = buildJsonSchema()
            for
              controller <- llmService
                              .executeStructured[JavaController](prompt, schema)
                              .mapError(convertError(analysis.file.name))
            yield controller

          // ---------------------------------------------------------------------------
          // Helper methods
          // ---------------------------------------------------------------------------

          private def buildJsonSchema(): JsonSchema =
            import zio.json.ast.Json
            Json.Obj(
              "type" -> Json.Str("object")
            )

          private def convertError(fileName: String)(error: LlmError): TransformError =
            error match
              case LlmError.ProviderError(message, cause) =>
                TransformError.AIFailed(
                  fileName,
                  s"Provider error: $message${cause.map(c => s" (${c.getMessage})").getOrElse("")}"
                )
              case LlmError.RateLimitError(retryAfter) =>
                TransformError.AIFailed(
                  fileName,
                  s"Rate limited${retryAfter.map(d => s", retry after ${d.toSeconds}s").getOrElse("")}"
                )
              case LlmError.AuthenticationError(message) =>
                TransformError.AIFailed(fileName, s"Authentication failed: $message")
              case LlmError.InvalidRequestError(message) =>
                TransformError.AIFailed(fileName, s"Invalid request: $message")
              case LlmError.TimeoutError(duration) =>
                TransformError.AIFailed(fileName, s"Request timed out after ${duration.toSeconds}s")
              case LlmError.ParseError(message, raw) =>
                TransformError.ParseFailed(fileName, s"$message\nRaw: ${raw.take(200)}")
              case LlmError.ToolError(toolName, message) =>
                TransformError.AIFailed(fileName, s"Tool error ($toolName): $message")
              case LlmError.ConfigError(message) =>
                TransformError.AIFailed(fileName, s"Configuration error: $message")

          // ---------------------------------------------------------------------------
          // Name sanitization
          // ---------------------------------------------------------------------------

          private def sanitizeJavaIdentifier(name: String): String =
            val stripped = name.replaceAll("\\.(cbl|cob)$", "")
            val cleaned  = stripped.replaceAll("[^a-zA-Z0-9]", "")
            val result   = if cleaned.isEmpty then "generated" else cleaned
            if result.head.isDigit then s"p$result" else result

          private def deriveProjectName(analyses: List[CobolAnalysis]): String =
            if analyses.size == 1 then programIdFor(analyses.head.file.name)
            else
              analyses.headOption
                .map(a => programIdFor(a.file.name))
                .getOrElse("MIGRATIONPROJECT")

          // ---------------------------------------------------------------------------
          // Hallucination check
          // ---------------------------------------------------------------------------

          private def verifyPackageDeclarations(
            project: SpringBootProject,
            basePkg: String,
          ): List[(String, String, String)] =
            val packagePattern = """^\s*package\s+([a-zA-Z0-9_.]+)\s*;""".r.unanchored

            def checkSource(
              label: String,
              expectedPkg: String,
              source: String,
            ): Option[(String, String, String)] =
              if source.isEmpty then None
              else
                packagePattern.findFirstMatchIn(source) match
                  case Some(m) =>
                    val actual = m.group(1)
                    if actual != expectedPkg then Some((label, expectedPkg, actual)) else None
                  case None    => None

            val entityMismatches = project.entities.flatMap { e =>
              checkSource(s"Entity ${e.className}", s"$basePkg.entity", e.sourceCode)
            }
            val repoMismatches   = project.repositories.flatMap { r =>
              checkSource(s"Repository ${r.name}", s"$basePkg.repository", r.sourceCode)
            }
            entityMismatches ++ repoMismatches

          private def fixPackageDeclarations(
            project: SpringBootProject,
            basePkg: String,
          ): SpringBootProject =
            val packagePattern = """^\s*package\s+[a-zA-Z0-9_.]+\s*;""".r

            def fixSource(source: String, expectedPkg: String): String =
              if source.isEmpty then source
              else packagePattern.replaceFirstIn(source, s"package $expectedPkg;")

            val fixedEntities = project.entities.map { e =>
              e.copy(sourceCode = fixSource(e.sourceCode, s"$basePkg.entity"))
            }
            val fixedRepos    = project.repositories.map { r =>
              r.copy(sourceCode = fixSource(r.sourceCode, s"$basePkg.repository"))
            }
            project.copy(entities = fixedEntities, repositories = fixedRepos)

          // ---------------------------------------------------------------------------
          // Compilation retry loop
          // ---------------------------------------------------------------------------

          private def runMavenCompile(
            projectDir: java.nio.file.Path
          ): ZIO[Any, TransformError, CompileResult] =
            ZIO
              .attemptBlocking {
                val process = new ProcessBuilder("mvn", "-q", "compile")
                  .directory(projectDir.toFile)
                  .redirectErrorStream(true)
                  .start()
                val output  = new String(process.getInputStream.readAllBytes())
                val code    = process.waitFor()
                CompileResult(success = code == 0, exitCode = code, output = output.take(2000))
              }
              .timeout(90.seconds)
              .mapError(e =>
                TransformError.WriteFailed(projectDir, Option(e.getMessage).getOrElse("compile failed"))
              )
              .map {
                case Some(result) => result
                case None         =>
                  CompileResult(success = false, exitCode = 124, output = "mvn compile timed out")
              }

          private def compilationRetryLoop(
            project: SpringBootProject,
            projectDir: java.nio.file.Path,
            basePkg: String,
            maxRetries: Int,
          ): ZIO[Any, TransformError, SpringBootProject] =
            def loop(
              current: SpringBootProject,
              attempt: Int,
            ): ZIO[Any, TransformError, SpringBootProject] =
              for
                result <- runMavenCompile(projectDir)
                fixed  <- {
                  if result.success then
                    Logger.info(
                      s"Compilation succeeded for ${project.projectName} (attempt $attempt)"
                    ) *> ZIO.succeed(current)
                  else if attempt >= maxRetries then
                    Logger.warn(
                      s"Compilation fix exhausted after $maxRetries retries for ${project.projectName}"
                    ) *> ZIO.succeed(current)
                  else
                    for
                      _       <-
                        Logger.warn(
                          s"Compilation failed for ${project.projectName} (attempt $attempt/$maxRetries): ${result.output.take(200)}"
                        )
                      updated <- fixSourcesFromErrors(current, basePkg, result.output)
                      mainJava = projectDir.resolve("src/main/java")
                      _       <- writeEntityFiles(updated, mainJava, basePkg)
                      _       <- writeServiceFiles(updated, mainJava, basePkg)
                      _       <- writeControllerFiles(updated, mainJava, basePkg)
                      _       <- writeRepositoryFiles(updated, mainJava, basePkg)
                      next    <- loop(updated, attempt + 1)
                    yield next
                }
              yield fixed

            loop(project, 1)

          private def fixSourcesFromErrors(
            project: SpringBootProject,
            basePkg: String,
            compileOutput: String,
          ): ZIO[Any, TransformError, SpringBootProject] =
            for fixedEntities <- ZIO.foreach(project.entities) { entity =>
                                   val source =
                                     if entity.sourceCode.nonEmpty then entity.sourceCode
                                     else renderEntity(basePkg, entity)
                                   if !compileOutput.contains(entity.className) then ZIO.succeed(entity)
                                   else
                                     for
                                       response <- llmService
                                                     .execute(
                                                       PromptTemplates.JavaTransformer.fixCompilationErrors(
                                                         s"${entity.className}.java",
                                                         source,
                                                         compileOutput,
                                                       )
                                                     )
                                                     .mapError(convertError(entity.className))
                                       cleaned   = stripCodeFences(response.content.trim)
                                     yield entity.copy(sourceCode = cleaned)
                                 }
            yield project.copy(entities = fixedEntities)

          private def stripCodeFences(code: String): String =
            val lines    = code.linesIterator.toList
            val stripped =
              if lines.headOption.exists(_.startsWith("```")) then
                lines.tail.reverse.dropWhile(_.startsWith("```")).reverse
              else lines
            stripped.mkString("\n")

          // ---------------------------------------------------------------------------
          // Project building
          // ---------------------------------------------------------------------------

          private def buildUnifiedProject(
            projectName: String,
            analyses: List[CobolAnalysis],
            generatedAt: java.time.Instant,
            entities: List[JavaEntity],
            services: List[JavaService],
            controllers: List[JavaController],
            repositories: List[JavaRepository],
          ): SpringBootProject =
            val artifactId = sanitizeJavaIdentifier(projectName).toLowerCase
            SpringBootProject(
              projectName = projectName,
              sourceProgram = analyses.map(_.file.name).mkString(","),
              generatedAt = generatedAt,
              entities = entities,
              services = services,
              controllers = controllers,
              repositories = repositories,
              configuration = ProjectConfiguration(
                groupId = config.basePackage,
                artifactId = artifactId,
                dependencies =
                  List("spring-boot-starter-web", "spring-boot-starter-data-jpa", "lombok", "springdoc-openapi"),
              ),
              buildFile = BuildFile(
                "maven",
                pomXml(artifactId, config.basePackage, config.projectVersion, projectName),
              ),
            )

          // ---------------------------------------------------------------------------
          // File writing
          // ---------------------------------------------------------------------------

          private def writeProject(project: SpringBootProject, outputDir: Path): ZIO[Any, TransformError, Unit] =
            val projectDir = outputDir.resolve(sanitizeJavaIdentifier(project.projectName).toLowerCase)
            val basePkg    = s"${config.basePackage}.${sanitizeJavaIdentifier(project.projectName).toLowerCase}"
            val mainJava   = projectDir.resolve("src/main/java")
            val mainRes    = projectDir.resolve("src/main/resources")
            for
              _ <-
                fileService.ensureDirectory(mainJava).mapError(fe => TransformError.WriteFailed(mainJava, fe.message))
              _ <- fileService.ensureDirectory(mainRes).mapError(fe => TransformError.WriteFailed(mainRes, fe.message))
              _ <- fileService
                     .writeFileAtomic(projectDir.resolve("pom.xml"), project.buildFile.content)
                     .mapError(fe => TransformError.WriteFailed(projectDir.resolve("pom.xml"), fe.message))
              _ <- fileService
                     .writeFileAtomic(mainRes.resolve("application.yml"), applicationYaml(project))
                     .mapError(fe => TransformError.WriteFailed(mainRes.resolve("application.yml"), fe.message))
              _ <- fileService
                     .writeFileAtomic(
                       mainJava.resolve(packagePath(basePkg)).resolve("Application.java"),
                       applicationClass(basePkg),
                     )
                     .mapError(fe =>
                       TransformError.WriteFailed(
                         mainJava.resolve(packagePath(basePkg)).resolve("Application.java"),
                         fe.message,
                       )
                     )
              _ <- fileService
                     .writeFileAtomic(
                       mainJava.resolve(packagePath(s"$basePkg.config")).resolve("OpenApiConfig.java"),
                       openApiConfigClass(s"$basePkg.config"),
                     )
                     .mapError(fe =>
                       TransformError.WriteFailed(
                         mainJava.resolve(packagePath(s"$basePkg.config")).resolve("OpenApiConfig.java"),
                         fe.message,
                       )
                     )
              _ <- writeEntityFiles(project, mainJava, basePkg)
              _ <- writeServiceFiles(project, mainJava, basePkg)
              _ <- writeControllerFiles(project, mainJava, basePkg)
              _ <- writeRepositoryFiles(project, mainJava, basePkg)
            yield ()

          private def writeEntityFiles(
            project: SpringBootProject,
            mainJava: Path,
            basePkg: String,
          ): ZIO[Any, TransformError, Unit] =
            ZIO.foreachDiscard(project.entities) { entity =>
              val path    = mainJava.resolve(packagePath(s"$basePkg.entity")).resolve(s"${entity.className}.java")
              val content =
                if entity.sourceCode.nonEmpty then entity.sourceCode else renderEntity(basePkg, entity)
              fileService.writeFileAtomic(path, content).mapError(fe => TransformError.WriteFailed(path, fe.message))
            }

          private def writeServiceFiles(
            project: SpringBootProject,
            mainJava: Path,
            basePkg: String,
          ): ZIO[Any, TransformError, Unit] =
            ZIO.foreachDiscard(project.services) { service =>
              val path = mainJava.resolve(packagePath(s"$basePkg.service")).resolve(s"${service.name}.java")
              fileService
                .writeFileAtomic(path, renderService(basePkg, service))
                .mapError(fe => TransformError.WriteFailed(path, fe.message))
            }

          private def writeControllerFiles(
            project: SpringBootProject,
            mainJava: Path,
            basePkg: String,
          ): ZIO[Any, TransformError, Unit] =
            ZIO.foreachDiscard(project.controllers) { controller =>
              val path = mainJava.resolve(packagePath(s"$basePkg.controller")).resolve(s"${controller.name}.java")
              fileService
                .writeFileAtomic(path, renderController(basePkg, controller, project.services.headOption.map(_.name)))
                .mapError(fe => TransformError.WriteFailed(path, fe.message))
            }

          private def writeRepositoryFiles(
            project: SpringBootProject,
            mainJava: Path,
            basePkg: String,
          ): ZIO[Any, TransformError, Unit] =
            ZIO.foreachDiscard(project.repositories) { repo =>
              val path    = mainJava.resolve(packagePath(s"$basePkg.repository")).resolve(s"${repo.name}.java")
              val content = if repo.sourceCode.nonEmpty then repo.sourceCode else renderRepository(basePkg, repo)
              fileService.writeFileAtomic(path, content).mapError(fe => TransformError.WriteFailed(path, fe.message))
            }

          // ---------------------------------------------------------------------------
          // Java source rendering
          // ---------------------------------------------------------------------------

          private def renderEntity(basePkg: String, entity: JavaEntity): String =
            val fieldLines = entity.fields.map { field =>
              val ann  = field.annotations.map(a => s"  $a").mkString("\n")
              val line = s"  private ${field.javaType} ${field.name};"
              if ann.nonEmpty then s"$ann\n$line" else line
            }
            s"""package $basePkg.entity;
               |
               |import lombok.Data;
               |import lombok.NoArgsConstructor;
               |import lombok.AllArgsConstructor;
               |import jakarta.persistence.*;
               |
               |${entity.annotations.mkString("\n")}
               |@Data
               |@NoArgsConstructor
               |@AllArgsConstructor
               |public class ${entity.className} {
               |${fieldLines.mkString("\n")}
               |}
               |""".stripMargin

          private def renderService(basePkg: String, service: JavaService): String =
            val methods = service.methods.map { m =>
              val params = m.parameters.map(p => s"${p.javaType} ${p.name}").mkString(", ")
              val body   = normalizeJavaBody(m.body)
              s"""  public ${m.returnType} ${m.name}($params) {
                 |    ${body}
                 |  }""".stripMargin
            }
            s"""package $basePkg.service;
               |
               |import org.springframework.stereotype.Service;
               |import org.springframework.transaction.annotation.Transactional;
               |
               |@Service
               |@Transactional
               |public class ${service.name} {
               |${methods.mkString("\n\n")}
               |}
               |""".stripMargin

          private def normalizeJavaBody(body: String): String =
            body
              .replace("\\n", "\n")
              .replace("\\\"", "\"")

          private def renderController(
            basePkg: String,
            controller: JavaController,
            serviceName: Option[String],
          ): String =
            val svcField  = serviceName match
              case Some(name) =>
                s"""  private final $name service;
                   |
                   |  public ${controller.name}($name service) {
                   |    this.service = service;
                   |  }
                   |""".stripMargin
              case None       => ""
            val endpoints = controller.endpoints.map { e =>
              val mapping = e.method match
                case HttpMethod.GET    => "@GetMapping"
                case HttpMethod.POST   => "@PostMapping"
                case HttpMethod.PUT    => "@PutMapping"
                case HttpMethod.DELETE => "@DeleteMapping"
                case HttpMethod.PATCH  => "@PatchMapping"
              s"""  $mapping("${e.path}")
                 |  public org.springframework.http.ResponseEntity<String> ${e.methodName}() {
                 |    return org.springframework.http.ResponseEntity.ok("OK");
                 |  }""".stripMargin
            }
            s"""package $basePkg.controller;
               |
               |import org.springframework.web.bind.annotation.*;
               |import io.swagger.v3.oas.annotations.Operation;
               |
               |@RestController
               |@RequestMapping("${controller.basePath}")
               |public class ${controller.name} {
               |$svcField
               |${endpoints.mkString("\n\n")}
               |}
               |""".stripMargin

          private def renderRepository(basePkg: String, repo: JavaRepository): String =
            s"""package $basePkg.repository;
               |
               |import org.springframework.data.jpa.repository.JpaRepository;
               |
               |${repo.annotations.mkString("\n")}
               |public interface ${repo.name} extends JpaRepository<${repo.entityName}, ${repo.idType}> {
               |}
               |""".stripMargin

          private def applicationClass(basePkg: String): String =
            s"""package $basePkg;
               |
               |import org.springframework.boot.SpringApplication;
               |import org.springframework.boot.autoconfigure.SpringBootApplication;
               |
               |@SpringBootApplication
               |public class Application {
               |  public static void main(String[] args) {
               |    SpringApplication.run(Application.class, args);
               |  }
               |}
               |""".stripMargin

          private def applicationYaml(project: SpringBootProject): String =
            val appName = sanitizeJavaIdentifier(project.projectName).toLowerCase
            s"""spring:
               |  application:
               |    name: $appName
               |  datasource:
               |    url: jdbc:h2:mem:$appName
               |    driverClassName: org.h2.Driver
               |    username: sa
               |    password:
               |  jpa:
               |    hibernate:
               |      ddl-auto: update
               |    show-sql: true
               |springdoc:
               |  api-docs:
               |    path: /api-docs
               |  swagger-ui:
               |    path: /swagger-ui.html
               |""".stripMargin

          private def pomXml(
            artifactId: String,
            groupId: String,
            version: String,
            projectName: String,
          ): String =
            s"""<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
               |  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
               |  <modelVersion>4.0.0</modelVersion>
               |  <groupId>$groupId</groupId>
               |  <artifactId>$artifactId</artifactId>
               |  <version>$version</version>
               |  <name>$projectName</name>
               |  <parent>
               |    <groupId>org.springframework.boot</groupId>
               |    <artifactId>spring-boot-starter-parent</artifactId>
               |    <version>3.2.0</version>
               |  </parent>
               |  <dependencies>
               |    <dependency>
               |      <groupId>org.springframework.boot</groupId>
               |      <artifactId>spring-boot-starter-web</artifactId>
               |    </dependency>
               |    <dependency>
               |      <groupId>org.springframework.boot</groupId>
               |      <artifactId>spring-boot-starter-data-jpa</artifactId>
               |    </dependency>
               |    <dependency>
               |      <groupId>org.projectlombok</groupId>
               |      <artifactId>lombok</artifactId>
               |      <optional>true</optional>
               |    </dependency>
               |    <dependency>
               |      <groupId>org.springdoc</groupId>
               |      <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
               |      <version>2.3.0</version>
               |    </dependency>
               |    <dependency>
               |      <groupId>com.h2database</groupId>
               |      <artifactId>h2</artifactId>
               |      <scope>runtime</scope>
               |    </dependency>
               |  </dependencies>
               |  <build>
               |    <plugins>
               |      <plugin>
               |        <groupId>org.springframework.boot</groupId>
               |        <artifactId>spring-boot-maven-plugin</artifactId>
               |      </plugin>
               |    </plugins>
               |  </build>
               |</project>
               |""".stripMargin

          private def openApiConfigClass(pkg: String): String =
            s"""package $pkg;
               |
               |import org.springframework.context.annotation.Bean;
               |import org.springframework.context.annotation.Configuration;
               |import io.swagger.v3.oas.models.OpenAPI;
               |import io.swagger.v3.oas.models.info.Info;
               |
               |@Configuration
               |public class OpenApiConfig {
               |  @Bean
               |  public OpenAPI openAPI() {
               |    return new OpenAPI().info(new Info().title("COBOL Migration API").version("1.0.0"));
               |  }
               |}
               |""".stripMargin

          // ---------------------------------------------------------------------------
          // Helpers
          // ---------------------------------------------------------------------------

          private def packagePath(pkg: String): Path =
            Path.of(pkg.replace('.', '/'))

          private def programIdFor(name: String): String =
            sanitizeJavaIdentifier(name).toUpperCase

          private def generateRepositories(entity: JavaEntity): List[JavaRepository] =
            List(
              JavaRepository(
                name = s"${entity.className}Repository",
                entityName = entity.className,
                idType = "Long",
                packageName = s"${entity.packageName}.repository",
                annotations = List("@org.springframework.stereotype.Repository"),
                sourceCode = "",
              )
            )

          private def ensureEntityDefaults(entity: JavaEntity, analysis: CobolAnalysis): JavaEntity =
            val className    =
              if entity.className.nonEmpty then entity.className else programIdFor(analysis.file.name).capitalize
            val packageName  =
              if entity.packageName.nonEmpty then entity.packageName
              else s"${config.basePackage}.${programIdFor(analysis.file.name).toLowerCase}.entity"
            val withDefaults = entity.copy(className = className, packageName = packageName)
            val source       =
              if withDefaults.sourceCode.nonEmpty then withDefaults.sourceCode
              else renderEntity(packageName.stripSuffix(".entity"), withDefaults)
            withDefaults.copy(sourceCode = source)
        }
    }
