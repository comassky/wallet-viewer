# Logging

Console logs use a compact format with a full timestamp, an aligned level, the
short category name and the thread. Exception stack traces are preserved.

```text
2026-09-11 12:02:57.458 INFO  [quarkus] (main) Profile prod activated.
```

The startup banner uses ASCII lettering and a compact Bitcoin tagline. Disable
it with `QUARKUS_BANNER_ENABLED=false` for log collectors. Console colors follow
Quarkus terminal detection; use `QUARKUS_LOG_CONSOLE_COLOR=false` for plain text.
Override `QUARKUS_LOG_CONSOLE_FORMAT` to customize the format (`%c` restores full
category names instead of `%c{1}`).

Control verbosity with **`LOG_LEVEL`** (classic Quarkus, default `INFO`); it maps to the `com.comassky.wallet` category.

- **INFO (default):** startup details such as the detected wallet script type and masked public key, plus warnings and errors. No full keys, balances or raw payloads.
- **DEBUG (`LOG_LEVEL=DEBUG`):** Electrum connection changes, address notifications, wallet scan lifecycle and per-RPC method/duration/outcome.

**⚠️ Logs contain wallet addresses:** keep them private and redact them before sharing.
