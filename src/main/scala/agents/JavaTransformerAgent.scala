package agents

import java.nio.file.Path

import zio.*
import zio.json.*

import core.{ AIService, FileService, Logger, ResponseParser }
import models.*
import prompts.PromptTemplates

/** JavaTransformerAgent - Transform COBOL programs into Spring Boot microservices
  *
  * Responsibilities:
  *   - Convert COBOL data structures to Java classes/records
  *   - Transform PROCEDURE DIVISION to service methods
  *   - Generate Spring Boot annotations and configurations
  *   - Implement REST endpoints for program entry points
  *   - Create Spring Data JPA entities from file definitions
  *   - Handle error scenarios with try-catch blocks
  *
  * Interactions:
  *   - Input from: CobolAnalyzerAgent, DependencyMapperAgent
  *   - Output consumed by: ValidationAgent, DocumentationAgent
  */
trait JavaTransformerAgent:
  def transform(
    analysis: CobolAnalysis,
    dependencyGraph: DependencyGraph,
  ): ZIO[Any, TransformError, SpringBootProject]

object JavaTransformerAgent:
  def transform(
    analysis: CobolAnalysis,
    dependencyGraph: DependencyGraph,
  ): ZIO[JavaTransformerAgent, TransformError, SpringBootProject] =
    ZIO.serviceWithZIO[JavaTransformerAgent](_.transform(analysis, dependencyGraph))

  val live
    : ZLayer[AIService & ResponseParser & FileService & MigrationConfig, Nothing, JavaTransformerAgent] =
    ZLayer.fromFunction {
      (
        aiService: AIService,
        responseParser: ResponseParser,
        fileService: FileService,
        config: MigrationConfig,
      ) =>
        new JavaTransformerAgent {
          override def transform(
            analysis: CobolAnalysis,
            dependencyGraph: DependencyGraph,
          ): ZIO[Any, TransformError, SpringBootProject] =
            for
              _           <- Logger.info(s"Transforming ${analysis.file.name} to Spring Boot")
              entity      <- generateEntity(analysis)
              service     <- generateService(analysis, dependencyGraph)
              controller  <- generateController(analysis, service.name)
              repositories = generateRepositories(entity)
              now         <- Clock.instant
              project      = buildProject(analysis, now, entity, service, controller, repositories)
              _           <- writeProject(project, config.outputDir)
              _           <- Logger.info(s"Generated Spring Boot project: ${project.projectName}")
            yield project

          private def generateEntity(analysis: CobolAnalysis): ZIO[Any, TransformError, JavaEntity] =
            val prompt = PromptTemplates.JavaTransformer.generateEntity(analysis)
            for
              response <- aiService
                            .execute(prompt)
                            .mapError(e => TransformError.AIFailed(analysis.file.name, e.message))
              entity   <- responseParser
                            .parse[JavaEntity](response)
                            .mapError(e => TransformError.ParseFailed(analysis.file.name, e.message))
              enriched  = ensureEntityDefaults(entity, analysis)
            yield enriched

          private def generateService(
            analysis: CobolAnalysis,
            graph: DependencyGraph,
          ): ZIO[Any, TransformError, JavaService] =
            val deps   = graph.serviceCandidates
            val prompt = PromptTemplates.JavaTransformer.generateService(analysis, deps)
            for
              response <- aiService
                            .execute(prompt)
                            .mapError(e => TransformError.AIFailed(analysis.file.name, e.message))
              service  <- responseParser
                            .parse[JavaService](response)
                            .mapError(e => TransformError.ParseFailed(analysis.file.name, e.message))
            yield service

          private def generateController(
            analysis: CobolAnalysis,
            serviceName: String,
          ): ZIO[Any, TransformError, JavaController] =
            val prompt = PromptTemplates.JavaTransformer.generateController(analysis, serviceName)
            for
              response   <- aiService
                              .execute(prompt)
                              .mapError(e => TransformError.AIFailed(analysis.file.name, e.message))
              controller <- responseParser
                              .parse[JavaController](response)
                              .mapError(e => TransformError.ParseFailed(analysis.file.name, e.message))
            yield controller

          private def buildProject(
            analysis: CobolAnalysis,
            generatedAt: java.time.Instant,
            entity: JavaEntity,
            service: JavaService,
            controller: JavaController,
            repositories: List[JavaRepository],
          ): SpringBootProject =
            val programId = programIdFor(analysis.file.name)
            SpringBootProject(
              projectName = programId,
              sourceProgram = analysis.file.name,
              generatedAt = generatedAt,
              entities = List(entity),
              services = List(service),
              controllers = List(controller),
              repositories = repositories,
              configuration = ProjectConfiguration(
                groupId = "com.example",
                artifactId = programId.toLowerCase,
                dependencies =
                  List("spring-boot-starter-web", "spring-boot-starter-data-jpa", "lombok", "springdoc-openapi"),
              ),
              buildFile = BuildFile("maven", pomXml(programId.toLowerCase)),
            )

          private def writeProject(project: SpringBootProject, outputDir: Path): ZIO[Any, TransformError, Unit] =
            val projectDir = outputDir.resolve(project.projectName.toLowerCase)
            val basePkg    = s"com.example.${project.projectName.toLowerCase}"
            val mainJava   = projectDir.resolve("src/main/java")
            val mainRes    = projectDir.resolve("src/main/resources")
            for
              _ <-
                fileService.ensureDirectory(mainJava).mapError(fe => TransformError.WriteFailed(mainJava, fe.message))
              _ <- fileService.ensureDirectory(mainRes).mapError(fe => TransformError.WriteFailed(mainRes, fe.message))
              _ <- writeFileAtomic(projectDir.resolve("pom.xml"), project.buildFile.content)
              _ <- writeFileAtomic(mainRes.resolve("application.yml"), applicationYaml(project))
              _ <- writeFileAtomic(
                     mainJava.resolve(packagePath(basePkg)).resolve("Application.java"),
                     applicationClass(basePkg),
                   )
              _ <- writeFileAtomic(
                     mainJava.resolve(packagePath(s"$basePkg.config")).resolve("OpenApiConfig.java"),
                     openApiConfigClass(s"$basePkg.config"),
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
              writeFileAtomic(path, content)
            }

          private def writeServiceFiles(
            project: SpringBootProject,
            mainJava: Path,
            basePkg: String,
          ): ZIO[Any, TransformError, Unit] =
            ZIO.foreachDiscard(project.services) { service =>
              val path = mainJava.resolve(packagePath(s"$basePkg.service")).resolve(s"${service.name}.java")
              writeFileAtomic(path, renderService(basePkg, service))
            }

          private def writeControllerFiles(
            project: SpringBootProject,
            mainJava: Path,
            basePkg: String,
          ): ZIO[Any, TransformError, Unit] =
            ZIO.foreachDiscard(project.controllers) { controller =>
              val path = mainJava.resolve(packagePath(s"$basePkg.controller")).resolve(s"${controller.name}.java")
              writeFileAtomic(path, renderController(basePkg, controller, project.services.headOption.map(_.name)))
            }

          private def writeRepositoryFiles(
            project: SpringBootProject,
            mainJava: Path,
            basePkg: String,
          ): ZIO[Any, TransformError, Unit] =
            ZIO.foreachDiscard(project.repositories) { repo =>
              val path    = mainJava.resolve(packagePath(s"$basePkg.repository")).resolve(s"${repo.name}.java")
              val content = if repo.sourceCode.nonEmpty then repo.sourceCode else renderRepository(basePkg, repo)
              writeFileAtomic(path, content)
            }

          private def writeFileAtomic(path: Path, content: String): ZIO[Any, TransformError, Unit] =
            for
              suffix  <- ZIO
                           .attemptBlocking(java.util.UUID.randomUUID().toString)
                           .mapError(e => TransformError.WriteFailed(path, e.getMessage))
              tempPath = path.resolveSibling(s"${path.getFileName}.tmp.$suffix")
              _       <- fileService
                           .writeFile(tempPath, content)
                           .mapError(fe => TransformError.WriteFailed(tempPath, fe.message))
              _       <- ZIO
                           .attemptBlocking {
                             import java.nio.file.StandardCopyOption
                             try
                               java.nio.file.Files.move(
                                 tempPath,
                                 path,
                                 StandardCopyOption.REPLACE_EXISTING,
                                 StandardCopyOption.ATOMIC_MOVE,
                               )
                             catch
                               case _: java.nio.file.AtomicMoveNotSupportedException =>
                                 java.nio.file.Files.move(
                                   tempPath,
                                   path,
                                   StandardCopyOption.REPLACE_EXISTING,
                                 )
                           }
                           .mapError(e => TransformError.WriteFailed(path, e.getMessage))
            yield ()

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
            s"""spring:
               |  application:
               |    name: ${project.projectName.toLowerCase}
               |  datasource:
               |    url: jdbc:h2:mem:${project.projectName.toLowerCase}
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

          private def pomXml(artifactId: String): String =
            s"""<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
               |  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
               |  <modelVersion>4.0.0</modelVersion>
               |  <groupId>com.example</groupId>
               |  <artifactId>$artifactId</artifactId>
               |  <version>0.0.1-SNAPSHOT</version>
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

          private def packagePath(pkg: String): Path =
            Path.of(pkg.replace('.', '/'))

          private def programIdFor(name: String): String =
            name.replaceAll("\\.(cbl|cob)$", "").toUpperCase

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
              else s"com.example.${programIdFor(analysis.file.name).toLowerCase}.entity"
            val withDefaults = entity.copy(className = className, packageName = packageName)
            val source       =
              if withDefaults.sourceCode.nonEmpty then withDefaults.sourceCode
              else renderEntity(packageName.stripSuffix(".entity"), withDefaults)
            withDefaults.copy(sourceCode = source)

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
        }
    }
