# syntax=docker/dockerfile:1
FROM maven:3.9.12-eclipse-temurin-25 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY src ./src
# Quinoa installs Node, uses npm ci and builds the Vue UI into the Quarkus app.
# Run the regression tests here too: no image is produced if verification fails.
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/workspace/.quinoa \
    mvn --batch-mode --no-transfer-progress verify -Dquarkus.quinoa.ci=true

FROM eclipse-temurin:25-jre-jammy AS runtime
WORKDIR /app
RUN groupadd --gid 10001 wallet \
    && useradd --uid 10001 --gid wallet --no-create-home --shell /usr/sbin/nologin wallet

# Keep dependencies separate from application code for efficient image layers.
COPY --from=build --chown=10001:10001 /workspace/target/quarkus-app/lib/ ./lib/
COPY --from=build --chown=10001:10001 /workspace/target/quarkus-app/*.jar ./
COPY --from=build --chown=10001:10001 /workspace/target/quarkus-app/app/ ./app/
COPY --from=build --chown=10001:10001 /workspace/target/quarkus-app/quarkus/ ./quarkus/

ENV QUARKUS_HTTP_HOST=0.0.0.0
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]