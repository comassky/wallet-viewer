package com.comassky.wallet.electrum;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ElectrumResultTest {
    private static JsonObject response(Object value) {
        return new JsonObject().put("result", value);
    }

    @Test
    void balanceRequiresBothExactIntegerAmounts() {
        assertEquals(new ElectrumResult.Balance(42, -8), ElectrumResult.balance(
                response(new JsonObject().put("confirmed", 42L).put("unconfirmed", -8))));
        for (Object invalid : List.of("private-response", 1.5, 1.0, new JsonObject())) {
            var failure = assertThrows(IllegalArgumentException.class, () -> ElectrumResult.balance(
                    response(new JsonObject().put("confirmed", invalid).put("unconfirmed", 0))));
            assertEquals("Invalid Electrum response", failure.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> ElectrumResult.balance(response(new JsonObject())));
        assertThrows(IllegalArgumentException.class, () -> ElectrumResult.balance(new JsonObject()));
        assertThrows(IllegalArgumentException.class, () -> ElectrumResult.balance(response(null)));
    }

    @Test
    void historyAndUtxosValidateEveryElementAndRemainImmutable() {
        String hash = "ab".repeat(32);
        JsonObject entry = new JsonObject().put("tx_hash", hash).put("height", -1);
        var history = ElectrumResult.history(response(new JsonArray().add(entry)));
        assertEquals(List.of(new ElectrumResult.HistoryEntry(hash, -1)), history);
        assertThrows(UnsupportedOperationException.class, () -> history.clear());
        assertTrue(ElectrumResult.history(response(new JsonArray())).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ElectrumResult.history(response(new JsonArray().add(entry).add("bad"))));
        assertThrows(IllegalArgumentException.class, () -> ElectrumResult.history(response(new JsonArray().add(entry.copy().put("tx_hash", "invalid")))));
        assertThrows(IllegalArgumentException.class, () -> ElectrumResult.history(response(new JsonArray().add(entry.copy().put("height", Long.MAX_VALUE)))));
        JsonObject unspent = entry.copy().put("tx_pos", 0).put("value", 42L);
        assertEquals(42L, ElectrumResult.unspent(response(new JsonArray().add(unspent))).getFirst().value());
        assertThrows(IllegalArgumentException.class, () -> ElectrumResult.unspent(response(new JsonArray().add(unspent.copy().put("value", -1)))));
        unspent.remove("value");
        assertThrows(IllegalArgumentException.class, () -> ElectrumResult.unspent(response(new JsonArray().add(unspent))));
    }

    @Test
    void tipTextAndSubscriptionRejectMalformedEnvelopes() {
        assertEquals(0, ElectrumResult.tip(response(new JsonObject().put("height", 0))).height());
        assertThrows(IllegalArgumentException.class, () -> ElectrumResult.tip(response(new JsonObject().put("height", -1))));
        assertEquals("00", ElectrumResult.text(response("00")));
        assertThrows(IllegalArgumentException.class, () -> ElectrumResult.text(response("")));
        assertThrows(IllegalArgumentException.class, () -> ElectrumResult.text(response("00").put("error", "secret")));
        assertDoesNotThrow(() -> ElectrumResult.subscription(response(null)));
        assertDoesNotThrow(() -> ElectrumResult.subscription(response("status")));
        assertThrows(IllegalArgumentException.class, () -> ElectrumResult.subscription(response(42)));
        assertThrows(IllegalArgumentException.class, () -> ElectrumResult.subscription(new JsonObject()));
    }
}