package com.comassky.wallet.electrum;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.regex.Pattern;

public final class ElectrumResult {
    private static final Pattern TXID = Pattern.compile("[0-9a-fA-F]{64}");

    private ElectrumResult() { }

    public record Balance(long confirmed, long unconfirmed) { }
    public record HistoryEntry(String txid, int height) { }
    public record Unspent(String txid, int index, long value, int height) { }
    public record Tip(int height) { }

    public static Balance balance(JsonObject response) {
        JsonObject result = object(result(response));
        return new Balance(nonNegative(result, "confirmed"), integer(result.getValue("unconfirmed")));
    }

    public static Tip tip(JsonObject response) {
        return new Tip(index(object(result(response)), "height", 0));
    }

    public static List<HistoryEntry> history(JsonObject response) {
        return array(result(response)).stream().map(ElectrumResult::object)
                .map(entry -> new HistoryEntry(txid(entry), index(entry, "height", -1))).toList();
    }

    public static List<Unspent> unspent(JsonObject response) {
        return array(result(response)).stream().map(ElectrumResult::object)
                .map(entry -> new Unspent(txid(entry), index(entry, "tx_pos", 0),
                        nonNegative(entry, "value"), index(entry, "height", -1))).toList();
    }

    public static String text(JsonObject response) {
        if (result(response) instanceof String text && !text.isEmpty()) return text;
        throw invalid();
    }

    public static void subscription(JsonObject response) {
        Object result = result(response);
        if (result != null && !(result instanceof String)) throw invalid();
    }

    private static Object result(JsonObject response) {
        if (response == null || !response.containsKey("result") || response.getValue("error") != null) throw invalid();
        return response.getValue("result");
    }

    private static JsonObject object(Object value) {
        if (value instanceof JsonObject object) return object;
        throw invalid();
    }

    private static JsonArray array(Object value) {
        if (value instanceof JsonArray array) return array;
        throw invalid();
    }

    private static long integer(Object value) {
        return switch (value) {
            case Byte number -> number.longValue();
            case Short number -> number.longValue();
            case Integer number -> number.longValue();
            case Long number -> number;
            case null, default -> throw invalid();
        };
    }

    private static long nonNegative(JsonObject object, String key) {
        long value = integer(object.getValue(key));
        if (value < 0) throw invalid();
        return value;
    }

    private static int index(JsonObject object, String key, int minimum) {
        long value = integer(object.getValue(key));
        if (value < minimum || value > Integer.MAX_VALUE) throw invalid();
        return (int) value;
    }

    private static String txid(JsonObject object) {
        if (object.getValue("tx_hash") instanceof String hash && TXID.matcher(hash).matches()) return hash;
        throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Electrum response");
    }
}