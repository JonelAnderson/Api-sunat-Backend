package pe.facturacion.api.modules.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.facturacion.api.shared.json.PhpJson;

/** JWT del panel: emisión, verificación, vencimiento, manipulación y separación acceso / ticket MFA. */
class AdminJwtTest {

    /** Clave de prueba sin valor real (≥ 32 bytes). */
    static final byte[] KEY = "clave-de-prueba-del-panel-0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    static final Instant NOW = Instant.parse("2026-11-23T15:04:05Z");

    final AdminJwt jwt = new AdminJwt(KEY);

    @Test
    void emite_y_verifica_el_token_de_acceso() throws Exception {
        String token = jwt.issueAccess(7, "operador", 3, NOW);

        AdminJwt.AccessClaims c = jwt.verifyAccess(token, NOW.plusSeconds(60)).orElseThrow();
        assertThat(c.userId()).isEqualTo(7);
        assertThat(c.role()).isEqualTo("operador");
        assertThat(c.sessionVersion()).isEqualTo(3);
        assertThat(c.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));

        SignedJWT parsed = SignedJWT.parse(token);
        assertThat(parsed.getHeader().getAlgorithm().getName()).isEqualTo("HS256");
        var claims = parsed.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("api-sunat-admin");
        assertThat(claims.getSubject()).isEqualTo("7");
        assertThat(claims.getStringListClaim("amr")).containsExactly("pwd", "mfa");
        // Sin datos personales: solo estos claims
        assertThat(claims.getClaims().keySet()).containsExactlyInAnyOrder("iss", "sub", "jti", "iat", "exp", "typ", "role", "sv", "amr");
    }

    @Test
    void vence_a_los_30_minutos() {
        String token = jwt.issueAccess(7, "lectura", 0, NOW);
        assertThat(jwt.verifyAccess(token, NOW.plus(Duration.ofMinutes(30)).minusSeconds(1))).isPresent();
        assertThat(jwt.verifyAccess(token, NOW.plus(Duration.ofMinutes(30)))).isEmpty();
        // Para el refresh se acepta vencido, pero no con otra firma
        assertThat(jwt.subjectOfAccessIgnoringExpiry(token)).contains(7L);
    }

    @Test
    void rechaza_firma_o_contenido_manipulado_y_otra_clave() {
        String token = jwt.issueAccess(7, "lectura", 0, NOW);
        String[] parts = token.split("\\.");

        // Cambiar el rol en el payload sin volver a firmar
        Map<String, Object> payload = PhpJson.decodeObject(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        payload.put("role", "superadmin");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(PhpJson.encodeUnescaped(payload).getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertThat(jwt.verifyAccess(forged, NOW)).isEmpty();

        // Firma alterada (el primer carácter: el último de un base64url de 32 bytes lleva 2 bits de relleno y cambiar A por B
        // podía dejar la misma firma, lo que hacía la prueba intermitente según el jti aleatorio)
        char first = parts[2].charAt(0);
        String badSig = parts[0] + "." + parts[1] + "." + (first == 'A' ? 'B' : 'A') + parts[2].substring(1);
        assertThat(jwt.verifyAccess(badSig, NOW)).isEmpty();

        // Firmado con otra clave
        AdminJwt other = new AdminJwt("otra-clave-de-prueba-del-panel-9876543210".getBytes(StandardCharsets.UTF_8));
        assertThat(jwt.verifyAccess(other.issueAccess(7, "superadmin", 0, NOW), NOW)).isEmpty();
        assertThat(jwt.subjectOfAccessIgnoringExpiry(other.issueAccess(7, "superadmin", 0, NOW))).isEmpty();

        // alg=none
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(jwt.verifyAccess(header + "." + parts[1] + ".", NOW)).isEmpty();

        assertThat(jwt.verifyAccess("no-es-un-jwt", NOW)).isEmpty();
        assertThat(jwt.verifyAccess(null, NOW)).isEmpty();
        assertThat(jwt.verifyAccess("", NOW)).isEmpty();
    }

    @Test
    void el_ticket_mfa_nunca_sirve_como_acceso_ni_al_reves() {
        String ticket = jwt.issueMfaTicket(9, AdminJwt.STAGE_SETUP, 2, NOW);
        AdminJwt.MfaClaims c = jwt.verifyMfaTicket(ticket, NOW.plusSeconds(10)).orElseThrow();
        assertThat(c.userId()).isEqualTo(9);
        assertThat(c.stage()).isEqualTo("setup");
        assertThat(c.sessionVersion()).isEqualTo(2);

        assertThat(jwt.verifyAccess(ticket, NOW)).isEmpty();
        assertThat(jwt.subjectOfAccessIgnoringExpiry(ticket)).isEmpty();
        assertThat(jwt.verifyMfaTicket(jwt.issueAccess(9, "superadmin", 2, NOW), NOW)).isEmpty();
    }

    @Test
    void el_ticket_mfa_vence_a_los_5_minutos() {
        String ticket = jwt.issueMfaTicket(9, AdminJwt.STAGE_VERIFY, 0, NOW);
        assertThat(jwt.verifyMfaTicket(ticket, NOW.plus(Duration.ofMinutes(5)).minusSeconds(1))).isPresent();
        assertThat(jwt.verifyMfaTicket(ticket, NOW.plus(Duration.ofMinutes(5)))).isEmpty();
    }

    @Test
    void exige_una_clave_de_al_menos_32_bytes() {
        assertThatThrownBy(() -> new AdminJwt("corta".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdminJwt(new byte[31])).isInstanceOf(IllegalArgumentException.class);
        new AdminJwt(new byte[32]);
        assertThat(List.of(AdminJwt.STAGE_VERIFY, AdminJwt.STAGE_SETUP)).containsExactly("verify", "setup");
    }
}
