package com.fidegresa.webhook;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Resuelve un dominio y rechaza los que apunten, aunque sea en una de sus direcciones, a una red que no es pública. */
@Component
class HostResolver {
    InetAddress[] resolvePublic(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (InetAddress address : addresses) {
            if (!PublicAddress.isPublic(address)) throw new BlockedAddressException();
        }
        return addresses;
    }

    static final class BlockedAddressException extends UnknownHostException {
        BlockedAddressException() {
            super("Dirección no pública");
        }
    }
}
