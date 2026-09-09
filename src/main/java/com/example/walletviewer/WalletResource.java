package com.example.walletviewer;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/wallet")
@Produces(MediaType.APPLICATION_JSON)
public class WalletResource {

    @Inject
    WalletService service;

    @GET
    public Uni<WalletSnapshot> snapshot() {
        return service.snapshot();
    }

    @GET
    @Path("/balance")
    public Uni<BalanceDto> balance() {
        return service.snapshot().map(WalletSnapshot::balance);
    }

    @GET
    @Path("/transactions")
    public Uni<List<TransactionDto>> transactions() {
        return service.snapshot().map(WalletSnapshot::transactions);
    }

    @GET
    @Path("/utxos")
    public Uni<List<UtxoDto>> utxos() {
        return service.snapshot().map(WalletSnapshot::utxos);
    }

    @GET
    @Path("/receive")
    public Uni<ReceiveAddressDto> receive() {
        return service.snapshot().map(WalletSnapshot::receiveAddress);
    }

    @GET
    @Path("/receive/{index}")
    public ReceiveAddressDto receiveAt(@PathParam("index") int index) {
        validateIndex(index);
        AddressInfo a = service.derive(0, index);
        return new ReceiveAddressDto(a.index, a.address, a.path);
    }

    @GET
    @Path("/receive/qr")
    @Produces("image/png")
    public Uni<byte[]> receiveQr() {
        return service.snapshot().map(s -> QrGenerator.png(s.receiveAddress().address(), 320));
    }

    @GET
    @Path("/receive/{index}/qr")
    @Produces("image/png")
    public byte[] receiveQrAt(@PathParam("index") int index) {
        validateIndex(index);
        return QrGenerator.png(service.derive(0, index).address, 320);
    }

    private static void validateIndex(int index) {
        if (index < 0) {
            throw new BadRequestException("Address index must be non-negative");
        }
    }
}
