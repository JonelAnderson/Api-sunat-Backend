package pe.facturacion.api.modules.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Lista de IP permitidas: sueltas y CIDR, IPv4 e IPv6, y falla cerrada. */
class IpAllowListTest {

    @Test
    void ip_suelta_y_cidr_ipv4() {
        IpAllowList list = new IpAllowList(List.of("10.0.0.5", "172.16.0.0/12", "192.168.1.128/25"));
        assertThat(list.allows("10.0.0.5")).isTrue();
        assertThat(list.allows("10.0.0.6")).isFalse();
        assertThat(list.allows("172.16.0.1")).isTrue();
        assertThat(list.allows("172.31.255.255")).isTrue();
        assertThat(list.allows("172.32.0.1")).isFalse();
        assertThat(list.allows("192.168.1.130")).isTrue();
        assertThat(list.allows("192.168.1.10")).isFalse();
    }

    @Test
    void ipv6_y_ipv4_mapeada() {
        IpAllowList list = new IpAllowList(List.of("2001:db8::/32", "10.0.0.5"));
        assertThat(list.allows("2001:db8:1::7")).isTrue();
        assertThat(list.allows("2001:db9::1")).isFalse();
        assertThat(list.allows("::ffff:10.0.0.5")).isTrue();
        assertThat(list.allows("::1")).isFalse();
    }

    @Test
    void lista_vacia_o_invalida_no_permite_nada_y_nunca_resuelve_nombres() {
        assertThat(new IpAllowList(List.of()).allows("10.0.0.5")).isFalse();
        IpAllowList bad = new IpAllowList(List.of("localhost", "abc", "10.0.0.0/33", "10.0.0.0/x", "0.0.0.0/0x", ""));
        assertThat(bad.allows("127.0.0.1")).isFalse();
        assertThat(bad.allows("10.0.0.1")).isFalse();
        assertThat(bad.allows(null)).isFalse();
        assertThat(bad.allows("localhost")).isFalse();
        assertThat(bad.allows("abc")).isFalse();
    }

    @Test
    void cero_barra_cero_es_explicito() {
        assertThat(new IpAllowList(List.of("0.0.0.0/0")).allows("203.0.113.7")).isTrue();
    }
}
