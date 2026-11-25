package pe.facturacion.api.modules.portal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.facturacion.api.modules.admin.security.AdminJwt;

/**
 * JWT del portal: emisor y audiencia propios, y separación del JWT del panel en los dos sentidos. Las claves son de
 * prueba, sin valor real.
 */
class PortalJwtTest {

    static final byte[] PORTAL_SECRET = "clave-de-prueba-del-portal-0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    static final byte[] ADMIN_SECRET = "clave-de-prueba-del-panel-0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    static final Instant NOW = Instant.parse("2026-11-24T15:00:00Z");

    final PortalJwt portal = new PortalJwt(PORTAL_SECRET);

    @Test
    void emite_y_verifica_el_token_de_acceso_sin_datos_personales() throws Exception {
        String token = portal.issueAccess(42, 3, NOW);

        PortalJwt.AccessClaims c = portal.verifyAccess(token, NOW.plusSeconds(60)).orElseThrow();
        assertThat(c.userId()).isEqualTo(42);
        assertThat(c.sessionVersion()).isEqualTo(3);
        assertThat(c.expiresAt()).isEqualTo(NOW.plus(PortalJwt.ACCESS_TTL));

        JWTClaimsSet claims = SignedJWT.parse(token).getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("api-sunat-portal");
        assertThat(claims.getAudience()).containsExactly("portal");
        assertThat(claims.getStringListClaim("amr")).containsExactly("pwd", "mfa");
        // Sin correo, nombre, empresas ni rol: solo lo mínimo para validar la sesión
        assertThat(claims.getClaims().keySet()).containsExactlyInAnyOrder("iss", "aud", "sub", "jti", "iat", "exp", "typ", "sv", "amr");
    }

    @Test
    void el_token_vence_a_los_30_minutos_y_el_ticket_mfa_a_los_5() {
        String access = portal.issueAccess(1, 0, NOW);
        assertThat(portal.verifyAccess(access, NOW.plus(Duration.ofMinutes(29)))).isPresent();
        assertThat(portal.verifyAccess(access, NOW.plus(Duration.ofMinutes(30)))).isEmpty();

        String ticket = portal.issueMfaTicket(1, PortalJwt.STAGE_VERIFY, 0, NOW);
        assertThat(portal.verifyMfaTicket(ticket, NOW.plus(Duration.ofMinutes(4)))).isPresent();
        assertThat(portal.verifyMfaTicket(ticket, NOW.plus(Duration.ofMinutes(5)))).isEmpty();
    }

    @Test
    void el_ticket_mfa_nunca_sirve_como_token_de_acceso_ni_al_reves() {
        String ticket = portal.issueMfaTicket(7, PortalJwt.STAGE_SETUP, 1, NOW);
        assertThat(portal.verifyAccess(ticket, NOW)).isEmpty();
        assertThat(portal.verifyMfaTicket(ticket, NOW).orElseThrow().stage()).isEqualTo("setup");
        assertThat(portal.verifyMfaTicket(portal.issueAccess(7, 1, NOW), NOW)).isEmpty();
    }

    @Test
    void firma_ajena_manipulacion_y_algoritmo_none_se_rechazan() throws Exception {
        String token = portal.issueAccess(1, 0, NOW);
        assertThat(new PortalJwt("otra-clave-de-prueba-del-portal-0123456789".getBytes(StandardCharsets.UTF_8)).verifyAccess(token, NOW)).isEmpty();
        String[] parts = token.split("\\.");
        assertThat(portal.verifyAccess(parts[0] + "." + parts[1] + "x." + parts[2], NOW)).isEmpty();
        assertThat(portal.verifyAccess(token + "x", NOW)).isEmpty();
        assertThat(portal.verifyAccess(null, NOW)).isEmpty();
        assertThat(portal.verifyAccess("   ", NOW)).isEmpty();
        assertThat(portal.verifyAccess("a".repeat(5000), NOW)).isEmpty();
        PlainJWT none = new PlainJWT(claims(PortalJwt.ISSUER, List.of(PortalJwt.AUDIENCE), "access", NOW));
        assertThat(portal.verifyAccess(none.serialize(), NOW)).isEmpty();
    }

