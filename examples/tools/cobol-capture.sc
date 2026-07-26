//> using dep "io.github.riccardomerolla::llm4zio-runner:3.23.0"
//> using scala "3.8.3"
//> using jvm 21

/** Golden-vector capture for the Meridian COBOL fixture (ACCTXFR).
  *
  * Executes the real 1988 batch program under GnuCOBOL and records what it actually does as
  * captured-tier equivalence vectors (`flow.Equiv` JSONL) — the legacy side of
  * modernize-verify.sc's behavioural-equivalence proof.
  *
  * How: each `EXEC SQL … END-EXEC` block is rewritten into a CALL to the DBSHIM subprograms
  * (DBSHIM.cbl next to this script) backed by a pipe-separated account file and an observation
  * log; one vector = one program execution. Probe vectors need only `inputs` (observations are
  * overwritten with what the COBOL actually did). Input conventions match the pack's
  * prompts/vectors.md: XFER-* fields verbatim, "state:ACCOUNT:<id>" rows, "state:RUN-DATE"
  * (drives COB_CURRENT_DATE so POST_DATE/LAST_ACTIVITY are deterministic).
  *
  * Requires cobc (GnuCOBOL) on PATH — `brew install gnucobol`.
  *
  * Run:  scala-cli run tools/cobol-capture.sc -- \
  *         --cobol fixtures/legacy-bank/cobol \
  *         --probes fixtures/legacy-bank/probes/ACCTXFR.probes.jsonl \
  *         --out vectors/ACCTXFR.captured.jsonl
  */

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }

import zio.json.ast.Json
import zio.process.Command
import zio.{ Runtime, Task, Unsafe, ZIO }

import llm4zio.flow.{ Equiv, EquivVector, VectorStore }

// --- EXEC SQL preprocessing --------------------------------------------------

val SqlcaWs = List(
  "       01  SQLCA.",
  "           05  SQLCODE             PIC S9(09) COMP-5 VALUE +0.",
)

def classify(block: String): List[String] =
  if block.contains("INCLUDE SQLCA") then SqlcaWs
  else if block.contains("INTO :SRC-") then List("           CALL 'DBSEL' USING SQLCA SRC-REC")
  else if block.contains("INTO :TGT-") then List("           CALL 'DBSEL' USING SQLCA TGT-REC")
  else if block.contains("UPDATE ACCOUNT") && block.contains(":SRC-BALANCE") then
    List("           CALL 'DBUPD' USING SQLCA SRC-REC WS-POST-DATE 'S'")
  else if block.contains("UPDATE ACCOUNT") && block.contains(":TGT-BALANCE") then
    List("           CALL 'DBUPD' USING SQLCA TGT-REC WS-POST-DATE 'T'")
  else if block.contains("INSERT INTO LEDGER") then List("           CALL 'DBLED' USING SQLCA LEDG-ROW")
  else if block.contains("INSERT INTO XFER_AUDIT") then
    List(
      "           CALL 'DBAUD' USING SQLCA XFER-SEQ-NO SRC-ID",
      "               TGT-ID XFER-AMOUNT WS-XFER-FEE",
      "               WS-OD-TRIGGERED XFER-CHANNEL WS-POST-DATE",
    )
  else if block.contains("COMMIT") || block.contains("ROLLBACK") then List("           MOVE 0 TO SQLCODE")
  else sys.error(s"unclassifiable EXEC SQL block:\n$block")

final case class PreState(out: Vector[String], block: List[String], inSql: Boolean)

def preprocess(source: String): String =
  val done = source.linesIterator.foldLeft(PreState(Vector.empty, Nil, inSql = false)) { (st, line) =>
    val code = if line.length > 6 then line.substring(6) else ""
    if !st.inSql && code.matches("""\s*EXEC\s+SQL.*""") then st.copy(block = List(line), inSql = true)
    else if st.inSql then
      val block = st.block :+ line
      if !code.contains("END-EXEC") then st.copy(block = block)
      else
        val repl  = classify(block.mkString("\n"))
        val fixed =
          if code.contains("END-EXEC.") && !repl.last.endsWith(".") then repl.init :+ (repl.last + ".")
          else repl
        PreState(st.out ++ fixed, Nil, inSql = false)
    else
      // Mainframe partial-word COPY REPLACING needs GnuCOBOL's LEADING keyword.
      val emitted =
        if code.contains("COPY ") && code.contains("REPLACING ==") then
          line.replace("REPLACING ==", "REPLACING LEADING ==")
        else line
      st.copy(out = st.out :+ emitted)
  }
  done.out.mkString("", "\n", "\n")

