# Docker & deployment

Running a published image or building the JVM image with Compose requires Docker with Compose v2; no local Java, Maven or Node installation is needed. Building a native runner locally also requires JDK 25 and Maven.

## Quick start

1. Copy [.env.example](../.env.example) to a local environment file named **.env** and set `WALLET_XPUB` to your account-level extended public key. Keep it out of Git.
2. Match the script type and network to the account and Electrum server. **BIP86/Taproot requires `WALLET_SCRIPT_TYPE=p2tr`**: a plain xpub cannot identify Taproot automatically.
3. From the repository root, build/start the **JVM variant** using [Dockerfile](../Dockerfile), then open <http://localhost:8080>:

```sh
docker compose up -d --build
```

Follow logs or stop the service:

```sh
docker compose logs -f wallet-viewer
docker compose down
```

[compose.yaml](../compose.yaml) binds to `127.0.0.1:8080`, enables Electrum TLS and runs non-root with a read-only filesystem and dropped capabilities. Change `WALLET_VIEWER_PORT` to use another host port.

## Use GHCR instead of building

Published images (`dev`, `latest`, version tags) contain a **native linux/amd64 executable**, not the JVM build used by the default Compose build. Set `WALLET_VIEWER_IMAGE=ghcr.io/comassky/wallet-viewer:latest` in your local environment file (adjust the lowercase repository path for a fork), then:

```sh
docker compose pull
docker compose up -d --no-build
```

Prefer a release tag or digest; private GHCR images require authentication.

## CI & releases

The [Docker - Build and Publish Dev](../.github/workflows/docker.yml) workflow runs Java/frontend tests, TypeScript checks and a linux/amd64 build on PRs, and publishes the `dev` image on `main`.

Cut a release from **Actions → [Release - Create and Publish](../.github/workflows/release.yml)** (manual): it prepares the version locally, runs native verification and builds the Docker image before creating any version commit or Git tag. Only after those steps succeed does it commit and tag `X.Y.Z`, bump `main` to the next `-SNAPSHOT`, and push the branch and tag atomically. It then pushes the already-built image as `X.Y.Z` and `latest` to GHCR and creates the GitHub Release, without a second native build.

Publication runs `mvn verify -Dnative` so packaged native integration tests must pass before the image is pushed. The generated OpenAPI schema is uploaded as an artifact and reused by the Pages job, without another Maven or frontend build.

Pushing a tag alone does not publish an image. To publish or retry an existing tag, run **Actions → [Release - Publish Tag](../.github/workflows/publish-image.yml)** manually with that tag. A build failure leaves no release tag or version commits on the remote. A later registry or GitHub Release publication failure can leave the verified tag in place; use the existing-tag workflow to retry publication.

Image tags:

- `dev` — latest `main`
- `latest` — latest release
- `X.Y.Z` — specific release

## Native image (GraalVM)

The native image runs without a JVM, but it is not a fully static executable. [Dockerfile.native](../Dockerfile.native) supplies compatible glibc libraries and CA certificates through a non-root Distroless Debian 13 base. The runner is built with the UBI9 Mandrel JDK 25 builder.

1. With JDK 25 and Maven installed, build and verify the native runner. Docker runs the Mandrel builder, so no local GraalVM installation is required:

```sh
mvn verify -Dnative -Dquarkus.native.container-build=true
```

2. Package the runner into a minimal distroless image and run it:

```sh
docker build -f Dockerfile.native -t wallet-viewer:native .
docker run --rm -p 127.0.0.1:8080:8080 --env-file .env wallet-viewer:native
```

Keep the environment file private and match the Electrum network and script type to the account. The direct `docker run` example does not apply Compose's environment defaults or its additional hardening; configure TLS explicitly and prefer Compose for a persistent deployment.

The `native` Maven profile is activated only by `-Dnative`. `mvn verify` runs packaged integration tests using a loopback-only Electrum fixture, including REST/WebSocket consistency, notifications, cache replay, reconnection and origin rejection. It does not query a real wallet.

## Native vs JVM

| Aspect | Native (Mandrel/GraalVM) | JVM (HotSpot) |
| --- | --- | --- |
| Published GHCR images | `dev`, `latest`, version tags | Build locally with `Dockerfile` |
| Runtime | No JVM; system libraries and CA certificates required | JRE and system libraries supplied by the image |
| Startup and idle memory | Typically suited to fast restarts and a small idle footprint | Includes JVM startup and memory overhead |
| Sustained throughput | Workload-dependent; measure on the target hardware | JIT can optimize long-running workloads |
| Build | Native compilation takes minutes and more build resources | No native compilation step |

Earlier local observations on `linux/amd64` reported roughly 18-20 ms native startup and 55 MB idle RSS. The hardware, measurement procedure and sample size were not recorded here, so these are illustrative observations, not reproducible benchmarks or deployment guarantees. Measure startup, memory and throughput on your own host and workload before choosing a runtime.
