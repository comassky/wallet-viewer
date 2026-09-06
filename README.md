<p align="center"><img src="docs/logo-full.png" alt="Bitcoin Wallet Viewer" width="160"></p>

# ₿ Bitcoin Wallet Viewer

<p align="center">
  <img alt="Bitcoin: read-only" src="https://img.shields.io/badge/Bitcoin-read--only-F7931A?style=flat-square&logo=bitcoin&logoColor=white">
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-ED8B00?style=flat-square&logo=openjdk&logoColor=white">
  <img alt="Quarkus 3.39" src="https://img.shields.io/badge/Quarkus-3.39-4695EB?style=flat-square&logo=quarkus&logoColor=white">
  <img alt="bitcoinj 0.17" src="https://img.shields.io/badge/bitcoinj-0.17-F7931A?style=flat-square&logo=bitcoin&logoColor=white">
  <img alt="Vue 3.5" src="https://img.shields.io/badge/Vue-3.5-4FC08D?style=flat-square&logo=vuedotjs&logoColor=white">
  <img alt="Vite 8" src="https://img.shields.io/badge/Vite-8-646CFF?style=flat-square&logo=vite&logoColor=white">
  <img alt="TypeScript 5.9" src="https://img.shields.io/badge/TypeScript-5.9-3178C6?style=flat-square&logo=typescript&logoColor=white">
  <img alt="Tailwind CSS 4" src="https://img.shields.io/badge/Tailwind_CSS-4-06B6D4?style=flat-square&logo=tailwindcss&logoColor=white">
  <img alt="Docker: self-hosted" src="https://img.shields.io/badge/Docker-self--hosted-2496ED?style=flat-square&logo=docker&logoColor=white">
  <img alt="License: GPL-3.0" src="https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square&logo=gnu&logoColor=white">
</p>

**A self-hosted, read-only Bitcoin dashboard powered by Electrum.** One extended public key, one application. No database, signing or spending.

