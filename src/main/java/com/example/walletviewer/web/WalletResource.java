package com.example.walletviewer.web;

import com.example.walletviewer.electrum.ElectrumClient;
import com.example.walletviewer.model.AddressInfo;
import com.example.walletviewer.model.BalanceDto;
import com.example.walletviewer.model.ElectrumServerDto;
import com.example.walletviewer.model.PriceRatesDto;
import com.example.walletviewer.model.ReceiveAddressDto;
import com.example.walletviewer.model.TransactionDetailsDto;
import com.example.walletviewer.model.TransactionDto;
import com.example.walletviewer.model.UtxoDto;
import com.example.walletviewer.model.WalletSnapshot;
import com.example.walletviewer.service.PriceService;
import com.example.walletviewer.service.TransactionDetailsService;
import com.example.walletviewer.service.WalletLiveService;
import com.example.walletviewer.service.WalletService;
import com.example.walletviewer.util.QrGenerator;
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

    @Inject
    WalletLiveService live;

    @Inject
    PriceService prices;

    @Inject
    TransactionDetailsService transactionDetails;

    @Inject
    ElectrumClient electrum;

    @GET
    @Path("/server")
    public ElectrumServerDto server() {
        return electrum.serverInfo();
    }

    @GET
    @Path("/prices")
    public Uni<PriceRatesDto> prices() {
        return prices.rates();
    }

    @GET
    public Uni<WalletSnapshot> snapshot() {
        return live.snapshot();
    }

    @GET
    @Path("/balance")
    public Uni<BalanceDto> balance() {
        return live.snapshot().map(WalletSnapshot::balance);
    }

    @GET
    @Path("/transactions")
    public Uni<List<TransactionDto>> transactions() {
        return live.snapshot().map(WalletSnapshot::transactions);
    }

    @GET
    @Path("/transactions/{txid}")
    public Uni<TransactionDetailsDto> transactionDetails(@PathParam("txid") String txid) {
        return transactionDetails.details(txid);
    }

    @GET
    @Path("/utxos")
    public Uni<List<UtxoDto>> utxos() {
        return live.snapshot().map(WalletSnapshot::utxos);
    }

    @GET
    @Path("/receive")
    public Uni<ReceiveAddressDto> receive() {
        return live.snapshot().map(WalletSnapshot::receiveAddress);
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
        return live.snapshot().map(s -> QrGenerator.png(s.receiveAddress().address(), 320));
    }

    @GET
    @Path("/receive/{index}/qr")
    @Produces("image/png")
    public byte[] receiveQrAt(@PathParam("index") int index) {
        return QrGenerator.png(receiveAt(index).address(), 320);
    }

    private static void validateIndex(int index) {
        if (index < 0) {
            throw new BadRequestException("Address index must be non-negative");
        }
    }
}