    @Test
    void sin_la_audiencia_del_portal_o_con_otro_emisor_se_rechaza_aunque_la_firma_sea_valida() throws Exception {
        // Firmados con la clave del portal pero con otro emisor / audiencia: nunca valen
        assertThat(portal.verifyAccess(forge(PORTAL_SECRET, "api-sunat-admin", List.of("portal"), "access"), NOW)).isEmpty();
        assertThat(portal.verifyAccess(forge(PORTAL_SECRET, PortalJwt.ISSUER, List.of("admin"), "access"), NOW)).isEmpty();
        assertThat(portal.verifyAccess(forge(PORTAL_SECRET, PortalJwt.ISSUER, List.of(), "access"), NOW)).isEmpty();
        assertThat(portal.verifyAccess(forge(PORTAL_SECRET, PortalJwt.ISSUER, null, "access"), NOW)).isEmpty();
        // Control: con emisor y audiencia correctos sí
        assertThat(portal.verifyAccess(forge(PORTAL_SECRET, PortalJwt.ISSUER, List.of("portal"), "access"), NOW)).isPresent();
    }

    @Test
    void un_token_del_portal_no_sirve_en_el_panel_y_uno_del_panel_no_sirve_en_el_portal() {
        AdminJwt admin = new AdminJwt(ADMIN_SECRET);
        String portalToken = portal.issueAccess(1, 0, NOW);
        String adminToken = admin.issueAccess(1, "superadmin", 0, NOW);
        String portalTicket = portal.issueMfaTicket(1, PortalJwt.STAGE_VERIFY, 0, NOW);
        String adminTicket = admin.issueMfaTicket(1, AdminJwt.STAGE_VERIFY, 0, NOW);

        // Con sus claves distintas (lo normal)
        assertThat(admin.verifyAccess(portalToken, NOW)).isEmpty();
        assertThat(admin.verifyMfaTicket(portalTicket, NOW)).isEmpty();
        assertThat(portal.verifyAccess(adminToken, NOW)).isEmpty();
        assertThat(portal.verifyMfaTicket(adminTicket, NOW)).isEmpty();
        assertThat(portal.subjectOfAccessIgnoringExpiry(adminToken)).isEmpty();
        assertThat(admin.subjectOfAccessIgnoringExpiry(portalToken)).isEmpty();
    }

    @Test
    void aunque_alguien_configurara_la_misma_clave_en_los_dos_el_emisor_y_la_audiencia_los_separan() {
        AdminJwt admin = new AdminJwt(PORTAL_SECRET);
        String portalToken = portal.issueAccess(1, 0, NOW);
        String adminToken = admin.issueAccess(1, "superadmin", 0, NOW);

        assertThat(admin.verifyAccess(portalToken, NOW)).isEmpty();
        assertThat(portal.verifyAccess(adminToken, NOW)).isEmpty();
        assertThat(admin.verifyMfaTicket(portal.issueMfaTicket(1, PortalJwt.STAGE_VERIFY, 0, NOW), NOW)).isEmpty();
        assertThat(portal.verifyMfaTicket(admin.issueMfaTicket(1, AdminJwt.STAGE_VERIFY, 0, NOW), NOW)).isEmpty();
    }

    @Test
    void la_clave_debe_tener_al_menos_32_bytes() {
        assertThatThrownBy(() -> new PortalJwt("corta".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PortalJwt(null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static JWTClaimsSet claims(String iss, List<String> aud, String typ, Instant now) {
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().issuer(iss).subject("1").claim("typ", typ).claim("sv", 0)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(Duration.ofMinutes(30))));
        if (aud != null) {
            b.audience(aud);
        }
        return b.build();
    }

    private static String forge(byte[] secret, String iss, List<String> aud, String typ) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims(iss, aud, typ, NOW));
        jwt.sign(new MACSigner(secret));
        return jwt.serialize();
    }
}
