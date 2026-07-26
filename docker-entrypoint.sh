#!/bin/sh
# Entrypoint for the llm4zio-modernize OCI image: run the pipeline main on the
# classpath resolved at image-build time (no network at run time).
exec java -cp "$(cat /opt/llm4zio-classpath)" llm4zio.modernize.Main "$@"
