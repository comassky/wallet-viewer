package com.comassky.wallet.service;

import com.comassky.wallet.derivation.HdWallet;
import com.comassky.wallet.electrum.ElectrumClient;
import com.comassky.wallet.electrum.ElectrumMethod;
import com.comassky.wallet.model.TransactionDetailsDto;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import org.bitcoinj.base.internal.ByteUtils;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptException;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Only cached wallet members may initiate a details lookup; ancestors need not belong to the wallet. */
@ApplicationScoped
public class TransactionDetailsService {
    @Inject WalletLiveService live;
    @Inject ElectrumClient electrum;
    @Inject HdWallet wallet;
    @Inject DemoService demo;

    public Uni<TransactionDetailsDto> details(String txid) {
        if (txid == null || !txid.matches("[0-9a-fA-F]{64}")) {
            throw new BadRequestException("Transaction id must be 64 hexadecimal characters");
        }
        String normalized = txid.toLowerCase(Locale.ROOT);
        if (demo.enabled()) {
            TransactionDetailsDto demoDetails = demo.details(normalized);
            return demoDetails == null
                    ? Uni.createFrom().failure(new NotFoundException("Transaction not found"))
                    : Uni.createFrom().item(demoDetails);
        }
        return live.snapshot().flatMap(snapshot -> {
            boolean known = snapshot.transactions().stream()
                    .anyMatch(transaction -> normalized.equalsIgnoreCase(transaction.txid()));
            if (!known) return Uni.createFrom().failure(new NotFoundException("Transaction not found"));
            return fetchAuthorized(normalized);
        });
    }

    private Uni<TransactionDetailsDto> fetchAuthorized(String txid) {
        // Defer even synchronous setup failures into this boundary. Authorization/loading errors stay outside it.
        return Uni.createFrom().deferred(() -> {
            NetworkParameters params = wallet.params();
            return fetch(txid, params).flatMap(transaction -> {
                List<Uni<Transaction>> previous = transaction.getInputs().stream()
                        .filter(input -> !input.isCoinBase())
                        .map(input -> input.getOutpoint().getHash().toString())
                        .distinct()
                        .map(id -> fetch(id, params))
                        .toList();
                if (previous.isEmpty()) {
                    return Uni.createFrom().item(render(transaction, Map.of(), params));
                }
                return Uni.join().all(previous).usingConcurrencyOf(8).andFailFast()
                        .map(transactions -> {
                            Map<String, Transaction> byId = transactions.stream()
                                    .collect(Collectors.toMap(prev -> prev.getTxId().toString(), prev -> prev));
                            return render(transaction, byId, params);
                        });
            });
        }).ifNoItem().after(Duration.ofSeconds(30)).fail()
                // No server response, raw transaction, or original cause is exposed.
                .onFailure().transform(ignored -> new WebApplicationException("Unable to load transaction details", 502));
    }

    private Uni<Transaction> fetch(String txid, NetworkParameters params) {
        // RPC creation must also be lazy, so the join's concurrency bound applies to actual requests.
        return Uni.createFrom().deferred(() -> electrum.call(ElectrumMethod.TRANSACTION_GET, txid))
                .map(response -> {
                    String hex = response.getString("result");
                    if (hex == null || hex.isEmpty() || (hex.length() & 1) != 0
                            || !hex.matches("[0-9a-fA-F]+")) {
                        throw new IllegalStateException("Invalid transaction response");
                    }
                    byte[] raw = ByteUtils.parseHex(hex);
                    Transaction transaction = Transaction.read(ByteBuffer.wrap(raw));
                    if (transaction.messageSize() != raw.length || !transaction.getTxId().toString().equals(txid)) {
                        throw new IllegalStateException("Invalid transaction response");
                    }
                    return transaction;
                });
    }

    private static TransactionDetailsDto render(Transaction transaction, Map<String, Transaction> previous,
                                                 NetworkParameters params) {
        boolean coinbase = transaction.isCoinBase();
        final List<TransactionDetailsDto.Input> inputs = new ArrayList<>();
        long totalInput = 0;
        for (TransactionInput input : transaction.getInputs()) {
            if (input.isCoinBase()) {
                if (!coinbase) throw new IllegalStateException("Invalid coinbase transaction");
                inputs.add(new TransactionDetailsDto.Input(null, 0, null, null, true));
                continue;
            }
            final String txid = input.getOutpoint().getHash().toString();
            final long index = input.getOutpoint().getIndex();
            final Transaction prev = previous.get(txid);
            if (prev == null || index < 0 || index >= prev.getOutputs().size()) {
                throw new IllegalStateException("Missing previous output");
            }
            final TransactionOutput output = prev.getOutput((int) index);
            final long value = value(output);
            totalInput = Math.addExact(totalInput, value);
            inputs.add(new TransactionDetailsDto.Input(txid, index, address(output, params), value, false));
        }
        if (inputs.isEmpty() || transaction.getOutputs().isEmpty()) {
            throw new IllegalStateException("Empty transaction");
        }
        final List<TransactionOutput> txOutputs = transaction.getOutputs();
        final List<TransactionDetailsDto.Output> outputs = IntStream.range(0, txOutputs.size())
                .mapToObj(i -> {
                    final TransactionOutput output = txOutputs.get(i);
                    return new TransactionDetailsDto.Output(i, address(output, params), value(output),
                            ByteUtils.formatHex(output.getScriptBytes()));
                })
                .toList();
        final long totalOutput = outputs.stream()
                .mapToLong(TransactionDetailsDto.Output::value)
                .reduce(0L, Math::addExact);
        final Long fee = coinbase ? null : Math.subtractExact(totalInput, totalOutput);
        if (fee != null && fee < 0) throw new IllegalStateException("Invalid transaction amounts");
        return new TransactionDetailsDto(transaction.getTxId().toString(), transaction.getVersion(),
                transaction.getLockTime(), transaction.bitcoinSerialize().length, inputs, outputs,
                coinbase ? null : totalInput, totalOutput, fee);
    }

    private static long value(TransactionOutput output) {
        final long value = output.getValue().value;
        if (value < 0) throw new IllegalStateException("Invalid output amount");
        return value;
    }

    private static String address(TransactionOutput output, NetworkParameters params) {
        try {
            // The installed bitcoinj handles legacy, witness-v0 and P2TR (bech32m) scripts.
            return new Script(output.getScriptBytes()).getToAddress(params).toString();
        } catch (ScriptException unsupported) {
            return null;
        }
    }
}