// --- input serialization -----------------------------------------------------

val PosOverpunch = "0123456789".zip("{ABCDEFGHI").toMap
val NegOverpunch = "0123456789".zip("}JKLMNOPQR").toMap

/** Signed zoned-decimal (EBCDIC trailing overpunch) for PIC S9(9)V99 DISPLAY. */
def zoned(amount: String, digits: Int = 11): String =
  val cents = (BigDecimal(amount) * 100).toBigInt
  val body  = cents.abs.toString.reverse.padTo(digits, '0').reverse.mkString
  val table = if cents < 0 then NegOverpunch else PosOverpunch
  body.init + table(body.last)

def strField(inputs: Map[String, String], key: String, width: Int, default: String = ""): String =
  inputs.getOrElse(key, default).padTo(width, ' ').take(width)

def xferinRecord(inputs: Map[String, String]): String =
  (inputs.getOrElse("XFER-SEQ-NO", "1").reverse.padTo(7, '0').reverse.take(7)
    + strField(inputs, "XFER-SRC-ACCT", 10)
    + strField(inputs, "XFER-TGT-ACCT", 10)
    + zoned(inputs.getOrElse("XFER-AMOUNT", "0"))
    + strField(inputs, "XFER-CHANNEL", 3, "BRN")
    + strField(inputs, "XFER-REQ-DATE", 8)
    + strField(inputs, "XFER-MEMO", 25)).padTo(80, ' ')

def acctdbRows(inputs: Map[String, String]): List[String] =
  inputs.toList.sorted.collect {
    case (key, value) if key.startsWith("state:ACCOUNT:") =>
      val acctId = key.split(":", 3)(2)
      val cols   = value.split(",").toList.flatMap { part =>
        part.split("=", 2) match
          case Array(k, v) => List(k.trim -> v.trim)
          case _           => Nil
      }.toMap
      List(
        acctId,
        cols.getOrElse("CUST_ID", ""),
        cols.getOrElse("STATUS", "A"),
        cols.getOrElse("BALANCE", "0.00"),
        cols.getOrElse("OD_FLAG", "N"),
        cols.getOrElse("ACCUM", "0.00"),
        cols.getOrElse("BRANCH", "B001"),
      ).mkString("|")
  }

// --- observation parsing -----------------------------------------------------

def norm(value: String): String =
  val v = value.trim
  if v.matches("""-?\d+\.\d\d""") then BigDecimal(v).bigDecimal.toPlainString else v

def kv(text: String, sep: Char): Map[String, String] =
  text.split(sep).toList.flatMap { part =>
    part.split("=", 2) match
      case Array(k, v) => List(k.trim -> norm(v))
      case _           => Nil
  }.toMap

def parseDbout(path: Path): List[Equiv.Observation] =
  if !Files.exists(path) then Nil
  else
    Files.readString(path).linesIterator.filter(_.trim.nonEmpty).toList.map { line =>
      line.split("\\|", 5) match
        case Array(_, table, op, key, fields) =>
          Equiv.Observation.DbMutation(table, op, kv(key, ','), kv(fields, ';'))
        case _                                => sys.error(s"malformed observation line: $line")
    }

def parseRejects(path: Path): List[Equiv.Observation] =
  if !Files.exists(path) then Nil
  else
    Files.readString(path).linesIterator.filter(_.trim.nonEmpty).toList.map { line =>
      Equiv.Observation.Record(
        "output:REJECTS",
        Map(
          "XFER_SEQ"   -> line.substring(0, 7),
          "REJ_CODE"   -> line.substring(80, 82).trim,
          "REJ_REASON" -> line.substring(82, 112.min(line.length)).trim,
        ),
      )
    }

