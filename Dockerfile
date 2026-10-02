# syntax=docker/dockerfile:1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e
FROM maven:3.10.0-eclipse-temurin-25@sha256:721fefa7187746ff892b2a178eb4cac3292f89a80f76ef25c04da655f88619b8 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY src ./src
# Quinoa installs Node, uses npm ci and builds the Vue UI into the Quarkus app.
# Run the regression tests here too: no image is produced if verification fails.
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/workspace/.quinoa \
    --mount=type=cache,target=/root/.npm \
    mvn --batch-mode --no-transfer-progress verify -Dquarkus.quinoa.ci=true

FROM gcr.io/distroless/java25-debian13:nonroot@sha256:ca60da1345c0f17b6d019049e6749e15f10fd3c0da86dec938d2b4ec565d0629 AS runtime
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