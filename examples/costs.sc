//> using dep "io.github.riccardomerolla::llm4zio-runner:3.19.0"
//> using scala "3.8.3"
//> using jvm 21

/** Cross-run token & cost report over the append-only cost ledger.
  *
  * Every flow run appends one structured record (stage × agent × model cells + totals) to
  * `costs.jsonl` under the workspace's `.llm4zio/` — the durable counterpart of the console
  * footer. This script aggregates those records: per correlation label (LLM4ZIO_RUN_LABEL,
  * falling back to the repo id), it lists the runs and rolls the cells up per stage, then per
  * agent/model — so "what did five Gate iterations of the extract flow cost, judge vs analyst"
  * is one command:
  *
  *   cd <the workspace you run flows from>
  *   scala-cli run costs.sc                # everything
  *   scala-cli run costs.sc -- acctxfr    # only groups whose label/repo contains "acctxfr"
  *
  * Read-only, no LLM seats, no git. Costs are the PriceList estimates captured at run time.
  */

import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*

import zio.*

import llm4zio.flow.*

def ledgers(root: Path): List[Path] =
  val base = root.resolve(".llm4zio")
  if !Files.isDirectory(base) then Nil
  else
    val stream = Files.list(base)
    val nested =
      try stream.iterator().asScala.filter(Files.isDirectory(_)).toList
      finally stream.close()
    (base :: nested).filter(d => Files.exists(CostLedger.path(d)))

def money(c: Option[Double]): String = c.fold("     -  ")(v => f"$$$v%7.4f")

def tokens(n: Long): String =
  if n >= 1_000_000 then f"${n / 1e6}%.1fM" else if n >= 1_000 then f"${n / 1e3}%.1fk" else n.toString

def cellLine(c: CostCell): String =
  f"      ${c.agent}%-12s ${c.model}%-24s ${tokens(c.prompt)}%8s in  ${tokens(c.completion)}%8s out  ${money(c.costUsd)}"

val filter = args.headOption.map(_.trim.toLowerCase).filter(_.nonEmpty)

val program =
  for
    cwd     <- ZIO.succeed(Path.of(".").toAbsolutePath.normalize)
    records <- ZIO.foreach(ledgers(cwd))(CostLedger.load).map(_.flatten)
    matched  = records.filter(r =>
                 filter.forall(f => (r.label.getOrElse("") + " " + r.repo).toLowerCase.contains(f))
               )
    _       <- ZIO.when(matched.isEmpty)(
                 Console.printLine(s"no cost records under $cwd/.llm4zio — run a flow first").as(())
               )
    groups   = matched.groupBy(r => r.label.getOrElse(r.repo)).toList.sortBy(_._1)
    _       <- ZIO.foreachDiscard(groups) { (group, rs) =>
                 val total = rs.flatMap(_.totalCostUsd) match
                   case Nil => None
                   case xs  => Some(xs.sum)
                 for
                   _ <- Console.printLine(s"\n== $group  (${rs.size} run(s), total ${money(total).trim})")
                   _ <- ZIO.foreachDiscard(rs.sortBy(_.at)) { r =>
                          Console.printLine(
                            f"  ${r.at.take(16)}  ${r.runId}%-22s ${money(r.totalCostUsd)}  ${r.promptHead.take(60)}"
                          )
                        }
                   _ <- Console.printLine("  by stage:")
                   _ <- ZIO.foreachDiscard(
                          CostLedger.mergeCells(rs).groupBy(_.stage).toList.sortBy(_._1)
                        ) { (stage, cells) =>
                          val stageCost = cells.flatMap(_.costUsd) match
                            case Nil => None
                            case xs  => Some(xs.sum)
                          Console.printLine(s"    $stage  ${money(stageCost).trim}") *>
                            ZIO.foreachDiscard(cells)(c => Console.printLine(cellLine(c)))
                        }
                 yield ()
               }
    grand    = matched.flatMap(_.totalCostUsd) match
                 case Nil => None
                 case xs  => Some(xs.sum)
    _       <- Console.printLine(
                 s"\nTotal across ${matched.size} run(s): ${money(grand).trim}" +
                   s"  (estimates from the pricing table as of ${PriceList.asOf})"
               )
  yield ()

Unsafe.unsafe { implicit u =>
  Runtime.default.unsafe.run(program.catchAll(e => Console.printLine(s"costs report failed: $e").orDie)).getOrThrow()
}
