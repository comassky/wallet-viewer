package com.example.walletviewer.service;

import com.example.walletviewer.derivation.HdWallet;
import com.example.walletviewer.electrum.ElectrumClient;
import com.example.walletviewer.model.TransactionDetailsDto;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Only cached wallet members may initiate a details lookup; ancestors need not belong to the wallet. */
@ApplicationScoped
public class TransactionDetailsService {
    @Inject WalletLiveService live;
    @Inject ElectrumClient electrum;
    @Inject HdWallet wallet;

    public Uni<TransactionDetailsDto> details(String txid) {
        if (txid == null || !txid.matches("[0-9a-fA-F]{64}")) {
            throw new BadRequestException("Transaction id must be 64 hexadecimal characters");
        }
        String normalized = txid.toLowerCase(Locale.ROOT);
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
                            Map<String, Transaction> byId = new HashMap<>();
                            for (Transaction prev : transactions) byId.put(prev.getTxId().toString(), prev);
                            return render(transaction, byId, params);
                        });
            });
        }).ifNoItem().after(Duration.ofSeconds(30)).fail()
                // No server response, raw transaction, or original cause is exposed.
                .onFailure().transform(ignored -> new WebApplicationException("Unable to load transaction details", 502));
    }

    private Uni<Transaction> fetch(String txid, NetworkParameters params) {
        // RPC creation must also be lazy, so the join's concurrency bound applies to actual requests.
        return Uni.createFrom().deferred(() -> electrum.call("blockchain.transaction.get", txid))
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
        List<TransactionDetailsDto.Input> inputs = new ArrayList<>();
        long totalInput = 0;
        for (TransactionInput input : transaction.getInputs()) {
            if (input.isCoinBase()) {
                if (!coinbase) throw new IllegalStateException("Invalid coinbase transaction");
                inputs.add(new TransactionDetailsDto.Input(null, 0, null, null, true));
                continue;
            }
            String txid = input.getOutpoint().getHash().toString();
            long index = input.getOutpoint().getIndex();
            Transaction prev = previous.get(txid);
            if (prev == null || index < 0 || index >= prev.getOutputs().size()) {
                throw new IllegalStateException("Missing previous output");
            }
            TransactionOutput output = prev.getOutput((int) index);
            long value = value(output);
            totalInput = Math.addExact(totalInput, value);
            inputs.add(new TransactionDetailsDto.Input(txid, index, address(output, params), value, false));
        }
        if (inputs.isEmpty() || transaction.getOutputs().isEmpty()) {
            throw new IllegalStateException("Empty transaction");
        }
        List<TransactionDetailsDto.Output> outputs = new ArrayList<>();
        long totalOutput = 0;
        for (TransactionOutput output : transaction.getOutputs()) {
            long value = value(output);
            totalOutput = Math.addExact(totalOutput, value);
            outputs.add(new TransactionDetailsDto.Output(outputs.size(), address(output, params), value,
                    ByteUtils.formatHex(output.getScriptBytes())));
        }
        Long fee = coinbase ? null : Math.subtractExact(totalInput, totalOutput);
        if (fee != null && fee < 0) throw new IllegalStateException("Invalid transaction amounts");
        return new TransactionDetailsDto(transaction.getTxId().toString(), transaction.getVersion(),
                transaction.getLockTime(), transaction.bitcoinSerialize().length, inputs, outputs,
                coinbase ? null : totalInput, totalOutput, fee);
    }

    private static long value(TransactionOutput output) {
        long value = output.getValue().value;
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