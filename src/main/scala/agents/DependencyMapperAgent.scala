package agents

import java.nio.file.Path

import zio.*
import zio.json.*

import core.{ FileService, Logger }
import models.*

/** DependencyMapperAgent - Map relationships between COBOL programs and copybooks
  *
  * Responsibilities:
  *   - Analyze COPY statements and program calls
  *   - Build dependency graph
  *   - Calculate complexity metrics
  *   - Generate Mermaid diagrams
  *   - Identify shared copybooks as service candidates
  *
  * Interactions:
  *   - Input from: CobolDiscoveryAgent, CobolAnalyzerAgent
  *   - Output consumed by: JavaTransformerAgent, DocumentationAgent
  */
trait DependencyMapperAgent:
  def mapDependencies(analyses: List[CobolAnalysis]): ZIO[Any, MappingError, DependencyGraph]

object DependencyMapperAgent:
  def mapDependencies(analyses: List[CobolAnalysis]): ZIO[DependencyMapperAgent, MappingError, DependencyGraph] =
    ZIO.serviceWithZIO[DependencyMapperAgent](_.mapDependencies(analyses))

  val live: ZLayer[FileService, Nothing, DependencyMapperAgent] = ZLayer.fromFunction { (fileService: FileService) =>
    new DependencyMapperAgent {
      private val reportDir = Path.of("reports/mapping")

      override def mapDependencies(analyses: List[CobolAnalysis]): ZIO[Any, MappingError, DependencyGraph] =
        for
          _    <- Logger.info(s"Building dependency graph for ${analyses.size} programs")
          _    <- ZIO.fail(MappingError.EmptyAnalysis).when(analyses.isEmpty)
          nodes = buildNodes(analyses)
          edges = buildEdges(analyses, nodes)
          graph = DependencyGraph(
                    nodes = nodes,
                    edges = edges,
                    serviceCandidates = identifyServiceCandidates(edges),
                  )
          _    <- writeReports(graph)
          _    <- Logger.info(s"Graph complete: ${nodes.size} nodes, ${edges.size} edges")
        yield graph

      private def buildNodes(analyses: List[CobolAnalysis]): List[DependencyNode] =
        val programNodes  = analyses.map { analysis =>
          DependencyNode(
            id = programId(analysis.file.name),
            name = analysis.file.name,
            nodeType = NodeType.Program,
            complexity = analysis.complexity.cyclomaticComplexity,
          )
        }
        val copybookNodes = analyses
          .flatMap(_.copybooks)
          .distinct
          .map { copybook =>
            DependencyNode(
              id = copybookId(copybook),
              name = copybook,
              nodeType = NodeType.Copybook,
              complexity = 0,
            )
          }
        (programNodes ++ copybookNodes).groupBy(_.id).values.map(_.head).toList

      private def buildEdges(analyses: List[CobolAnalysis], nodes: List[DependencyNode]): List[DependencyEdge] =
        val programIds = nodes.filter(_.nodeType == NodeType.Program).map(_.id).toSet
        val includes   = analyses.flatMap { analysis =>
          val from = programId(analysis.file.name)
          analysis.copybooks.map { copybook =>
            DependencyEdge(
              from = from,
              to = copybookId(copybook),
              edgeType = EdgeType.Includes,
            )
          }
        }
        val calls      = analyses.flatMap { analysis =>
          val from = programId(analysis.file.name)
          extractCalls(analysis).flatMap { target =>
            val to = programId(target)
            if programIds.contains(to) then
              Some(
                DependencyEdge(
                  from = from,
                  to = to,
                  edgeType = EdgeType.Calls,
                )
              )
            else None
          }
        }
        (includes ++ calls).groupBy(e => (e.from, e.to, e.edgeType)).values.map(_.head).toList

      private def extractCalls(analysis: CobolAnalysis): List[String] =
        val fromStatements = analysis.procedures.flatMap(_.statements).flatMap { stmt =>
          callTargets(stmt.content)
        }
        val fromType       = analysis.procedures.flatMap(_.statements).flatMap { stmt =>
          if stmt.statementType.equalsIgnoreCase("CALL") then callTargets(stmt.content) else Nil
        }
        (fromStatements ++ fromType).distinct

      private def callTargets(content: String): List[String] =
        val pattern = "(?i)\\bCALL\\s+['\\\"]?([A-Z0-9_-]+)['\\\"]?".r
        pattern.findAllMatchIn(content).map(_.group(1)).toList

      private def identifyServiceCandidates(edges: List[DependencyEdge]): List[String] =
        edges
          .filter(_.edgeType == EdgeType.Includes)
          .groupBy(_.to)
          .collect { case (copybook, refs) if refs.map(_.from).distinct.size >= 2 => copybook }
          .toList

      private def writeReports(graph: DependencyGraph): ZIO[Any, MappingError, Unit] =
        for
          _ <-
            fileService.ensureDirectory(reportDir).mapError(fe => MappingError.ReportWriteFailed(reportDir, fe.message))
          _ <- fileService
                 .writeFileAtomic(reportDir.resolve("dependency-graph.json"), graph.toJsonPretty)
                 .mapError(fe => MappingError.ReportWriteFailed(reportDir.resolve("dependency-graph.json"), fe.message))
          _ <- fileService
                 .writeFileAtomic(
                   reportDir.resolve("dependency-diagram.md"),
                   List("```mermaid", DependencyGraphBuilder.toMermaid(graph), "```").mkString("\n"),
                 )
                 .mapError(fe =>
                   MappingError.ReportWriteFailed(reportDir.resolve("dependency-diagram.md"), fe.message)
                 )
          _ <- fileService
                 .writeFileAtomic(reportDir.resolve("migration-order.md"), renderMigrationOrder(graph))
                 .mapError(fe => MappingError.ReportWriteFailed(reportDir.resolve("migration-order.md"), fe.message))
        yield ()

      private def renderMigrationOrder(graph: DependencyGraph): String =
        val programNodes = graph.nodes.filter(_.nodeType == NodeType.Program).map(_.id)
        val order        = topologicalSort(programNodes, graph.edges)
        val cycles       = detectCycles(programNodes, graph.edges)
        val orphans      = orphanPrograms(programNodes, graph.edges)
        val coupled      = highlyCoupled(programNodes, graph.edges, 2)
        val lines        = List(
          "# Migration Order",
          "",
          "## Recommended order",
          order.map(id => s"- $id").mkString("\n"),
          "",
          "## Circular dependencies",
          if cycles.isEmpty then "- None" else cycles.map(c => s"- ${c.mkString(" -> ")}").mkString("\n"),
          "",
          "## Orphan programs",
          if orphans.isEmpty then "- None" else orphans.map(id => s"- $id").mkString("\n"),
          "",
          "## Highly coupled programs",
          if coupled.isEmpty then "- None"
          else coupled.map { case (id, degree) => s"- $id (degree: $degree)" }.mkString("\n"),
        )
        lines.mkString("\n")

      private def topologicalSort(programIds: List[String], edges: List[DependencyEdge]): List[String] =
        val graph        = edges.filter(_.edgeType == EdgeType.Calls).groupBy(_.from).view.mapValues(_.map(_.to)).toMap
        val baseInDegree = programIds.map(id => id -> 0).toMap
        val counts       = edges
          .filter(_.edgeType == EdgeType.Calls)
          .foldLeft(baseInDegree) {
            case (acc, edge) =>
              acc.updated(edge.to, acc.getOrElse(edge.to, 0) + 1)
          }
        val initialQueue = programIds.filter(id => counts.getOrElse(id, 0) == 0)

        @annotation.tailrec
        def loop(
          queue: List[String],
          inDegree: Map[String, Int],
          acc: List[String],
        ): (List[String], Map[String, Int]) =
          queue match
            case Nil          => (acc.reverse, inDegree)
            case node :: rest =>
              val (updatedDegrees, newlyReady) =
                graph.getOrElse(node, Nil).foldLeft((inDegree, List.empty[String])) {
                  case ((degrees, ready), to) =>
                    val next    = degrees.getOrElse(to, 0) - 1
                    val updated = degrees.updated(to, next)
                    if next == 0 then (updated, to :: ready) else (updated, ready)
                }
              loop(rest ++ newlyReady.reverse, updatedDegrees, node :: acc)

        val (ordered, _) = loop(initialQueue, counts, Nil)
        val remaining    = programIds.filterNot(ordered.contains)
        ordered ++ remaining

      private def detectCycles(programIds: List[String], edges: List[DependencyEdge]): List[List[String]] =
        val graph   = edges.filter(_.edgeType == EdgeType.Calls).groupBy(_.from).view.mapValues(_.map(_.to)).toMap
        val initial = CycleState(visited = Set.empty, stack = Set.empty, cycles = List.empty)

        def dfs(node: String, path: List[String], state: CycleState): CycleState =
          if state.stack.contains(node) then
            val cycleStart = path.indexOf(node)
            if cycleStart >= 0 then state.copy(cycles = (path.drop(cycleStart) :+ node) :: state.cycles)
            else state
          else if state.visited.contains(node) then state
          else
            val entered   = state.copy(
              visited = state.visited + node,
              stack = state.stack + node,
            )
            val traversed = graph.getOrElse(node, Nil).foldLeft(entered) { (acc, next) =>
              dfs(next, path :+ next, acc)
            }
            traversed.copy(stack = traversed.stack - node)

        programIds
          .foldLeft(initial)((state, id) => dfs(id, List(id), state))
          .cycles
          .reverse
          .distinct

      private def orphanPrograms(programIds: List[String], edges: List[DependencyEdge]): List[String] =
        val linked = edges.filter(_.edgeType == EdgeType.Calls).flatMap(e => List(e.from, e.to)).toSet
        programIds.filterNot(linked.contains)

      private def highlyCoupled(
        programIds: List[String],
        edges: List[DependencyEdge],
        threshold: Int,
      ): List[(String, Int)] =
        val degrees = programIds.map { id =>
          val degree = edges.count(e => e.edgeType == EdgeType.Calls && (e.from == id || e.to == id))
          id -> degree
        }
        degrees.filter(_._2 >= threshold).sortBy(-_._2)

      private def programId(name: String): String =
        name.replaceAll("\\.(cbl|cob)$", "").toUpperCase

      private def copybookId(name: String): String =
        name.replaceAll("\\.(cpy)$", "").toUpperCase

      private case class CycleState(
        visited: Set[String],
        stack: Set[String],
        cycles: List[List[String]],
      )
    }
  }
