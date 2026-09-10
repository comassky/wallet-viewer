<p align="center"><img src="docs/logo-full.png" alt="Bitcoin Wallet Viewer" width="160"></p>

# ₿ Bitcoin Wallet Viewer

<p align="center">
  <img alt="Bitcoin: read-only" src="https://img.shields.io/badge/Bitcoin-read--only-F7931A?style=flat-square&logo=bitcoin&logoColor=white">
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-ED8B00?style=flat-square&logo=openjdk&logoColor=white">
  <img alt="Quarkus 3.39" src="https://img.shields.io/badge/Quarkus-3.39-4695EB?style=flat-square&logo=quarkus&logoColor=white">
  <img alt="GraalVM native" src="https://img.shields.io/badge/GraalVM-native-3776AB?style=flat-square&logo=graalvm&logoColor=white">
  <img alt="Startup ~20ms" src="https://img.shields.io/badge/startup-~20ms-44CC11?style=flat-square&logo=rocket&logoColor=white">
  <img alt="Memory ~55MB" src="https://img.shields.io/badge/RSS-~55MB-44CC11?style=flat-square&logo=speedtest&logoColor=white">
  <img alt="bitcoinj 0.17" src="https://img.shields.io/badge/bitcoinj-0.17-F7931A?style=flat-square&logo=bitcoin&logoColor=white">
  <img alt="Vue 3.5" src="https://img.shields.io/badge/Vue-3.5-4FC08D?style=flat-square&logo=vuedotjs&logoColor=white">
  <img alt="Vite 8" src="https://img.shields.io/badge/Vite-8-646CFF?style=flat-square&logo=vite&logoColor=white">
  <img alt="TypeScript 5.9" src="https://img.shields.io/badge/TypeScript-5.9-3178C6?style=flat-square&logo=typescript&logoColor=white">
  <img alt="Tailwind CSS 4" src="https://img.shields.io/badge/Tailwind_CSS-4-06B6D4?style=flat-square&logo=tailwindcss&logoColor=white">
  <img alt="Docker: self-hosted" src="https://img.shields.io/badge/Docker-self--hosted-2496ED?style=flat-square&logo=docker&logoColor=white">
  <img alt="License: GPL-3.0" src="https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square&logo=gnu&logoColor=white">
</p>

**A self-hosted, read-only Bitcoin dashboard powered by Electrum.** One extended public key, one application. No database, signing or spending.

> [!WARNING]
> **No built-in authentication.** This application ships no login or access control of its own. Never expose it directly to the internet — always place it behind a secure, authenticated reverse proxy (HTTPS, with WebSocket support, preserved `Host`/`Origin` headers and timeouts above 90s). The built-in origin checks are hardening, not access control.
>
> 🔒 Supply only an extended **public** key at runtime. Never provide a seed phrase or private key, or expose the API directly to the Internet.

### Why I built this

Like many people, I run an Electrum node that I deliberately keep **off the public internet**. I just wanted to check my wallet and balance from anywhere — without the usual trade-offs:

- 🚫 **No VPN** to tunnel back home.
- 🔒 **No public key** pasted into third-party websites.

**Wallet Viewer does exactly that:** point it at your own Electrum server, feed it a watch-only xpub, and get a dashboard you fully host and control.

### Why you'll love it

- 🛡️ **Watch, never touch.** The app only ever sees a public key — nothing to sign, nothing to spend, nothing to steal.
- 🚀 **Up in one command.** A single self-contained container. No database, no accounts, no background jobs to babysit.
- ⚡ **Truly live.** Electrum notifications stream balance and transaction updates the instant they land — no refresh, no polling.
- 🔎 **See everything.** Fees, UTXOs, input/output graphs and derivation paths in a fast, responsive Bitcoin-orange dark UI.
- 🕵️ **Your node, your privacy.** Point it at your own Electrum server; only script hashes ever leave home, never your xpub.

