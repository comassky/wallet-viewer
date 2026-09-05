package com.comassky.wallet.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Local transport state; unknown metadata is explicitly serialized as null. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ElectrumServerDto(String host, int port, boolean tls, boolean connected,
                                String serverVersion, String protocolVersion) { }