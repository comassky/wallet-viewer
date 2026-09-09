# ₿ Bitcoin Wallet Viewer

![Read-only Bitcoin](https://img.shields.io/badge/Bitcoin-read--only-F7931A?style=flat-square&logo=bitcoin&logoColor=white)
![Java 25](https://img.shields.io/badge/Java-25-437291?style=flat-square)
![Quarkus 3.39.2](https://img.shields.io/badge/Quarkus-3.39.2-4695EB?style=flat-square)
![Vue 3.5.41](https://img.shields.io/badge/Vue-3.5.41-42B883?style=flat-square&logo=vuedotjs&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-self--hosted-2496ED?style=flat-square&logo=docker&logoColor=white)

**Your Bitcoin. A clearer picture.** A self-hosted, read-only dashboard powered by Electrum: one account-level extended public key, one JVM application, no database, no signing or spending.

[Features](#features) · [Architecture](#architecture) · [Stack](#stack) · [Docker](#docker) · [Configuration](#configuration) · [Logs](#logs) · [Security](#security-and-limitations)

> **Local by default, not authenticated.** Supply only an extended **public** key at runtime. Never provide a seed phrase or private key, or expose the API directly to the Internet.

## Features

- **Wallet overview:** confirmed/unconfirmed balances, transaction history, confirmations and unspent outputs; BIP44, BIP49, BIP84 and BIP86 receive/change derivation.
- **Activity / UTXO tabs:** keyboard-accessible navigation, sortable tables, mobile cards and click-to-copy addresses/transaction IDs with feedback.
- **Inline transaction details:** expand a row to inspect inputs, outputs, scripts and network fees without leaving the dashboard. Details load on demand from your Electrum server, not an external explorer.
- **Compact transaction graph:** up to **five inputs and five outputs per page**, plus a group for all off-page items. Each graph side and each detail list has independent pagination; groups let you explore further items.
- **BTC / SAT / EUR / USD:** choose a unit from the total balance; the preference is saved locally and applies throughout the dashboard.
- **Receive card:** next receiving address, derivation path, copy action and an enlargeable QR code tied to the displayed address.
- **Live state:** automatic WebSocket reconnection, synchronization status and last-known data retained with a stale/offline warning.

- **Bitcoin dark theme:** charcoal surfaces, orange accents and [Lucide](https://lucide.dev) icons. The header's live badge reveals Electrum host/port, connection, software/protocol versions and TLS on hover, keyboard focus or tap. Unavailable metadata is explicit.

## Architecture

**Electrum → serialized scan → immutable Caffeine state → WebSocket + REST → Vue**

| Layer | What makes it work |
| --- | --- |
| Electrum transport | One persistent outbound TCP connection, optional verified TLS, newline-delimited JSON-RPC, request-ID matching, heartbeat and automatic reconnect. |
| Discovery | Derives scripts locally; subscribes to script hashes and block headers. The extended public key is never sent to Electrum. |
| Scan coordinator | One worker coalesces notifications over **200 ms**. Updates arriving during a scan discard its result and trigger another pass; failed scans retry. |
| Shared state | Caffeine holds one immutable, versioned snapshot/status per instance, without TTL eviction. State is committed before consumers are notified. |
| Browser & REST | Same-origin `ws`/`wss` pushes complete states; wallet snapshot REST endpoints read the same cache. Refresh replays the cache, **not a new scan**. |
| Transaction details | Only transactions in cached wallet history can trigger a lookup. Raw transactions and parents are retrieved/validated on demand, without rescanning or enlarging the live stream. |

**No database, no scheduled wallet polling or full reconciliation.** State and discovery watermarks are in memory, rebuilt after restart and not shared between replicas. REST returns 503 before the first snapshot, then may serve stale data during outages; use live status to judge freshness.

Fiat pricing is separate: the backend fetches public [mempool.space quotes](https://mempool.space/api/v1/prices), caching successes and failures for 60 seconds. The browser refreshes every minute while fiat is selected; **no wallet identifiers are sent to the price provider**.

## Stack

Versions below come from repository declarations and the npm lockfile, **not an inventory of locally installed tools**.

| Component | Version / source |
| --- | --- |
| Java / Maven builder | Java **25**; Maven **3.9.12**, Eclipse Temurin 25 Docker build image |
| Backend | Quarkus **3.39.2**, Quinoa **2.9.0**, bitcoinj **0.16.3**, ZXing **3.5.3** — [pom.xml](pom.xml) |
| Reactive transport / cache | Vert.x, Mutiny and Caffeine — versions managed by the Quarkus BOM |
| Node.js | **24.20.0**, installed by Quinoa — [src/main/resources/application.properties](src/main/resources/application.properties) |
| Frontend (locked) | Vue **3.5.41**, TypeScript **5.9.3**, Vite **5.4.21**, Tailwind CSS **3.4.19** — [src/main/webui/package-lock.json](src/main/webui/package-lock.json) |
| Icons | Lucide Vue **1.43.0** (`@lucide/vue`), explicit imports of the icons used by the UI |
| Runtime image | Distroless Java **25**, Debian **13**, `nonroot` — [Dockerfile](Dockerfile) |

[src/main/webui/package.json](src/main/webui/package.json) declares caret ranges; the table reports resolved lockfile versions. The Docker build runs Maven verification, frontend tests, TypeScript checking and asset compilation before packaging. Local development uses JDK 25 and `mvn quarkus:dev`; Quinoa manages Node automatically.

## Docker

Requires Docker with Compose v2; no local Java, Maven or Node installation needed.

1. Copy [.env.example](.env.example) to a local environment file named **.env** and set `WALLET_XPUB` to your account-level extended public key. Keep it out of Git.
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

[compose.yaml](compose.yaml) binds to `127.0.0.1`, requires a nonempty key, enables Electrum TLS and adds a read-only filesystem, temporary `/tmp`, dropped capabilities, no privilege escalation and rotated logs. The runtime uses UID/GID **65532:65532**, with no shell or package manager; no persistent volume is needed. `WALLET_VIEWER_PORT` changes the host port (default **8080**).

**Use GHCR instead of building:** set `WALLET_VIEWER_IMAGE=ghcr.io/<owner>/<repository>:latest` in your local environment file, using the lowercase repository path, then:

```sh
docker compose pull
docker compose up -d --no-build
```

Prefer a release tag or digest for reproducibility; private packages require registry authentication. [.github/workflows/docker.yml](.github/workflows/docker.yml) verifies/builds **linux/amd64** images on pull requests without publishing, and publishes GHCR images on `main` or `v*` pushes (or manual runs on those refs). It uses `GITHUB_TOKEN`, needs no wallet key, and **does not deploy**.

## Configuration

Supply wallet settings **at runtime**, never as build arguments or in source. Defaults below are for Compose; application-only differences are noted.

| Variable | Default | Purpose |
| --- | --- | --- |
| `WALLET_XPUB` | Required | Account-level extended public key, not a master key or single address |
| `WALLET_SCRIPT_TYPE` | `auto` | `auto`, `p2pkh`, `p2sh-p2wpkh`, `p2wpkh`, `p2tr`; explicitly select `p2tr` for BIP86 |
| `WALLET_NETWORK` | `mainnet` | `mainnet` or `testnet`; use a compatible Electrum server on the same network |
| `WALLET_GAP_LIMIT` | `20` | Consecutive unused addresses stopping discovery on each chain |
| `WALLET_MAX_ADDRESSES` | `200` | Maximum history-scanned addresses per receive/change chain |
| `ELECTRUM_HOST` | `electrum.blockstream.info` | Reachable Electrum hostname; the default targets mainnet |
| `ELECTRUM_PORT` | `50002` | Server port; application-only default is `50001` |
| `ELECTRUM_SSL` | `true` | TLS with certificate/hostname verification; application-only default is `false` |
| `ELECTRUM_REQUEST_TIMEOUT` | `30s` | Per-RPC timeout |

Inside a container, `localhost` means that container: use a reachable Docker hostname or, on Docker Desktop, `host.docker.internal` for a host server. Compose forwards only its declared variables and does not mount local Java configuration; extra settings such as `WALLET_PRICES_URL` need an explicit environment/Compose override.

## Logs

- **INFO:** connection/reconnect lifecycle, notifications, scan duration/outcome and newly discovered transaction counts. Address-change notifications include the **full locally derived address** when known, otherwise `address=unknown`; resolving it performs no network request.
- **DEBUG:** per-RPC method, numeric request ID, duration and success/failure. Wallet keys, script hashes, transaction IDs, balances and raw payloads remain excluded; RPC correlation IDs are not wallet identifiers.

**Logs now contain wallet addresses.** Keep them private, restrict access and retention, and redact addresses before sharing diagnostics.

For local Quarkus runs, set `quarkus.log.category."com.example.walletviewer".level=DEBUG` in the ignored [config/application.properties](config/application.properties), then restart. Keep framework-wide logging unchanged.

Quarkus defaults to a **DEBUG build-time minimum**. If a custom build raised it, rebuild with `quarkus.log.category."com.example.walletviewer".min-level=DEBUG`; changing only the runtime level cannot restore disabled logging. See [Quarkus logging](https://quarkus.io/guides/logging). For Docker, explicitly supply runtime logging configuration: Compose does not mount the local file or forward arbitrary variables.

## Security and limitations

- **No authentication:** keep the localhost binding or use an authenticated HTTPS reverse proxy. WebSocket origin validation is not access control. Preserve `Host`/`Origin`, forward HTTP/1.1 `Upgrade`/`Connection`, use `wss` and proxy timeouts **greater than 90 seconds**.
- **Read-only is not anonymous:** an extended public key reveals account history. Electrum can correlate queried scripts; use a trusted, network-compatible server. Never provide spending secrets.
- **Bounded discovery:** activity beyond the gap limit or per-chain maximum may be missed. A next receive address beyond the cap is watched **without fetching its history**; increase `WALLET_MAX_ADDRESSES` before relying on its funds being included. Discovery watermarks do not persist across restarts.
- **Details depend on server history:** unavailable parent transactions prevent fee calculation. Graph links show transaction structure, not input-to-output ownership or funding attribution.
- **Fiat is an estimate at today's price, not historical accounting.** Old transactions use current quotes too. Failed refreshes retain a last-known quote with a warning; without one, fiat amounts are unavailable. BTC/SAT do not depend on the price service.