[Features](#features) · [Architecture](#architecture) · [Stack](#stack) · [Native](#native-vs-jvm) · [Docker](#docker) · [Demo](#demo-mode) · [Configuration](#configuration) · [Logs](#logs) · [Security](#security-and-limitations) · [License](#license)

<div align="center">

### 🧪 DEMO SCREENSHOTS ONLY

**The wallet, balance, addresses and transactions below are _entirely synthetic_.**<br>
**This wallet does NOT exist and holds NO REAL FUNDS.**<br>
**Nothing shown here corresponds to any real Bitcoin wallet, address or transaction.**

_Screenshots from [demo mode](#demo-mode)._

</div>

<p align="center"><img src="docs/dashboard.png" alt="Bitcoin Wallet Viewer dashboard showing mock data" width="820"></p>

<p align="center"><img src="docs/transaction.png" alt="Bitcoin Wallet Viewer transaction details popup showing mock data" width="820"></p>

<p align="center"><img src="docs/balance-history.png" alt="Bitcoin Wallet Viewer balance-over-time chart showing mock data" width="820"></p>

## Features

- 💰 **Live wallet:** balances, confirmations, Activity / UTXO tabs, sorting and click-to-copy identifiers.
- 🔍 **Transaction details:** a full-width dialog with **Graph** and **Inputs / Outputs** tabs, fees and compact input/output graphs with independent pagination (five items per side).
- 📈 **Balance history:** an interactive balance-over-time chart (lightweight-charts) with an optional fiat-value curve, selectable periods and per-curve visibility saved locally, plus a live mempool fee gauge in the header.
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
		P[Fiat / fee / history services] --> A
	end
	S -->|Script-hash queries| E
	M[mempool.space] -->|Public quotes via REST client| P
	A <-->|REST / live snapshots| V[Vue dashboard]
```

- Addresses are derived locally; only script hashes reach Electrum, never the extended public key. Notifications trigger serialized scans, not periodic wallet polling.
- WebSocket pushes versioned snapshots; REST reads the same cache. Refresh replays cached data, and transaction details load on demand via native `fetch`, with cancellation and request deadlines.
- State is in memory and rebuilt after restart. During outages, the UI keeps the last snapshot with a stale/offline warning. Fiat quotes, fee estimates and price history come separately from [mempool.space](https://mempool.space/api/v1/prices), fetched by a declarative reactive REST client (MicroProfile REST Client) and shared/cached per TTL, without wallet identifiers.
- Receive-address QR codes use a small vendored, dependency-free encoder (adapted from [Project Nayuki's QR-Code-generator](https://www.nayuki.io/page/qr-code-generator-library), MIT) and are served as **SVG** (crisp at any zoom, no image library); an address's QR is immutable, so results are memoized in a bounded cache.

## Stack

| Component | Version / source |
| --- | --- |
| Java / Maven builder | Java **25**; Maven **3.9.16**, Eclipse Temurin 25 Docker build image |
| Backend | Quarkus **3.39.3**, SmallRye OpenAPI, Quinoa **2.9.0**, bitcoinj **0.17.1** (QR codes via a vendored, dependency-free encoder) — [pom.xml](pom.xml) |
| Reactive transport / cache | Vert.x (TCP client for Electrum), MicroProfile REST Client (mempool.space), Mutiny and Caffeine — versions managed by the Quarkus BOM |
| Node.js | **24.21.0**, installed by Quinoa — [src/main/resources/application.properties](src/main/resources/application.properties) |
| Frontend (locked) | Vue **3.5.42**, Vite **8.3.0**, @vitejs/plugin-vue **6.0.8**, vue-tsc **3.3.11**, TypeScript **5.9.3**, Tailwind CSS **4.3.3** (via @tailwindcss/vite), @types/node **24.13.4** — [src/main/webui/package-lock.json](src/main/webui/package-lock.json) |
| UI libraries | Material Design Icons (@mdi/js) **7.4.47**, @formkit/auto-animate **0.10.0**, lightweight-charts **5.2.1** |
| Runtime image | **Native (GraalVM)** on Distroless Debian **13**, `nonroot` — [Dockerfile.native](Dockerfile.native); JVM variant on Distroless Java **25** — [Dockerfile](Dockerfile) |

Versions reflect declarations and the npm lockfile. For local development, use JDK 25 and `mvn quarkus:dev`; Quinoa manages Node automatically.

Frontend TypeScript enables `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `verbatimModuleSyntax`, and `erasableSyntaxOnly`. Run `npm run build` from `src/main/webui` to execute tests, type checking, and the production build. Vite targets `esnext`: use an up-to-date browser with native `toSorted()` and `Map.groupBy()` support. No legacy-browser fallback or polyfill is included.

API responses are validated at runtime before reaching Vue, with shared snapshot and transaction validators for REST and WebSocket. API snapshots use `shallowRef` and are replaced as a whole. Template references use Vue 3.5 `useTemplateRef`; obsolete dialog watchers are invalidated before opening. HTTP cancellation uses native `AbortSignal.any()` with a deadline that covers reading the response body and is cleared when the request finishes.

## Native vs JVM

Backend RPC responses are decoded explicitly into validated Electrum records before use; malformed or missing values fail the scan instead of becoming zero balances. Wallet and Electrum settings use validated SmallRye `@ConfigMapping` interfaces. Local derivation data is immutable (`AddressInfo` record), hex conversion uses Java `HexFormat`, and business timestamps use an injectable UTC `Clock`; elapsed-time deadlines remain monotonic.

Live updates use Quarkus WebSockets Next with isolated per-connection state, one pending snapshot at most, a 10-second send deadline, and a 30-second ping / 90-second pong timeout. The HTTP upgrade rejects missing, ambiguous or foreign origins with 403. WebSocket traffic logging and Dev UI message retention are disabled.

Every published Docker image (`dev`, `latest`, `X.Y.Z`) is compiled to a **GraalVM native** executable: CI builds the runner with `-Dnative` and ships it on a distroless base ([Dockerfile.native](Dockerfile.native)). A classic JVM image stays available via [Dockerfile](Dockerfile).

| Metric | Native (GraalVM) | JVM (HotSpot) |
| --- | --- | --- |
| Startup time | **~20 ms** | ~1–2 s |
| Memory (RSS, idle) | **~50–70 MB** | ~150–250 MB |
| Container image | Smaller (no JRE) | Larger (bundled JRE) |
| Peak throughput | Slightly lower | Higher under sustained load (JIT) |
| Build time | Slow (native compilation, minutes) | Fast |
| Runtime dependency | None (self-contained binary) | JRE |

Figures are indicative for this application on `linux/amd64` (native startup measured at ~18 ms, ~55 MB RSS). Native fits a small, always-on self-hosted dashboard well: near-instant restarts and a low, stable footprint, at the cost of longer build times. Build the native runner locally with:

```sh
mvn verify -Dnative -Dquarkus.native.container-build=true -Dquarkus.native.builder-image=quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25
docker build -f Dockerfile.native -t wallet-viewer:native .
```

See **[Docker & deployment](docs/DOCKER.md#native-image-graalvm)** for the full native workflow.

`mvn verify` also runs the live-wallet integration scenario against the packaged application (native when `-Dnative` is enabled), using a loopback-only Electrum fixture: REST/WebSocket consistency, credit notifications, cache replay, reconnection and origin rejection. It does not query a real wallet.

## Docker

Requires Docker with Compose v2 — no local Java, Maven or Node needed.

1. Copy [.env.example](.env.example) to **.env** and set `WALLET_XPUB` to your account-level extended public key (keep it out of Git).
2. Build, start, then open <http://localhost:8080>:

```sh
docker compose up -d --build
```

See **[Docker & deployment](docs/DOCKER.md)** for GHCR images, the **native (GraalVM)** build, script-type/network options, security hardening, custom ports and releases.

## Demo mode

Set **`WALLET_DEMO=true`** for a synthetic wallet with varied transactions and evolving confirmations, without an Electrum connection. Remove real wallet overrides before sharing screenshots.

See **[Demo mode guide](docs/DEMO.md)** for setup, simulation behaviour, privacy precautions and tests.

## Configuration

Supply wallet settings **at runtime**, never as build arguments or in source. Defaults below are for Compose; application-only differences are noted.

| Variable | Default | Purpose |
| --- | --- | --- |
| `WALLET_DEMO` | `false` | Synthetic wallet without Electrum; events every 15s and simulated blocks every 60s. See [Demo mode](docs/DEMO.md) |
| `WALLET_XPUB` | Required unless demo | Account-level extended public key, not a master key or single address |
| `WALLET_SCRIPT_TYPE` | `auto` | `auto`, `p2pkh`, `p2sh-p2wpkh`, `p2wpkh`, `p2tr`; explicitly select `p2tr` for BIP86 |
| `WALLET_NETWORK` | `mainnet` | `mainnet` or `testnet`; use a compatible Electrum server on the same network |
| `WALLET_GAP_LIMIT` | `20` | Consecutive unused addresses stopping discovery on each chain |
| `WALLET_MAX_ADDRESSES` | `200` | Maximum history-scanned addresses per receive/change chain |
| `WALLET_RPC_CONCURRENCY` | `8` | Maximum concurrent RPCs per scan group; up to four groups run together (balance, UTXOs, headers, transactions), not a global transport limit |
| `ELECTRUM_HOST` | `electrum.blockstream.info` | Reachable Electrum hostname; the default targets mainnet |
| `ELECTRUM_PORT` | `50002` | Server port; application-only default is `50001` |
| `ELECTRUM_SSL` | `true` | TLS with certificate/hostname verification; application-only default is `false` |
| `ELECTRUM_REQUEST_TIMEOUT` | `30s` | Per-RPC timeout |
| `LOG_LEVEL` | `INFO` | Application log level for the `com.comassky.wallet` category; set `DEBUG` for Electrum/scan lifecycle logs |

Inside Docker, `localhost` means the container: use a reachable server hostname. Compose forwards only declared variables and does not mount local Java configuration.

## API

The REST API is documented with OpenAPI 3.1.

- **Hosted docs (ReDoc):** https://comassky.github.io/wallet-viewer/ (published on each release).
- **Swagger UI (development only, `mvn quarkus:dev`):** `/q/swagger-ui`. Disabled in tests and excluded from production builds; `/q/openapi` remains available in production.
- **OpenAPI spec (running app):** `/q/openapi` (add `?format=json` for JSON).

`GET /api/wallet/state` returns the same versioned envelope as the WebSocket: `status`, `message`, `snapshot` and `updatedAt` (Unix seconds of the last successful snapshot, or `null` before the first scan). The timestamp is retained through outages. Existing snapshot endpoints keep their response shape and may return cached data; use `/state` when freshness matters.

Real scan snapshots include `discovery`: `complete`, `receiveScanned`, `changeScanned`, `addressLimit` and `gapLimit`. Complete means both configured unused-address gaps were reached and the receive address is covered, not that addresses beyond those gaps were searched. Synthetic snapshots may omit discovery metadata.

Balance-history points retain `balanceSats` when market data is unavailable. `valueEur` and `valueUsd` are nullable, with `priceTime` identifying the quote used and `priceStale` indicating a failed provider refresh. No future quote is substituted for dates before price history begins. Successful price history is cached for 30 minutes; failed refreshes retain the last valid quotes and can retry after 30 seconds. The balance-history endpoint itself has a 60-second cache.

## Logs

Control verbosity with **`LOG_LEVEL`** (default `INFO`; set `DEBUG` for Electrum connection, notification and scan-lifecycle logs). See **[Logging](docs/LOGS.md)** for levels and privacy details.

**⚠️ Logs contain wallet addresses:** keep them private and redact them before sharing.

## Security and limitations

- 🔓 **No authentication:** keep access local or use an authenticated HTTPS proxy with WebSocket support, preserved `Host`/`Origin` headers and timeouts above 90 seconds. Origin checks are not access control.
- 🕵️ **Privacy:** public keys expose account history; Electrum can correlate scripts. Use a trusted server and never provide spending secrets.
- **Hide amounts:** replaces displayed wallet amounts, including dialogue values, with a neutral label and clears the history chart. Notifications never include amounts. This is a display preference, not access control: identifiers and API data remain available to authorized users of the browser.
- 🎯 **Bounded discovery:** funds beyond the gap/address limits may be missed. An extra receive address beyond the cap is watched without history; raise `WALLET_MAX_ADDRESSES` before relying on its balance.
- 📊 **Estimates:** missing parent transactions prevent fee calculation; graph edges do not allocate inputs to outputs. Fiat on balances and transactions uses current quotes; only the balance-history chart values each day at its historical price.

## License

Released under the [GNU General Public License v3.0](LICENSE).
