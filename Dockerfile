# syntax=docker/dockerfile:1@sha256:572cebbc2b15e6f84c077742952674889cb63cd5e9b80abdffcbe587b6f19183
FROM maven:3.10.0-eclipse-temurin-25@sha256:84bd96155bf37d1be2858433aee89320f487c2d21faf06406640e7dc2b82d686 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY src ./src
# Quinoa installs Node, uses npm ci and builds the Vue UI into the Quarkus app.
# Run the regression tests here too: no image is produced if verification fails.
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/workspace/.quinoa \
    --mount=type=cache,target=/root/.npm \
    mvn --batch-mode --no-transfer-progress verify -Dquarkus.quinoa.ci=true

FROM gcr.io/distroless/java25-debian13:nonroot@sha256:59bbb349c9752fc57b75744b41b1614c9dabe8bb4869523aabd5a4288f6c09a0 AS runtime
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