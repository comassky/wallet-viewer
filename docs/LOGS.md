# Logging

Control verbosity with **`LOG_LEVEL`** (classic Quarkus, default `INFO`); it maps to the `com.comassky.wallet` category.

- **INFO (default):** startup details such as the detected wallet script type, plus warnings and errors. No keys, balances or raw payloads.
- **DEBUG (`LOG_LEVEL=DEBUG`):** Electrum connection changes, address notifications, wallet scan lifecycle and per-RPC method/duration/outcome.

**⚠️ Logs contain wallet addresses:** keep them private and redact them before sharing.
