package llm4zio.runner

import zio.*

import llm4zio.flow.{ AdoConfig, AdoTool, FlowError }
import llm4zio.providers.HttpClient

/** Wiring for the Azure DevOps tool in a flow script. `AdoTool` needs an [[HttpClient]], but a `flow { … }` body runs
  * with `R = Any`, so [[withTool]] provides the client layer for the flow's whole duration and hands the body a ready
  * `AdoTool`. Config is read from the ADO pipeline's predefined variables (with `LLM4ZIO_ADO_*` overrides for local
  * runs). `FlowContext` stays untouched — `AdoTool` is just another flow-constructed tool, like `sdd.sc`'s gates.
  */
object Ado:

  /** Build an [[AdoConfig]] from environment variables. ADO pipelines set `SYSTEM_COLLECTIONURI`, `SYSTEM_TEAMPROJECT`,
    * `BUILD_REPOSITORY_NAME`, and (with "Allow scripts to access the OAuth token") `SYSTEM_ACCESSTOKEN`;
    * `LLM4ZIO_ADO_*` and `AZURE_DEVOPS_EXT_PAT` override for local runs. Pure, so it's testable.
    */
  def configFrom(env: Map[String, String]): Either[String, AdoConfig] =
    def pick(keys: String*): Either[String, String] =
      keys.iterator.flatMap(env.get).find(_.nonEmpty).toRight(s"missing ${keys.mkString(" / ")}")
    for
      orgUrl  <- pick("SYSTEM_COLLECTIONURI", "SYSTEM_TEAMFOUNDATIONCOLLECTIONURI", "LLM4ZIO_ADO_ORG_URL")
      project <- pick("SYSTEM_TEAMPROJECT", "LLM4ZIO_ADO_PROJECT")
      repo    <- pick("BUILD_REPOSITORY_NAME", "LLM4ZIO_ADO_REPO")
      pat     <- pick("AZURE_DEVOPS_EXT_PAT", "SYSTEM_ACCESSTOKEN", "LLM4ZIO_ADO_PAT")
    yield AdoConfig(orgUrl = orgUrl.stripSuffix("/"), project = project, repository = repo, pat = pat)

  /** Provide an [[AdoTool]] (with a live HTTP client) to `use` for its whole duration. */
  def withTool[A](
    env: Map[String, String] = sys.env
  )(
    use: AdoTool => ZIO[Any, FlowError, A]
  ): ZIO[Any, FlowError, A] =
    configFrom(env) match
      case Left(msg)  => ZIO.fail(FlowError.Aborted(s"Azure DevOps config: $msg"))
      case Right(cfg) =>
        val client: ZLayer[Any, FlowError, HttpClient] =
          HttpClient.reliableClient.mapError(t =>
            FlowError.Process("ado http client", Option(t.getMessage).getOrElse(t.toString))
          ) >>> HttpClient.live
        ZIO.serviceWithZIO[HttpClient](http => use(new AdoTool(cfg, http))).provideLayer(client)
