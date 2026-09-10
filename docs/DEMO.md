# Demo Mode

[Back to README](../README.md#demo-mode)

Demo mode provides a synthetic wallet for screenshots and UI previews without an Electrum connection. `DemoWallet` maintains a synthetic ledger and `DemoService` publishes it through the normal REST/WebSocket cache. This is a UI simulation, not an Electrum protocol server; the loopback Electrum fixture used by integration tests is separate.

## Setup

Set `WALLET_DEMO=true` in your `.env` and use `WALLET_NETWORK=mainnet`. Remove real wallet overrides as described below, then run from the project root:

```sh
docker compose up -d --build
```

Open <http://localhost:8080>. See [Docker & deployment](DOCKER.md) for deployment options.

## Simulation

- **38 initial transactions**: 36 confirmed transactions spanning roughly 90 days, plus an incoming and an outgoing mempool transaction. The history includes single/multiple-input receipts, batched payments with change, and internal consolidations.
- **An event every 15 seconds**: receipt, payment, consolidation, then a block. New transactions start at zero confirmations with no block timestamp. Every simulated block (60 seconds) confirms pending transactions and increments existing transaction and UTXO confirmations.
- **Consistent accounting**: spending consumes existing outputs, change uses the change chain, internal transfers cost only their fee, and pending outgoing amounts may make the unconfirmed balance negative. Transaction details, fees, balances and remaining UTXOs agree.
- **Credible identifiers and scripts**: bitcoinj serializes unsigned transactions to derive their txids and sizes. Counterparties use matching legacy, SegWit or Taproot addresses/scripts. These transactions are fictitious, unsigned and never broadcast; explorer lookups will not find them.
- **Repeatable and bounded**: the random seed is fixed and historical dates are relative to startup. At 200 transactions, new transaction creation stops while block confirmations continue. The complete accounting history and details remain available; restarting resets the scenario.

## Privacy and Configuration

A bundled public BIP86 test key is supplied by default. Explicit `WALLET_XPUB` / `WALLET_SCRIPT_TYPE` environment variables or Java system properties can override that default: unset real wallet overrides before sharing screenshots.

Electrum settings are unused, but public price/fee data may still be fetched from mempool.space. See [Configuration](../README.md#configuration) for the full settings reference.

## Tests

Run from the project root under JDK 25:

```sh
mvn test -Dtest=DemoWalletTest -Dquarkus.quinoa=false
```

The tests check the ledger, scripts, serialized txids, fee conservation, double-spend prevention, immutable snapshots and confirmation progression without waiting for real timers.