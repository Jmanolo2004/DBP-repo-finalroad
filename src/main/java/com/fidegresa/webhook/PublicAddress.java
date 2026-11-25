package com.fidegresa.webhook;

import java.net.Inet4Address;
import java.net.InetAddress;

/** Dice si una dirección IP es pública. Evita que un webhook alcance servidores internos o de metadatos de la nube. */
final class PublicAddress {
    private PublicAddress() {
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        return address instanceof Inet4Address ? isPublicV4(bytes) : isPublicV6(bytes);
    }

    private static boolean isPublicV4(byte[] bytes) {
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        int third = bytes[2] & 0xFF;
        if (first == 0 || first >= 240) return false;                                   // reservadas y difusión
        if (first == 100 && second >= 64 && second <= 127) return false;                // NAT del operador
        if (first == 192 && second == 0 && (third == 0 || third == 2)) return false;    // protocolo y documentación
        if (first == 192 && second == 88 && third == 99) return false;                  // relés 6to4
        if (first == 198 && (second == 18 || second == 19)) return false;               // pruebas de red
        if (first == 198 && second == 51 && third == 100) return false;                 // documentación
        return !(first == 203 && second == 0 && third == 113);                          // documentación
    }

    /** Solo unicast global (2000::/3), sin los rangos que pueden contener una IPv4 interna. */
    private static boolean isPublicV6(byte[] bytes) {
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        int third = bytes[2] & 0xFF;
        int fourth = bytes[3] & 0xFF;
        if ((first & 0xE0) != 0x20) return false;
        if (first == 0x20 && second == 0x02) return false;                              // 6to4
        if (first == 0x20 && second == 0x01 && third < 0x02) return false;              // Teredo y asignaciones especiales
        return !(first == 0x20 && second == 0x01 && third == 0x0D && fourth == 0xB8);   // documentación
    }
}
