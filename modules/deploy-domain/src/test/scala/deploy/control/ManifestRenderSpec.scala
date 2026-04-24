package deploy.control

import java.nio.file.Paths

import zio.test.*

import deploy.entity.*

object ManifestRenderSpec extends ZIOSpecDefault:

  private val sampleSpec = DeploySpec(
    target = DeployTarget.Docker,
    workspace = Paths.get("/tmp/ws"),
    repoRoot = Paths.get("/tmp"),
    imageName = "Llm4Zio WS!",
    imageTag = "v1.2.3",
  )

  def spec = suite("deploy manifest renderers")(
    test("Dockerfile has multi-stage builder + runtime and references the workspace path") {
      val out = DockerfileRenderer.render(sampleSpec)
      assertTrue(out.contains("FROM eclipse-temurin:21-jdk AS builder")) &&
      assertTrue(out.contains("FROM eclipse-temurin:21-jre")) &&
      assertTrue(out.contains("sbt assembly")) &&
      assertTrue(out.contains("COPY ws /workspace")) &&
      assertTrue(out.contains("ENV LLM4ZIO_WORKSPACE=/workspace")) &&
      assertTrue(out.contains("EXPOSE 8080"))
    },
    test("KubernetesManifest sanitizes the image name into a valid DNS label") {
      val out = KubernetesManifest.render(sampleSpec)
      assertTrue(out.contains("name: llm4zio-ws")) &&
      assertTrue(out.contains("image: Llm4Zio WS!:v1.2.3")) &&
      assertTrue(out.contains("kind: Deployment")) &&
      assertTrue(out.contains("kind: Service"))
    },
    test("CloudRunManifest emits a Knative Service referencing the built image tag") {
      val out = CloudRunManifest.render(sampleSpec)
      assertTrue(out.contains("apiVersion: serving.knative.dev/v1")) &&
      assertTrue(out.contains("kind: Service")) &&
      assertTrue(out.contains("image: Llm4Zio WS!:v1.2.3")) &&
      assertTrue(out.contains("containerPort: 8080"))
    },
    test("DeployTarget.parse accepts canonical and alias names") {
      assertTrue(DeployTarget.parse("docker") == Right(DeployTarget.Docker)) &&
      assertTrue(DeployTarget.parse("cloud-run") == Right(DeployTarget.CloudRun)) &&
      assertTrue(DeployTarget.parse("k8s") == Right(DeployTarget.Kubernetes)) &&
      assertTrue(DeployTarget.parse("fatjar") == Right(DeployTarget.JvmFatJar)) &&
      assertTrue(DeployTarget.parse("bogus").isLeft)
    },
  )
