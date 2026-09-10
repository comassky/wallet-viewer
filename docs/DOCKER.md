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

The [Docker workflow](../.github/workflows/docker.yml) runs Java/frontend tests, TypeScript checks and a linux/amd64 build on PRs, and publishes the `dev` image on `main`.

Cut a release from **Actions → [Release](../.github/workflows/release.yml)** (manual): in a single run it sets the version, tags `X.Y.Z`, bumps `main` to the next `-SNAPSHOT`, then calls the reusable [Publish image workflow](../.github/workflows/publish-image.yml) to build the image (running the full test suite), push `X.Y.Z` and `latest` to GHCR and create the GitHub Release.

Image tags:

- `dev` — latest `main`
- `latest` — latest release
- `X.Y.Z` — specific release
