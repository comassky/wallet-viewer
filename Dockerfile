# syntax=docker/dockerfile:1@sha256:ecfaec9ed6d810b56388c508f4121597bfbba70d41a6dfeee4d8cad5f295fc32
FROM maven:3.9.16-eclipse-temurin-25@sha256:d67198007bb4441b07d45587320f83154de80ece3608f80408ef14c6ea847753 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY src ./src
# Quinoa installs Node, uses npm ci and builds the Vue UI into the Quarkus app.
# Run the regression tests here too: no image is produced if verification fails.
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/workspace/.quinoa \
    mvn --batch-mode --no-transfer-progress verify -Dquarkus.quinoa.ci=true

FROM gcr.io/distroless/java25-debian13:nonroot@sha256:fce4a1d66284e8866c46113d9bdc286c46fb8c3c3f0a098f877034349e88debe AS runtime
WORKDIR /app

# Keep dependencies separate from application code for efficient image layers.
COPY --from=build --chown=65532:65532 /workspace/target/quarkus-app/lib/ ./lib/
COPY --from=build --chown=65532:65532 /workspace/target/quarkus-app/*.jar ./
COPY --from=build --chown=65532:65532 /workspace/target/quarkus-app/app/ ./app/
COPY --from=build --chown=65532:65532 /workspace/target/quarkus-app/quarkus/ ./quarkus/

ENV QUARKUS_HTTP_HOST=0.0.0.0
USER 65532:65532
EXPOSE 8080
# Distroless provides the Java -jar entrypoint; no shell is required.
CMD ["quarkus-run.jar"]