package deploy.control

import deploy.entity.DeploySpec

/** Emits a minimal single-Deployment + Service manifest. Image ref comes from the DeploySpec so callers must have
  * already built+pushed the image (or set `dryRun` for preview).
  */
object KubernetesManifest:

  def render(spec: DeploySpec): String =
    val name = sanitize(spec.imageName)
    val ref  = s"${spec.imageName}:${spec.imageTag}"
    s"""|apiVersion: apps/v1
        |kind: Deployment
        |metadata:
        |  name: $name
        |  labels:
        |    app: $name
        |spec:
        |  replicas: 1
        |  selector:
        |    matchLabels:
        |      app: $name
        |  template:
        |    metadata:
        |      labels:
        |        app: $name
        |    spec:
        |      containers:
        |        - name: gateway
        |          image: $ref
        |          ports:
        |            - containerPort: 8080
        |          env:
        |            - name: LLM4ZIO_WORKSPACE
        |              value: /workspace
        |          resources:
        |            requests:
        |              cpu: "250m"
        |              memory: "512Mi"
        |            limits:
        |              cpu: "1"
        |              memory: "1Gi"
        |---
        |apiVersion: v1
        |kind: Service
        |metadata:
        |  name: $name
        |spec:
        |  selector:
        |    app: $name
        |  ports:
        |    - port: 80
        |      targetPort: 8080
        |  type: ClusterIP
        |""".stripMargin

  private def sanitize(s: String): String =
    s.toLowerCase.replaceAll("[^a-z0-9-]+", "-").replaceAll("-{2,}", "-").stripPrefix("-").stripSuffix("-")