[Features](#features) · [Architecture](#architecture) · [Stack](#stack) · [Docker](#docker) · [Demo](#demo-mode) · [Configuration](#configuration) · [Logs](#logs) · [Security](#security-and-limitations) · [License](#license)

> 🔒 **Local by default, not authenticated.** Supply only an extended **public** key at runtime. Never provide a seed phrase or private key, or expose the API directly to the Internet.

<p align="center"><img src="docs/screen.png" alt="Bitcoin Wallet Viewer dashboard showing mock data" width="820"></p>

> 🧪 **Screenshot from [demo mode](#demo-mode) — the wallet, balance, addresses and transactions are entirely synthetic. This wallet does not exist and holds no real funds.**

## Features

- 💰 **Live wallet:** balances, confirmations, Activity / UTXO tabs, sorting and click-to-copy identifiers.
- 🔍 **Transaction details:** a full-width dialog with **Graph** and **Inputs / Outputs** tabs, fees and compact input/output graphs with independent pagination (five items per side).
- 💱 **Display units:** BTC, SAT, EUR and USD; switch beside the balance, with the preference saved locally.
- 📥 **Receive:** next address, derivation path and enlargeable QR code; BIP44, BIP49, BIP84 and BIP86 support.
- 🎨 **Bitcoin dark theme:** responsive layout, keyboard controls and a live badge with Electrum server details.

## Architecture

```mermaid
flowchart LR
	E[Electrum] -->|Notifications| S
	subgraph Backend[Quarkus backend]
		S[Serialized wallet scan] --> C[Versioned Caffeine state]
		C --> A[REST API / WebSocket]
		P[Fiat price service] --> A
	end
	S -->|Script-hash queries| E
	M[mempool.space] -->|Public quotes| P
	A <-->|REST / live snapshots| V[Vue dashboard]
```

- Addresses are derived locally; only script hashes reach Electrum, never the extended public key. Notifications trigger serialized scans, not periodic wallet polling.
- WebSocket pushes versioned snapshots; REST reads the same cache. Refresh replays cached data, and transaction details load on demand via Axios.
- State is in memory and rebuilt after restart. During outages, the UI keeps the last snapshot with a stale/offline warning. Fiat quotes come separately from [mempool.space](https://mempool.space/api/v1/prices), without wallet identifiers.

## Stack

| Component | Version / source |
| --- | --- |
| Java / Maven builder | Java **25**; Maven **3.9.12**, Eclipse Temurin 25 Docker build image |
| Backend | Quarkus **3.39.2**, Quinoa **2.9.0**, bitcoinj **0.17.1**, ZXing **3.5.4** — [pom.xml](pom.xml) |
| Reactive transport / cache | Vert.x, Mutiny and Caffeine — versions managed by the Quarkus BOM |
| Node.js | **24.21.0**, installed by Quinoa — [src/main/resources/application.properties](src/main/resources/application.properties) |
| Frontend (locked) | Vue **3.5.42**, Vite **8.2.2**, @vitejs/plugin-vue **6.0.8**, vue-tsc **3.3.11**, TypeScript **5.9.3**, Tailwind CSS **4.3.3** (via @tailwindcss/vite) — [src/main/webui/package-lock.json](src/main/webui/package-lock.json) |
| UI libraries | Lucide Vue **1.43.0**, Axios **1.20.0**, @formkit/auto-animate **0.10.0** |
| Runtime image | Distroless Java **25**, Debian **13**, `nonroot` — [Dockerfile](Dockerfile) |

Versions reflect declarations and the npm lockfile. For local development, use JDK 25 and `mvn quarkus:dev`; Quinoa manages Node automatically.

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

[compose.yaml](compose.yaml) binds to `127.0.0.1:8080`, enables Electrum TLS and runs non-root with a read-only filesystem and dropped capabilities. Change `WALLET_VIEWER_PORT` to use another host port.

**🐳 Use GHCR instead of building:** set `WALLET_VIEWER_IMAGE=ghcr.io/<owner>/<repository>:latest` in your local environment file, using the lowercase repository path, then:

```sh
docker compose pull
docker compose up -d --no-build
```

Prefer a release tag or digest; private GHCR images require authentication. The [Docker workflow](.github/workflows/docker.yml) runs Java/frontend tests, TypeScript checks and a **linux/amd64** build on PRs, and publishes the `dev` image on `main`. Cut a release from **Actions → [Release](.github/workflows/release.yml)** (manual): in a single run it sets the version, tags **`X.Y.Z`**, bumps `main` to the next `-SNAPSHOT`, then calls the reusable [Publish image workflow](.github/workflows/publish-image.yml) to build the image (running the full test suite), push **`X.Y.Z`** and **`latest`** to GHCR and create the GitHub Release. Image tags: **`dev`** (latest `main`), **`latest`** (latest release), **`X.Y.Z`** (specific release).

## Demo mode

Set **`WALLET_DEMO=true`** (in your `.env`) for a no-setup demo with synthetic data — ideal for screenshots and UI previews without exposing a real xpub, address or transaction:

```sh
docker compose up -d --build   # with WALLET_DEMO=true in .env
```

In demo mode the app never connects to Electrum. It serves a self-consistent synthetic wallet (balance, UTXOs, transactions and a derived receive address) from a bundled public test key, and the mock **emits a random transaction every 15 seconds** so the live view keeps updating. `WALLET_XPUB` and the Electrum settings are ignored — no real funds or identity are involved.

## Dependency updates

[Renovate](renovate.json) opens separate **Java**, **Frontend** and **Docker** PRs, with major upgrades separated and **no automerge**. Node settings stay synchronized; Docker digests track image rebuilds. TypeScript is held on the **5.x** line (the native 7.x compiler is not yet supported by the Vue/Vite toolchain), and JDK/image-family migrations remain manual.

Authorize the [Renovate app](https://github.com/apps/renovate) for the repository and publish the configuration on the default branch. In the [Mend portal](https://developer.mend.io/github/comassky/wallet-viewer), disable **Silent mode** to enable automatic PR creation. npm uses the public registry; no corporate credentials are needed.

## Configuration

Supply wallet settings **at runtime**, never as build arguments or in source. Defaults below are for Compose; application-only differences are noted.

| Variable | Default | Purpose |
| --- | --- | --- |
| `WALLET_DEMO` | `false` | Serve a synthetic demo wallet with no Electrum or xpub; the mock emits a random transaction every 15s. See [Demo mode](#demo-mode) |
| `WALLET_XPUB` | Required unless demo | Account-level extended public key, not a master key or single address |
| `WALLET_SCRIPT_TYPE` | `auto` | `auto`, `p2pkh`, `p2sh-p2wpkh`, `p2wpkh`, `p2tr`; explicitly select `p2tr` for BIP86 |
| `WALLET_NETWORK` | `mainnet` | `mainnet` or `testnet`; use a compatible Electrum server on the same network |
| `WALLET_GAP_LIMIT` | `20` | Consecutive unused addresses stopping discovery on each chain |
| `WALLET_MAX_ADDRESSES` | `200` | Maximum history-scanned addresses per receive/change chain |
| `ELECTRUM_HOST` | `electrum.blockstream.info` | Reachable Electrum hostname; the default targets mainnet |
| `ELECTRUM_PORT` | `50002` | Server port; application-only default is `50001` |
| `ELECTRUM_SSL` | `true` | TLS with certificate/hostname verification; application-only default is `false` |
| `ELECTRUM_REQUEST_TIMEOUT` | `30s` | Per-RPC timeout |
| `LOG_LEVEL` | `INFO` | Application log level for the `com.comassky.wallet` category; set `DEBUG` for Electrum/scan lifecycle logs |

Inside Docker, `localhost` means the container: use a reachable server hostname. Compose forwards only declared variables and does not mount local Java configuration.

## Logs

Control verbosity with **`LOG_LEVEL`** (classic Quarkus, default `INFO`); it maps to the `com.comassky.wallet` category.

- **INFO (default):** startup details such as the detected wallet script type, plus warnings and errors. No keys, balances or raw payloads.
- **DEBUG (`LOG_LEVEL=DEBUG`):** Electrum connection changes, address notifications, wallet scan lifecycle and per-RPC method/duration/outcome.

**⚠️ Logs contain wallet addresses:** keep them private and redact them before sharing.

## Security and limitations

- 🔓 **No authentication:** keep access local or use an authenticated HTTPS proxy with WebSocket support, preserved `Host`/`Origin` headers and timeouts above 90 seconds. Origin checks are not access control.
- 🕵️ **Privacy:** public keys expose account history; Electrum can correlate scripts. Use a trusted server and never provide spending secrets.
- 🎯 **Bounded discovery:** funds beyond the gap/address limits may be missed. An extra receive address beyond the cap is watched without history; raise `WALLET_MAX_ADDRESSES` before relying on its balance.
- 📊 **Estimates:** missing parent transactions prevent fee calculation; graph edges do not allocate inputs to outputs. Fiat uses current quotes, not historical prices.

## License

Released under the [GNU General Public License v3.0](LICENSE).