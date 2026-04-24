package deploy.control

import deploy.entity.DeploySpec

/** Emits a Knative `Service` manifest deployable via `gcloud run services replace`. Phase 5 only emits the manifest;
  * actual `gcloud` invocation is left for a follow-up.
  */
object CloudRunManifest:

  def render(spec: DeploySpec): String =
    val name = sanitize(spec.imageName)
    val ref  = s"${spec.imageName}:${spec.imageTag}"
    s"""|apiVersion: serving.knative.dev/v1
        |kind: Service
        |metadata:
        |  name: $name
        |spec:
        |  template:
        |    spec:
        |      containers:
        |        - image: $ref
        |          ports:
        |            - containerPort: 8080
        |          env:
        |            - name: LLM4ZIO_WORKSPACE
        |              value: /workspace
        |          resources:
        |            limits:
        |              cpu: "1"
        |              memory: "1Gi"
        |""".stripMargin

  private def sanitize(s: String): String =
    s.toLowerCase.replaceAll("[^a-z0-9-]+", "-").replaceAll("-{2,}", "-").stripPrefix("-").stripSuffix("-")
