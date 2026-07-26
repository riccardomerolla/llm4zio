# llm4zio-modernize — the modernization pipeline as an OCI image, for air-gapped
# and CI use: JDK 21 + git + maven + the published module resolved at build time,
# so at run time nothing is fetched.
#
#   docker build --build-arg LLM4ZIO_VERSION=4.0.0 -t llm4zio-modernize .
#   docker run --rm -v $ESTATE:/work -w /work \
#     -e LLM4ZIO_PACK=/packs/your-pack ghcr.io/riccardomerolla/llm4zio-modernize:4.0.0 \
#     survey -- --repo /work
#
# CLI coding agents (claude/codex/gemini) must be present for the phases that use
# them — mount or bake them per your seat configuration; survey/seed run without.
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
 && apt-get install -y --no-install-recommends git maven curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN curl -fLo /usr/local/bin/cs https://github.com/coursier/launchers/raw/master/coursier \
 && chmod +x /usr/local/bin/cs

ARG LLM4ZIO_VERSION
RUN test -n "$LLM4ZIO_VERSION" || (echo "build with --build-arg LLM4ZIO_VERSION=<x.y.z>" && exit 1)

# Resolve the module and its full dependency graph into the image.
RUN cs fetch "io.github.riccardomerolla:llm4zio-modernize_3:${LLM4ZIO_VERSION}" > /opt/llm4zio-cp.txt \
 && paste -sd: /opt/llm4zio-cp.txt > /opt/llm4zio-classpath

COPY docker-entrypoint.sh /usr/local/bin/llm4zio-modernize
RUN chmod +x /usr/local/bin/llm4zio-modernize

ENTRYPOINT ["llm4zio-modernize"]
