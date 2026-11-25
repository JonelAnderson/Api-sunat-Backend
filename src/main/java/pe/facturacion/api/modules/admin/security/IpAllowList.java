package pe.facturacion.api.modules.admin.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Lista de IP permitidas: direcciones sueltas ({@code 10.0.0.5}) o redes CIDR ({@code 10.0.0.0/24}, IPv4 e IPv6).
 * Una lista vacía no permite nada (falla cerrada). Solo recibe direcciones numéricas: nunca resuelve nombres de host.
 */
public final class IpAllowList {

    private final List<Entry> entries;

    public IpAllowList(List<String> specs) {
        this.entries = specs.stream().map(IpAllowList::parse).filter(e -> e != null).toList();
    }

    public boolean allows(String ip) {
        byte[] addr = bytes(ip);
        if (addr == null) {
            return false;
        }
        for (Entry e : entries) {
            if (e.matches(addr)) {
                return true;
            }
        }
        return false;
    }

    private record Entry(byte[] network, int prefix) {
        boolean matches(byte[] addr) {
            if (addr.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (addr[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = 0xFF << (8 - rest) & 0xFF;
            return (addr[full] & mask) == (network[full] & mask);
        }
    }

    private static Entry parse(String spec) {
        String host = spec;
        Integer prefix = null;
        int slash = spec.indexOf('/');
        if (slash >= 0) {
            host = spec.substring(0, slash);
            try {
                prefix = Integer.parseInt(spec.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        byte[] addr = bytes(host.trim());
        if (addr == null) {
            return null;
        }
        int max = addr.length * 8;
        if (prefix == null) {
            prefix = max;
        }
        if (prefix < 0 || prefix > max) {
            return null;
        }
        return new Entry(addr, prefix);
    }

    /** Bytes de una IP numérica (v4 o v6); null si no lo es. Una v4 mapeada en v6 ({@code ::ffff:a.b.c.d}) cuenta como v4. */
    private static byte[] bytes(String ip) {
        // Solo literales: IPv4 con 4 grupos o IPv6 (con ':'). Un texto como "abc" nunca llega a InetAddress (haría DNS).
        boolean v4 = ip != null && ip.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean v6 = ip != null && ip.contains(":") && ip.matches("[0-9a-fA-F:.]+");
        if (!v4 && !v6) {
            return null;
        }
        try {
            return InetAddress.getByName(ip).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