// --- driver ------------------------------------------------------------------

def inputsOf(vector: EquivVector): Map[String, String] =
  vector.inputs match
    case Json.Obj(fields) => fields.collect { case (k, Json.Str(v)) => k -> v }.toMap
    case _                => Map.empty

def runVector(binary: Path, work: Path, vector: EquivVector): Task[List[Equiv.Observation]] =
  val inputs  = inputsOf(vector)
  val runDate = inputs.getOrElse("state:RUN-DATE", "2026-01-15")
  for
    _      <- ZIO.attemptBlocking {
                // XFERIN is record-sequential: exact 80-byte records, no line terminators.
                Files.write(work.resolve("xferin.dat"), xferinRecord(inputs).getBytes(StandardCharsets.UTF_8))
                Files.write(
                  work.resolve("acctdb.dat"),
                  acctdbRows(inputs).mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8),
                )
                List("dbout.dat", "rejects.dat").foreach(f => Files.deleteIfExists(work.resolve(f)))
              }
    process <- Command(binary.toString)
                 .workingDirectory(work.toFile)
                 .env(
                   Map(
                     "DD_XFERIN"        -> work.resolve("xferin.dat").toString,
                     "DD_REJECTS"       -> work.resolve("rejects.dat").toString,
                     "COB_CURRENT_DATE" -> s"${runDate.replace('-', '/')} 00:00:00",
                   )
                 )
                 .run
    out     <- process.stdout.string.fork
    err     <- process.stderr.string.fork
    exit    <- process.exitCode
    stdout  <- out.join
    stderr  <- err.join
    _       <- ZIO.when(exit.code != 0)(
                 ZIO.fail(new RuntimeException(s"${vector.id}: ACCTXFR exited ${exit.code}\n$stdout\n$stderr"))
               )
  yield parseDbout(work.resolve("dbout.dat")) ++ parseRejects(work.resolve("rejects.dat"))

def argValue(args: Seq[String], name: String): String =
  args.sliding(2).collectFirst { case Seq(`name`, v) => v }.getOrElse(sys.error(s"missing $name <value>"))

val toolsDir = Paths.get(scriptPath).toAbsolutePath.getParent

val app: Task[Unit] =
  for
    cobol    <- ZIO.attempt(Paths.get(argValue(args, "--cobol")).toAbsolutePath)
    probes   <- ZIO.attempt(Paths.get(argValue(args, "--probes")))
    out      <- ZIO.attempt(Paths.get(argValue(args, "--out")))
    work     <- ZIO.attemptBlocking(Files.createTempDirectory("llm4zio-capture-"))
    prepped   = work.resolve("ACCTXFR.cbl")
    _        <- ZIO.attemptBlocking(
                  Files.writeString(prepped, preprocess(Files.readString(cobol.resolve("ACCTXFR.cbl"))))
                )
    binary    = work.resolve("acctxfr")
    compile  <- Command(
                  "cobc", "-x", "-o", binary.toString,
                  "-fsign=ebcdic", // mainframe zoned-decimal overpunch ({, }, A-R)
                  "-I", cobol.resolve("copybooks").toString,
                  prepped.toString, toolsDir.resolve("DBSHIM.cbl").toString,
                ).exitCode
    _        <- ZIO.when(compile.code != 0)(ZIO.fail(new RuntimeException(s"cobc failed (${compile.code})")))
    vectors  <- VectorStore.read(probes).mapError(e => new RuntimeException(e.message))
    captured <- ZIO.foreach(vectors) { probe =>
                  runVector(binary, work, probe).map { obs =>
                    println(s"  ${probe.id}: ${obs.size} observation(s)")
                    probe.copy(tier = Equiv.Tier.Captured, observations = obs)
                  }
                }
    _        <- VectorStore.write(out, captured).mapError(e => new RuntimeException(e.message))
    _        <- ZIO.attempt(println(s"${captured.size} captured vector(s) -> $out"))
  yield ()

Unsafe.unsafe { implicit u =>
  Runtime.default.unsafe.run(app).getOrThrowFiberFailure()
}
