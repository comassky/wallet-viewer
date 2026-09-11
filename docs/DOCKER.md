# Docker & deployment

Requires Docker with Compose v2; no local Java, Maven or Node installation needed.

## Quick start

1. Copy [.env.example](../.env.example) to a local environment file named **.env** and set `WALLET_XPUB` to your account-level extended public key. Keep it out of Git.
2. Match the script type and network to the account and Electrum server. **BIP86/Taproot requires `WALLET_SCRIPT_TYPE=p2tr`**: a plain xpub cannot identify Taproot automatically.
3. From the repository root, build/start, then open <http://localhost:8080>:

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

Set `WALLET_VIEWER_IMAGE=ghcr.io/<owner>/<repository>:latest` in your local environment file, using the lowercase repository path, then:

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

The app also builds and runs as a GraalVM **native** executable (sub-second startup, low memory, no JVM); bitcoinj/BouncyCastle need no extra configuration.

1. Build the native runner — Docker runs the Mandrel builder, so no local GraalVM is required:

```sh
mvn package -Dnative -Dquarkus.native.container-build=true
```

2. Package the runner into a minimal distroless image and run it:

```sh
docker build -f Dockerfile.native -t wallet-viewer:native .
docker run --rm -p 8080:8080 -e WALLET_XPUB=your-xpub wallet-viewer:native
```

The native process starts in ~20 ms. The `native` Maven profile is inert for normal JVM builds (activated only by `-Dnative`).
