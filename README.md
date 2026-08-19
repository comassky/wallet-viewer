# Wallet Viewer

![Java 25](https://img.shields.io/badge/Java-25-437291?style=flat-square)
![Quarkus 3.39.2](https://img.shields.io/badge/Quarkus-3.39.2-4695EB?style=flat-square)
![Vue 3](https://img.shields.io/badge/Vue-3-42B883?style=flat-square)
![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=flat-square)
![Docker](https://img.shields.io/badge/Docker-2496ED?style=flat-square)

**A read-only Bitcoin wallet dashboard, powered by Electrum.**

View balances, transactions, unspent outputs and a receiving address from an account-level extended public key. Java 25 and Quarkus serve the Vue interface as a single JVM application; there is no signing or spending functionality.

[Features](#features) · [Quick start](#quick-start-with-compose) · [Currencies](#currencies-and-price-estimates) · [Development](#local-development-and-build) · [Configuration](#configuration) · [Docker & GHCR](#docker-and-ghcr) · [Security](#security-and-limitations)

> **Private by deployment, not by authentication.** The API has no authentication. Keep it on localhost or behind an authenticated HTTPS proxy. Supply only an account-level **extended public key at runtime** — never a seed phrase, xprv or other private key.

## Features

- **Wallet overview:** confirmed and unconfirmed balances, transaction history, confirmations and UTXOs retrieved through Electrum.
- **Live updates:** Electrum subscriptions rebuild a shared snapshot and push versioned state over WebSocket. Last-known data remains visible with a stale/offline status during outages.
- **HD account support:** legacy BIP44, wrapped SegWit BIP49, SegWit BIP84 and Taproot BIP86, with separate receive/change scans.
- **Receive view:** address, derivation path, copy feedback and a QR code tied to the displayed address index. The receiving address follows the last used receiving address found by the scan.
- **Currency on the balance:** click the total balance to choose EUR, USD, SAT or BTC. The preference is saved immediately in localStorage and restored on reload; no currency buttons in the header.
- **Transaction explorer:** select a transaction to inspect its inputs, outputs, scripts and network fee, with a scrollable input → transaction → output graph. Data comes from your configured Electrum server, not an external explorer.
- **Responsive interface:** a full-width desktop dashboard, mobile transaction/UTXO cards, wrapping addresses and touch-friendly controls.
- **Enlarged QR popup:** click or tap the receiving QR to view a larger image and copy its address. Close with the button, Escape or a click outside the popup; keyboard focus returns to the QR button.
- **Self-hosted deployment:** one multi-stage Docker build, a non-root Java runtime and a localhost-bound Compose service.

The interface, messages and documentation are in English, with `en-US` number and date formatting. The QR popup keeps the same address and image even if a wallet refresh completes while it is open.

## Quick start with Compose

Requires Docker with Compose v2. No local Java, Maven or Node installation is needed for this route.

1. Copy [.env.example](.env.example) to a local `.env` file and set `WALLET_XPUB` to your **account-level extended public key**. Keep this file out of version control.
2. Choose the correct network and script type. **For Taproot/BIP86, explicitly set `WALLET_SCRIPT_TYPE=p2tr`.** The `auto` setting cannot distinguish a BIP86 xpub from a BIP44 xpub.
3. Build and start the service from the repository root:

   ```sh
   docker compose up -d --build
   ```

4. Open <http://localhost:8080>.

To follow logs or stop the service:

```sh
docker compose logs -f wallet-viewer
docker compose down
```

[compose.yaml](compose.yaml) loads the declared service variables from your environment or `.env` and refuses to start with an empty `WALLET_XPUB`. It does not mount your local Java configuration. Set `WALLET_VIEWER_PORT` to change the host port; the binding remains `127.0.0.1`.

The service restarts unless stopped, runs with a read-only filesystem and an in-memory temporary directory, drops all Linux capabilities, disallows privilege escalation and rotates logs. No persistent volume is required.

> **Electrum networking:** `localhost` inside the container refers to the container itself. For your own Electrum server, use a reachable Docker network hostname, or `host.docker.internal` on Docker Desktop when the server runs on the host. Match the Electrum server to the wallet network.

## Currencies and price estimates

Click the **total balance** to reveal the **EUR / USD / SAT / BTC** selector. It applies to balances, transactions, UTXOs and the transaction detail graph. BTC is the default; the browser immediately saves the selected unit under `wallet-viewer.currency` in localStorage and restores it on reload. SAT is stored as `SATS` for compatibility with earlier preferences. Storage restrictions do not prevent changing the display. Wallet data stays in satoshis on the backend — display conversions do not change it.

For EUR or USD:

- The browser calls `GET /api/wallet/prices`; the backend fetches public market data from <https://mempool.space/api/v1/prices>.
- Requests have a **10-second timeout**. Both successful responses **and failures** are cached for **60 seconds**, so retrying may still return a cached failure.
- Incoming quotes older than **15 minutes** are rejected. The browser refreshes quotes every minute while a fiat unit is selected.
- Conversions are **estimates at the current price**, including amounts on old transactions. They are **not historical valuations**. The quote timestamp is displayed.
- If fetching fails, the browser retains its last known quote **with a warning**. That retained estimate can age; rejecting stale incoming quotes does not discard an already displayed quote. Without a previous quote, fiat amounts show a dash. BTC and SATS remain available without the price service.

**No wallet key, address or transaction is sent to the price provider.** This is separate from Electrum queries.

`WALLET_PRICES_URL` can override the provider. The response must contain positive numeric `EUR` and `USD` values and a `time` Unix timestamp in seconds. See the Compose forwarding caveat below.

## Local development and build

### Requirements

| Component | Version / behavior |
| --- | --- |
| Java | **JDK 25**; ensure `JAVA_HOME` and Maven both use it |
| Maven | **3.9+**; the Docker builder uses 3.9.12 |
| Quarkus | **3.39.2** |
| Quinoa | **2.9.0** |
| Wallet cache | Caffeine, one in-memory live state per application instance; no TTL eviction or persistence |
| Node.js | **24.20.0 (LTS)**, installed and managed automatically by Quinoa |
| Frontend | Vue 3, TypeScript, Vite and Tailwind CSS |

No global Node installation is required for the Maven workflow. Quinoa uses a version-specific local Node cache. The runtime remains Java 25 on the JVM; live updates do not introduce a native build.

Provide `WALLET_XPUB` in the application's runtime environment. For local development, Quarkus also accepts `wallet.xpub` in the ignored [config/application.properties](config/application.properties). Do not put personal wallet configuration in the tracked source defaults or build arguments.

Start development mode from the repository root:

```sh
mvn quarkus:dev
```

Open <http://localhost:8080>. Do not expose the development server to an untrusted network.

Build and verify the JVM application:

```sh
mvn --batch-mode --no-transfer-progress verify -Dquarkus.quinoa.ci=true
```

This runs the Java regression tests, installs frontend dependencies with `npm ci`, runs the frontend tests, checks TypeScript and builds the Vue assets and Quarkus application. The npm lockfile is versioned. Tests use fixture data and a local TCP server; they do not require a personal wallet key or a live external Electrum server.

Run the packaged application, with wallet configuration supplied at runtime:

```sh
java -jar target/quarkus-app/quarkus-run.jar
```

Keep the complete Quarkus application directory together, not just the runner JAR.

### Regression coverage

Regression areas include BIP86 derivation through production code, wallet scanning and receiving address selection, REST error handling, Electrum protocol behavior using a local server, price validation/cache behavior, frontend currency conversion, HTTP/clipboard utilities and the wallet stream. Tests have not been run for this documentation/configuration update; no passing result or test count is claimed. Build verification is not a substitute for checking your own account settings, server connectivity and deployment security.

## Frontend architecture

### Transaction details

Click a transaction ID or **Explore** in Activity to open the native detail dialog. It shows every input's previous output, output addresses and scripts, total amounts and the network fee. The SVG graph includes every input and output and can be scrolled on small screens or for large transactions. Connections show structure, not which input funded which output. Full addresses remain selectable in the lists below.

`GET /api/wallet/transactions/{txid}` accepts only a valid transaction ID present in the current cached wallet history (400 for malformed IDs, 404 for unknown transactions, 503 before initialization). Details are fetched on demand without rescanning the wallet or enlarging WebSocket messages. The server fetches and verifies the raw transaction and its distinct parents through Electrum, with at most eight concurrent parent lookups and a 30-second overall timeout. Fees are the sum of **all** resolved input values minus output values, never the wallet's net balance change. Coinbase inputs and fees are explicitly marked not applicable. Scripts with no supported address retain their script hex.

If a transaction or ancestor cannot be retrieved or validated, the endpoint returns 502 instead of inventing amounts. The dialog offers a retry; closing it cancels the browser request. Confirmation status is captured when opening the dialog; reopen to see updated confirmations. There are no external explorer requests or links. No ownership is inferred from the input/output graph.

The Vue application separates presentation, state/lifecycle and transport without a global store:

| Layer | Responsibility |
| --- | --- |
| [App.vue](src/main/webui/src/App.vue) | Compose the dashboard and connect events/props |
| Components | Header, price notice, balance, receive card, QR dialog, transactions and UTXOs; shared status/badge rendering |
| [useWallet](src/main/webui/src/composables/useWallet.ts) | Live wallet state over same-origin WebSocket, connection-scoped versions, automatic reconnect, cached replay on refresh and preservation of stale data |
| [useCurrency](src/main/webui/src/composables/useCurrency.ts) | Saved unit, price refresh timer and amount formatting |
| [useClipboard](src/main/webui/src/composables/useClipboard.ts) | Independent copy feedback, stale-result protection and timer cleanup |
| Wallet stream | Complete versioned state updates from `/api/wallet/live` over `ws`/`wss`; reset version tracking on each new connection |
| [walletApi](src/main/webui/src/services/walletApi.ts) / [HTTP transport](src/main/webui/src/services/http.ts) | Typed REST endpoints, JSON requests, timeout and cancellation; wallet snapshot endpoints share the live cache |
| [Wallet types](src/main/webui/src/types/wallet.ts) | API contracts, with amounts in satoshis |
| [Currency formatting](src/main/webui/src/currency.ts) / [display helpers](src/main/webui/src/utils/format.ts) | Shared pure presentation functions |

The [QR dialog](src/main/webui/src/components/ReceiveQrDialog.vue) owns its address snapshot and native dialog lifecycle. It stays mounted across dashboard refreshes. Components receive data through typed props and report actions through typed events. The Vite development proxy enables WebSocket forwarding with `ws: true`. The separate 60-second fiat-price timer remains; it does not poll the wallet.

## Electrum connection and live updates

The backend opens one persistent, outbound TCP connection to Electrum (TLS when enabled), sends newline-delimited JSON-RPC and matches responses by numeric request ID. An xpub/zpub stays on the backend: it is used to derive receive/change scripts locally. **The extended key is never sent to Electrum; subscriptions use only derived script hashes.** Electrum has no direct xpub/zpub subscription. Notifications arrive over the existing outbound connection, not through a webhook or a new inbound connection, and signal changes rather than supplying a complete wallet snapshot.

### Backend architecture

| Layer | Current behavior |
| --- | --- |
| `WalletService.scan()` | Uncached full scan; subscribes to each derived script hash before requesting its history and subscribes to block headers for tip/confirmation updates |
| Receive/change discovery | Each chain has its own gap limit and maximum history-scan cap; per-process watermarks prevent receive-address regression and shrinkage of the previously discovered history window |
| Electrum monitor | Proactive connection monitoring, reconnect retries from 1 to 30 seconds, a 30-second heartbeat and subscription restoration after reconnect |
| `WalletLiveService` | Registers Electrum listeners and starts monitoring at application startup; a single worker coalesces full scans with a 200 ms debounce |
| Caffeine live cache | `maximumSize(1)`, no TTL eviction; holds an immutable snapshot plus version and status (`loading`, `syncing`, `live`, `offline`, `error`); commits state before notifying consumers |
| Scan consistency/retry | Discards a scan result if an update arrives during that scan and schedules a follow-up; retries failed scans after 5 seconds |
| Consumers | WebSocket and REST read the same cache; browser refresh does not trigger a scan |

The next receive address is watched even when it falls beyond the maximum history-scan cap, but its history is **not fetched** there. **Increase `WALLET_MAX_ADDRESSES` to include that address in the scan before relying on accurate funds at that address.** A subscription alone does not include its funds in the snapshot. The gap limit can also prevent discovery of more distant activity on either chain.

The former `wallet.cache-ttl-seconds` / `WALLET_CACHE_TTL_SECONDS` setting is unused and has been superseded by the event-driven cache. There is **no scheduled full reconciliation** currently. The cache and discovery watermarks are in-memory, nonpersistent and instance-local: restart rebuilds them, and replicas do not share state.

### Browser stream and REST snapshots

- `/api/wallet/live` uses same-origin `ws` (HTTP) or `wss` (HTTPS). Each connection receives the initial cached state and subsequent **complete, versioned state updates**, not partial deltas.
- Frontend refresh sends the literal text `refresh`: the server replays the cache without running a wallet scan.
- The frontend automatically reconnects and resets version tracking for each new connection, so a restarted backend's lower versions are accepted. Last-known wallet data remains visible when offline, with a stale/status indication.
- REST wallet snapshot endpoints read the same cache and return **503 until a snapshot is initialized**. During outages they continue serving the last-known snapshot; the WebSocket status indicates that it is stale. A successful REST response alone does not prove freshness.
- The independent 60-second fiat-price timer remains unchanged and does not reconcile wallet data.

### Reverse proxy and WebSocket security

The WebSocket handshake validates the `Origin` header against `Host` to prevent cross-site WebSocket hijacking. **An absent `Origin` is rejected. This is not authentication** and does not protect the REST API or prevent non-browser clients from supplying headers.

Keep the service local or behind an authenticated reverse proxy. For `/api/wallet/live`, use HTTP/1.1 to the upstream, preserve the browser's `Host` and `Origin`, and forward the `Upgrade` and `Connection` headers for the WebSocket upgrade. Do not rewrite `Host` to an internal upstream name or remove `Origin`. Set proxy idle/read timeouts **greater than 90 seconds** and use HTTPS externally so the browser connects with `wss`. The Vite development proxy already enables `ws: true`; production proxies need their own WebSocket configuration.

## Configuration

Environment variables override the tracked [application defaults](src/main/resources/application.properties). Wallet keys belong at **runtime**, never in the image build, GitHub build secrets or source code.

| Variable | Default | Purpose |
| --- | --- | --- |
| `WALLET_XPUB` | Required | Account-level extended **public** key; never a seed or extended private key |
| `WALLET_NETWORK` | `mainnet` | Use `testnet` for a testnet account and select a matching Electrum server |
| `WALLET_SCRIPT_TYPE` | `auto` | `auto`, `p2pkh`, `p2sh-p2wpkh`, `p2wpkh` or `p2tr`; BIP86 requires explicit `p2tr` |
| `WALLET_GAP_LIMIT` | `20` | Consecutive unused addresses that stop scanning **each chain** |
| `WALLET_MAX_ADDRESSES` | `200` | Maximum history-scanned addresses **per chain**; a next receive address beyond the cap is watched without history and requires a larger cap for accurate funds |
| `ELECTRUM_HOST` | `electrum.blockstream.info` | Electrum server reachable from the application |
| `ELECTRUM_PORT` | Compose: `50002`; app: `50001` | Server TCP/TLS port |
| `ELECTRUM_SSL` | Compose: `true`; app: `false` | TLS with certificate and hostname verification |
| `ELECTRUM_REQUEST_TIMEOUT` | `30s` | Timeout **per RPC request**, not for the complete wallet scan |
| `WALLET_PRICES_URL` | `https://mempool.space/api/v1/prices` | Public EUR/USD price endpoint |
| `WALLET_VIEWER_PORT` | `8080` | Compose-only host port, bound to `127.0.0.1` |
| `WALLET_VIEWER_IMAGE` | `wallet-viewer:local` | Compose-only image name; set a GHCR reference to use a published image |

**Compose forwarding:** the supplied service explicitly forwards the wallet and Electrum variables listed in [compose.yaml](compose.yaml). It does **not** currently forward `WALLET_PRICES_URL`; putting that variable in `.env` alone will not pass it into the container. Supply it directly to the JVM/container environment or through your own Compose override.

**Derivation matters.** Supply an account-level public key, not a master key or an individual address. Automatic script selection uses the key's encoding; a plain xpub does not identify BIP86. For Taproot, explicitly choose `p2tr`.

**Scanning is bounded.** Funds beyond the configured gap limit or maximum may not be discovered. Both limits apply independently to receive and change chains. A next receive address beyond the maximum is subscribed without fetching its history: increase the maximum to account for its funds accurately. Per-process watermarks prevent the receive address and discovered history window from regressing, but do not persist across restarts or bypass the scan cap. Adjust limits to the account's history; larger limits can increase scan time and server load.

## Docker and GHCR

### Build a local image

[Dockerfile](Dockerfile) uses two stages:

1. **Build:** Maven 3.9.12 with Eclipse Temurin 25 runs `mvn verify`; Quinoa installs Node and builds the frontend.
2. **Runtime:** Eclipse Temurin **25 JRE** on Jammy runs the packaged Quarkus application as **UID/GID `10001:10001`**, listening on port 8080.

```sh
docker build -t wallet-viewer:local .
docker run --rm --name wallet-viewer --env-file .env -p 127.0.0.1:8080:8080 wallet-viewer:local
```

Unlike Compose, `docker run` does not apply the service's environment defaults: include the desired Electrum TLS settings in your environment file. The additional read-only filesystem, capability and log restrictions described above come from Compose, not the image alone.

[.dockerignore](.dockerignore) allowlists build inputs, excluding local wallet configuration, Node caches, installed dependencies and previous build outputs. The source tree is included: **never place personal keys in source files or Docker `ARG` values**.

### Use a published image

Set `WALLET_VIEWER_IMAGE=ghcr.io/<owner>/<repository>:latest` in your local environment file, replacing the placeholders with the lowercase repository path. Then use these commands **instead of building locally**:

```sh
docker compose pull
docker compose up -d --no-build
```

Private packages require registry authentication on the deployment machine. For reproducible deployments, prefer a specific release tag or image digest to the moving `latest` tag.

### GitHub Actions publishing

[.github/workflows/docker.yml](.github/workflows/docker.yml) builds **`linux/amd64`** images and uses the GitHub Actions BuildKit cache.

| Event | Behavior |
| --- | --- |
| Pull request | Builds and verifies the image; no registry login or publication |
| Push to `main` | Builds and publishes branch and `sha-…` tags; `latest` applies when this is the default branch |
| Version tag such as `v1.2.3` | Publishes SemVer tags such as `1.2.3`, `1.2` and a `sha-…` tag |
| Manual dispatch | Builds the selected reference; publishes only from `main` or a `v*` tag |

The push trigger accepts `v*`; use valid SemVer tags for semantic version aliases. Docker metadata may also assign `latest` to version releases, so treat it as a moving tag.

The Docker build runs verification before producing an image: test or compilation failures prevent publication. Publishing uses the automatically supplied `GITHUB_TOKEN` with `packages: write`; no personal token or wallet key is needed in GitHub secrets. Organization policies must permit GHCR publishing, and an existing package must grant the repository access. Update the `main` filters and publication conditions if the primary branch changes.

**The workflow publishes an image; it does not deploy the application.**

## Security and limitations

- **No authentication:** anyone who can reach the API can query wallet information. Keep the default localhost binding, or use a private network / authenticated reverse proxy with HTTPS. Do not expose the API directly to the Internet.
- **WebSocket origin checks are not access control:** `/api/wallet/live` requires an `Origin` matching `Host` and rejects a missing origin. Preserve these headers through proxies and configure HTTP/1.1 upgrades, timeouts greater than 90 seconds and TLS/wss as described above.
- **Read-only is not anonymous:** an extended public key cannot spend funds, but it reveals account addresses and history. The Electrum server can correlate the queried addresses. Use a server you trust.
- **Never supply spending secrets:** only use an account-level extended public key at runtime. Never enter a seed phrase, xprv or any other private key.
- **TLS defaults differ:** Compose uses TLS; the base development configuration uses plain TCP. For a server with a self-signed certificate, configure a JVM truststore that trusts it. Certificate and hostname checks are not silently disabled.
- **Transaction details depend on Electrum history availability:** all previous outputs must be retrievable to compute fees. Addresses use the configured Bitcoin network. Details stay in the local dashboard; no external explorer is contacted.
- **Results depend on scan settings and Electrum:** a wrong script type, wrong network or insufficient gap/maximum can hide activity. A watched receive address beyond the history cap is not enough to account for its funds. Cached snapshots may lag the server or remain stale during outages; REST serves the last-known snapshot while WebSocket status exposes staleness. There is no scheduled full reconciliation. A per-request timeout does not impose a single deadline on the entire scan.
- **Fiat amounts are estimates:** current-price conversions are not historical accounting values. A retained last-known quote is explicitly marked when refreshing fails; use BTC/SATS when prices are unavailable or unsuitable.
- **Previously reported development dependency issues:** an earlier npm audit reported **two vulnerabilities**, Vite (**high**) and esbuild (**moderate**). The proposed remediation required a major Vite migration that is not included here. This is a prior finding, not a fresh audit result. Do not expose the development server; those build tools are not shipped in the final JRE image.
- **Git history retains old disclosures:** removing a previously committed key from the current file does not remove it from history. Treat that account's privacy as affected.
- **Ignored is not untracked:** generated Quinoa caches belong outside version control. Adding an ignore rule does not untrack files that were already committed; remove them from the index in a dedicated cleanup.