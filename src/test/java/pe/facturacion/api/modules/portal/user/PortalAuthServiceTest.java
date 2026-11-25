package pe.facturacion.api.modules.portal.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import pe.facturacion.api.config.AdminProperties;
import pe.facturacion.api.config.PortalProperties;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.admin.security.RecoveryCodes;
import pe.facturacion.api.modules.admin.security.Totp;
import pe.facturacion.api.modules.audit.AuditLogService;
import pe.facturacion.api.modules.portal.security.PortalJwt;
import pe.facturacion.api.modules.portal.security.PortalMessages;
import pe.facturacion.api.modules.portal.security.PortalPrincipal;
import pe.facturacion.api.modules.portal.security.PortalSessionCache;
import pe.facturacion.api.modules.portal.security.PortalTokens;
import pe.facturacion.api.shared.crypto.SecretCipher;
import pe.facturacion.api.shared.time.Clock;

/**
 * Activación, login en dos pasos, bloqueo, paso de seguridad y cuenta propia del portal, con la base simulada. La
 * integración con PostgreSQL y Redis reales está en {@code PortalClientesIT}. Claves y contraseñas de prueba, sin valor
 * real.
 */
class PortalAuthServiceTest {

    static final String PASSWORD = "Prueba#2026";
    static final String PORTAL_SECRET = "clave-de-prueba-del-portal-0123456789abcdef";
    static final Instant NOW = Instant.parse("2026-11-24T15:00:00Z");
    static final String GENERIC_INVITATION = PortalMessages.INVITATION_INVALID;

    PortalUserStore store;
    PortalSessionCache sessions;
    PortalTenantQueries tenantQueries;
    AuditLogService audit;
    StringRedisTemplate redis;
    ValueOperations<String, String> redisOps;
    SecretCipher cipher;
    PortalAuthService auth;
    PortalJwt jwt;
    final MockHttpServletRequest request = new MockHttpServletRequest();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        Clock.useFixed(NOW);
        store = mock(PortalUserStore.class);
        sessions = mock(PortalSessionCache.class);
        tenantQueries = mock(PortalTenantQueries.class);
        audit = mock(AuditLogService.class);
        redis = mock(StringRedisTemplate.class);
        redisOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(redisOps);
        when(redisOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        cipher = new SecretCipher(Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        PortalTokens tokens = new PortalTokens(new PortalProperties(PORTAL_SECRET, null),
                new AdminProperties("clave-de-prueba-del-panel-0123456789abcdef", null, null, null));
        auth = new PortalAuthService(store, tokens, sessions, tenantQueries, cipher, audit, redis);
        jwt = new PortalJwt(PORTAL_SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        Clock.useSystem();
    }

    // ------------------------------------------------------------------ activación

    @Test
    void activar_con_una_invitacion_valida_fija_la_contrasena_y_no_devuelve_tokens() {
        PortalUser invited = user(7, null, true, null, null);
        when(store.consumeInvitation(eq(PortalAuthService.sha256("token-de-prueba")), any())).thenReturn(Optional.of(7L));
        when(store.findById(7)).thenReturn(Optional.of(invited));

        auth.activate("token-de-prueba", PASSWORD, PASSWORD, request);

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(store).updatePassword(eq(7L), hash.capture(), any());
        assertThat(hash.getValue()).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(auth.passwordMatches(PASSWORD, hash.getValue())).isTrue();
        verify(sessions).invalidate(7);
        // No hay refresh token ni sesión nueva: la persona entra después por el login
        verify(store, never()).insertRefreshToken(anyLong(), anyString(), any(), any());
        verify(audit).registrarPortal(any(), eq("portal"), eq("ana@cliente.pe"), eq("portal.activacion"), any(), any(), any(), anyString(), any());
    }

    @Test
    void el_token_se_busca_por_su_sha256_nunca_en_claro() {
        when(store.consumeInvitation(anyString(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> auth.activate("token-de-prueba", PASSWORD, PASSWORD, request)).isInstanceOf(AdminException.class);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(store).consumeInvitation(hash.capture(), any());
        assertThat(hash.getValue()).matches("[0-9a-f]{64}").doesNotContain("token-de-prueba");
    }

    @Test
    void token_invalido_usado_vencido_o_reemplazado_responde_siempre_el_mismo_mensaje_generico() {
        // Invalido, usado, vencido o reemplazado por una invitación nueva: el UPDATE atómico no devuelve nada
        when(store.consumeInvitation(anyString(), any())).thenReturn(Optional.empty());
        assertFails(() -> auth.activate("desconocido", PASSWORD, PASSWORD, request), GENERIC_INVITATION);
        assertFails(() -> auth.activate("usado-o-vencido", PASSWORD, PASSWORD, request), GENERIC_INVITATION);

        // Válido pero de una persona deshabilitada: el mismo mensaje
        when(store.consumeInvitation(eq(PortalAuthService.sha256("de-deshabilitado")), any())).thenReturn(Optional.of(9L));
        when(store.findById(9)).thenReturn(Optional.of(user(9, null, false, null, null)));
        assertFails(() -> auth.activate("de-deshabilitado", PASSWORD, PASSWORD, request), GENERIC_INVITATION);
        // Válido de una persona que ya no existe
        when(store.consumeInvitation(eq(PortalAuthService.sha256("de-borrado")), any())).thenReturn(Optional.of(10L));
        when(store.findById(10)).thenReturn(Optional.empty());
        assertFails(() -> auth.activate("de-borrado", PASSWORD, PASSWORD, request), GENERIC_INVITATION);

        // Sin token o absurdo: igual, y ni se consulta la base
        for (String token : new String[] {null, "", "   ", "x".repeat(201)}) {
            assertFails(() -> auth.activate(token, PASSWORD, PASSWORD, request), GENERIC_INVITATION);
        }
        verify(store, never()).updatePassword(anyLong(), anyString(), any());
    }

    @Test
    void la_contrasena_se_valida_antes_de_gastar_la_invitacion_y_sus_errores_no_dicen_nada_del_token() {
        for (String[] bad : new String[][] {{"corta1A#", "distinta1A#"}, {"sinnumeros#AAA", "sinnumeros#AAA"}, {"SINMINUSCULAS1#", "SINMINUSCULAS1#"},
                {"Sin1especial", "Sin1especial"}, {"", ""}}) {
            assertThatThrownBy(() -> auth.activate("token-cualquiera", bad[0], bad[1], request)).isInstanceOf(AdminException.class)
                    .satisfies(e -> {
                        AdminException ex = (AdminException) e;
                        assertThat(ex.errors()).isNotEmpty();
                        assertThat(ex.messages()).doesNotContain(GENERIC_INVITATION);
                    });
        }
        // La confirmación debe coincidir
        assertThatThrownBy(() -> auth.activate("t", PASSWORD, PASSWORD + "x", request)).isInstanceOf(AdminException.class);
        // Un error de contraseña no gasta la invitación: la persona puede corregir y reintentar con el mismo enlace
        verify(store, never()).consumeInvitation(anyString(), any());
    }

    // ------------------------------------------------------------------ login

    @Test
    void correo_inexistente_y_contrasena_incorrecta_responden_lo_mismo() {
        when(store.findByEmail("nadie@cliente.pe")).thenReturn(Optional.empty());
        assertFails(() -> auth.login("nadie@cliente.pe", PASSWORD, request), PortalMessages.BAD_CREDENTIALS);

        PortalUser ana = activated(1, null);
        when(store.findByEmail("ana@cliente.pe")).thenReturn(Optional.of(ana));
        assertFails(() -> auth.login("ana@cliente.pe", "Otra#2026x", request), PortalMessages.BAD_CREDENTIALS);
        verify(store).registerFailure(eq(1L), eq(5), any(), any());
    }

    @Test
    void el_correo_se_busca_sin_distinguir_mayusculas_ni_espacios() {
        when(store.findByEmail("ana@cliente.pe")).thenReturn(Optional.of(activated(1, null)));
        Map<String, Object> data = auth.login("  ANA@Cliente.pe ", PASSWORD, request);
        assertThat(data).containsEntry("mfaSetupRequired", true);
    }

    @Test
    void una_invitacion_sin_activar_no_puede_iniciar_sesion_y_no_lo_dice() {
        PortalUser pending = user(2, null, true, null, null);
        when(store.findByEmail("ana@cliente.pe")).thenReturn(Optional.of(pending));
        assertFails(() -> auth.login("ana@cliente.pe", PASSWORD, request), PortalMessages.BAD_CREDENTIALS);
    }

    @Test
    void cuenta_deshabilitada_con_la_contrasena_correcta_lo_dice_solo_a_quien_acierta() {
        PortalUser off = user(3, auth.hashPassword(PASSWORD), false, null, null);
        when(store.findByEmail("ana@cliente.pe")).thenReturn(Optional.of(off));
        assertFails(() -> auth.login("ana@cliente.pe", PASSWORD, request), PortalMessages.INACTIVE);
        assertFails(() -> auth.login("ana@cliente.pe", "Otra#2026x", request), PortalMessages.BAD_CREDENTIALS);
    }

    @Test
    void el_primer_paso_entrega_un_ticket_de_configuracion_o_de_verificacion_segun_el_mfa() throws Exception {
        when(store.findByEmail("ana@cliente.pe")).thenReturn(Optional.of(activated(1, null)));
        Map<String, Object> setup = auth.login("ana@cliente.pe", PASSWORD, request);
        assertThat(setup).containsOnlyKeys("mfaSetupRequired", "mfaTicket");
        assertThat(jwt.verifyMfaTicket((String) setup.get("mfaTicket"), Clock.now()).orElseThrow().stage()).isEqualTo("setup");

        String secret = Totp.newSecret();
        when(store.findByEmail("ana@cliente.pe")).thenReturn(Optional.of(withMfa(activated(1, null), secret)));
        Map<String, Object> verify = auth.login("ana@cliente.pe", PASSWORD, request);
        assertThat(verify).containsOnlyKeys("mfaRequired", "mfaTicket");
        assertThat(jwt.verifyMfaTicket((String) verify.get("mfaTicket"), Clock.now()).orElseThrow().stage()).isEqualTo("verify");
    }

    // ------------------------------------------------------------------ bloqueo

    @Test
    void el_quinto_fallo_bloquea_15_minutos_y_se_audita() {
        PortalUser ana = activated(1, null);
        when(store.findByEmail("ana@cliente.pe")).thenReturn(Optional.of(ana));
        when(store.registerFailure(eq(1L), eq(5), any(), any())).thenReturn(Clock.nowUtc().plusMinutes(15));
        // Un fallo que deja la cuenta bloqueada se audita; el mensaje de la contraseña sigue sin decir nada
        assertFails(() -> auth.login("ana@cliente.pe", "Otra#2026x", request), PortalMessages.BAD_CREDENTIALS);
        verify(audit).registrarPortal(any(), eq("portal"), eq("ana@cliente.pe"), eq("portal.cuenta.bloqueada"), any(), any(), any(), anyString(), any());
        ArgumentCaptor<LocalDateTime> until = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(store).registerFailure(eq(1L), eq(5), until.capture(), any());
        assertThat(until.getValue()).isEqualTo(Clock.nowUtc().plus(PortalAuthService.LOCK_DURATION));
    }

    @Test
    void bloqueada_solo_se_entera_quien_acierta_la_contrasena_y_el_contador_no_sigue_subiendo() {
        PortalUser locked = new PortalUser(1, "ana@cliente.pe", "Ana", auth.hashPassword(PASSWORD), true, 0, Clock.nowUtc().plusMinutes(10), 0, null,
                null, null, null);
        when(store.findByEmail("ana@cliente.pe")).thenReturn(Optional.of(locked));
        assertFails(() -> auth.login("ana@cliente.pe", PASSWORD, request), PortalMessages.LOCKED);
        assertFails(() -> auth.login("ana@cliente.pe", "Otra#2026x", request), PortalMessages.BAD_CREDENTIALS);
        verify(store, never()).registerFailure(anyLong(), anyInt(), any(), any());
        // Un bloqueo ya vencido deja entrar
        PortalUser expired = new PortalUser(1, "ana@cliente.pe", "Ana", auth.hashPassword(PASSWORD), true, 0, Clock.nowUtc().minusMinutes(1), 0, null,
                null, null, null);
        when(store.findByEmail("ana@cliente.pe")).thenReturn(Optional.of(expired));
        assertThat(auth.login("ana@cliente.pe", PASSWORD, request)).containsKey("mfaTicket");
    }

    @Test
    void un_codigo_incorrecto_cuenta_para_el_bloqueo_y_el_ticket_de_otra_etapa_no_sirve() {
        String secret = Totp.newSecret();
        PortalUser ana = withMfa(activated(1, null), secret);
        when(store.findById(1)).thenReturn(Optional.of(ana));
        String ticket = jwt.issueMfaTicket(1, PortalJwt.STAGE_VERIFY, 0, Clock.now());

        assertFails(() -> auth.completeMfa(ticket, "000000", null, request), PortalMessages.BAD_CODE);
        verify(store).registerFailure(eq(1L), eq(5), any(), any());

        // Con el quinto fallo, bloqueada
        when(store.registerFailure(eq(1L), eq(5), any(), any())).thenReturn(Clock.nowUtc().plusMinutes(15));
        assertFails(() -> auth.completeMfa(ticket, "000000", null, request), PortalMessages.LOCKED);

        // Un ticket de configuración no completa el paso de verificación y uno vencido tampoco
        assertFails(() -> auth.completeMfa(jwt.issueMfaTicket(1, PortalJwt.STAGE_SETUP, 0, Clock.now()), "000000", null, request),
                PortalMessages.TICKET_INVALID);
        assertFails(() -> auth.completeMfa("no-es-un-ticket", "000000", null, request), PortalMessages.TICKET_INVALID);
    }

    @Test
    void el_ticket_deja_de_valer_si_cambio_la_version_de_sesion() {
        PortalUser ana = new PortalUser(1, "ana@cliente.pe", "Ana", auth.hashPassword(PASSWORD), true, 0, null, 5, cipher.encrypt(Totp.newSecret()),
                Clock.nowUtc(), RecoveryCodes.toJson(List.of()), null);
        when(store.findById(1)).thenReturn(Optional.of(ana));
        String oldTicket = jwt.issueMfaTicket(1, PortalJwt.STAGE_VERIFY, 4, Clock.now());
        assertFails(() -> auth.completeMfa(oldTicket, "123456", null, request), PortalMessages.TICKET_INVALID);
    }

    @Test
    void el_login_completo_entrega_token_y_refresh_y_un_codigo_sirve_una_sola_vez() {
        String secret = Totp.newSecret();
        PortalUser ana = withMfa(activated(1, null), secret);
        when(store.findById(1)).thenReturn(Optional.of(ana));
        String ticket = jwt.issueMfaTicket(1, PortalJwt.STAGE_VERIFY, 0, Clock.now());
        String code = Totp.code(secret, Clock.now());

        Map<String, Object> done = auth.completeMfa(ticket, code, null, request);
        assertThat(done).containsKeys("token", "refreshToken", "refreshTokenExpiryTime", "recoveryCodesLeft");
        assertThat(jwt.verifyAccess((String) done.get("token"), Clock.now())).isPresent();
        verify(store).loginSucceeded(eq(1L), any());
        // El refresh se guarda solo como SHA-256
        ArgumentCaptor<String> refreshHash = ArgumentCaptor.forClass(String.class);
        verify(store).insertRefreshToken(eq(1L), refreshHash.capture(), any(), any());
        assertThat(refreshHash.getValue()).isEqualTo(PortalAuthService.sha256((String) done.get("refreshToken")));

        // El mismo paso del código ya fue usado: Redis dice que la marca existe
        when(redisOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertFails(() -> auth.completeMfa(ticket, code, null, request), PortalMessages.BAD_CODE);
    }

    // ------------------------------------------------------------------ paso de seguridad de las credenciales

    @Test
    void el_paso_de_seguridad_exige_contrasena_y_codigo_en_el_mismo_pedido() {
        String secret = Totp.newSecret();
        when(store.findById(1)).thenReturn(Optional.of(withMfa(activated(1, null), secret)));

        auth.stepUp(1, PASSWORD, Totp.code(secret, Clock.now()), request);
        verify(store, never()).registerFailure(anyLong(), anyInt(), any(), any());

        // Falta uno de los dos
        assertFails(() -> auth.stepUp(1, PASSWORD, null, request), "Ingresa tu contraseña y el código de tu aplicación autenticadora.");
        assertFails(() -> auth.stepUp(1, "", "123456", request), "Ingresa tu contraseña y el código de tu aplicación autenticadora.");
    }

    @Test
    void el_paso_de_seguridad_no_dice_cual_de_los_dos_fallo_y_cuenta_para_el_bloqueo() {
        String secret = Totp.newSecret();
        when(store.findById(1)).thenReturn(Optional.of(withMfa(activated(1, null), secret)));
        String good = Totp.code(secret, Clock.now());

        // Contraseña mal, código bien
        assertFails(() -> auth.stepUp(1, "Otra#2026x", good, request), PortalMessages.STEP_UP_FAILED);
        // Contraseña bien, código mal
        assertFails(() -> auth.stepUp(1, PASSWORD, "000000", request), PortalMessages.STEP_UP_FAILED);
        // Los dos mal
        assertFails(() -> auth.stepUp(1, "Otra#2026x", "000000", request), PortalMessages.STEP_UP_FAILED);
        verify(store, org.mockito.Mockito.times(3)).registerFailure(eq(1L), eq(5), any(), any());
        // Un código de recuperación no sirve aquí (solo TOTP)
        assertFails(() -> auth.stepUp(1, PASSWORD, "abcde-fghjk", request), PortalMessages.STEP_UP_FAILED);
    }

    @Test
    void sin_mfa_configurado_no_hay_paso_de_seguridad() {
        when(store.findById(1)).thenReturn(Optional.of(activated(1, null)));
        assertFails(() -> auth.stepUp(1, PASSWORD, "123456", request), PortalMessages.STEP_UP_FAILED);
    }

    @Test
    void el_paso_de_seguridad_fallido_que_bloquea_cierra_las_sesiones_y_uno_bloqueado_ni_se_evalua() {
        String secret = Totp.newSecret();
        PortalUser ana = withMfa(activated(1, null), secret);
        when(store.findById(1)).thenReturn(Optional.of(ana));
        when(store.registerFailure(eq(1L), eq(5), any(), any())).thenReturn(Clock.nowUtc().plusMinutes(15));

        assertFails(() -> auth.stepUp(1, "Otra#2026x", "000000", request), PortalMessages.LOCKED);
        verify(store).bumpSession(eq(1L), any());
        verify(sessions).invalidate(1);

        // Ya bloqueada: ni siquiera se mira la contraseña ni el código
        PortalUser locked = new PortalUser(1, "ana@cliente.pe", "Ana", auth.hashPassword(PASSWORD), true, 0, Clock.nowUtc().plusMinutes(5), 0,
                cipher.encrypt(secret), Clock.nowUtc(), null, null);
        when(store.findById(1)).thenReturn(Optional.of(locked));
        org.mockito.Mockito.clearInvocations(store, redisOps);
        assertFails(() -> auth.stepUp(1, PASSWORD, Totp.code(secret, Clock.now()), request), PortalMessages.LOCKED);
        verify(store, never()).registerFailure(anyLong(), anyInt(), any(), any());
        verify(redisOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    // ------------------------------------------------------------------ cuenta propia

    @Test
    void me_devuelve_solo_id_nombre_correo_y_empresas() {
        when(store.findById(1)).thenReturn(Optional.of(withMfa(activated(1, null), Totp.newSecret())));
        when(tenantQueries.tenantsOf(1)).thenReturn(List.of(Map.of("id", 5L, "ruc", "20123456789")));
        Map<String, Object> me = auth.me(new PortalPrincipal(1, "ana@cliente.pe", "Ana"));
        assertThat(me).containsOnlyKeys("id", "name", "email", "tenants");
        assertThat(me.toString()).doesNotContain("$2").doesNotContain("v1:");
    }

    @Test
    void cambiar_la_contrasena_cierra_todas_las_sesiones_y_pide_la_actual() {
        PortalUser ana = activated(1, null);
        when(store.findById(1)).thenReturn(Optional.of(ana));
        PortalPrincipal me = new PortalPrincipal(1, "ana@cliente.pe", "Ana");

        assertFails(() -> auth.changePassword(me, "Otra#2026x", "Nueva#2026ab", "Nueva#2026ab", request), "La contraseña actual no es correcta.");
        verify(store, never()).updatePassword(anyLong(), anyString(), any());

        auth.changePassword(me, PASSWORD, "Nueva#2026ab", "Nueva#2026ab", request);
        verify(store).updatePassword(eq(1L), anyString(), any());
        verify(store).bumpSession(eq(1L), any());
        verify(sessions).invalidate(1);
    }

    @Test
    void cerrar_sesion_sube_la_version_y_revoca_los_refresh_tokens() {
        auth.logout(new PortalPrincipal(1, "ana@cliente.pe", "Ana"), request);
        verify(store).bumpSession(eq(1L), any());
        verify(sessions).invalidate(1);
    }

    @Test
    void refresh_rota_el_token_y_exige_que_el_token_viejo_sea_de_la_misma_persona() {
        PortalUser ana = activated(1, null);
        when(store.consumeRefreshToken(eq(PortalAuthService.sha256("refresh-ok")), any())).thenReturn(Optional.of(1L));
        when(store.findById(1)).thenReturn(Optional.of(ana));
        Map<String, Object> pair = auth.refresh(jwt.issueAccess(1, 0, Clock.now().minusSeconds(3600)), "refresh-ok");
        assertThat(pair).containsKeys("token", "refreshToken");

        // El token de acceso viejo es de otra persona
        when(store.consumeRefreshToken(eq(PortalAuthService.sha256("refresh-ajeno")), any())).thenReturn(Optional.of(2L));
        assertFails(() -> auth.refresh(jwt.issueAccess(1, 0, Clock.now()), "refresh-ajeno"), PortalMessages.SESSION_EXPIRED);
        // Refresh desconocido o usado
        when(store.consumeRefreshToken(anyString(), any())).thenReturn(Optional.empty());
        assertFails(() -> auth.refresh(null, "x"), PortalMessages.SESSION_EXPIRED);
        assertFails(() -> auth.refresh(null, null), PortalMessages.SESSION_EXPIRED);
        // Persona deshabilitada entre tanto
        when(store.consumeRefreshToken(eq(PortalAuthService.sha256("refresh-off")), any())).thenReturn(Optional.of(3L));
        when(store.findById(3)).thenReturn(Optional.of(user(3, auth.hashPassword(PASSWORD), false, null, null)));
        assertFails(() -> auth.refresh(null, "refresh-off"), PortalMessages.SESSION_EXPIRED);
    }

    // ------------------------------------------------------------------ apoyo

    private PortalUser activated(long id, String secret) {
        return user(id, auth.hashPassword(PASSWORD), true, secret, null);
    }

    private PortalUser user(long id, String hash, boolean active, String secret, LocalDateTime confirmed) {
        return new PortalUser(id, "ana@cliente.pe", "Ana", hash, active, 0, null, 0, secret, confirmed, null, null);
    }

    private PortalUser withMfa(PortalUser u, String plainSecret) {
        return new PortalUser(u.id(), u.email(), u.name(), u.passwordHash(), u.active(), 0, null, u.sessionVersion(), cipher.encrypt(plainSecret),
                Clock.nowUtc(), RecoveryCodes.toJson(List.of()), null);
    }

    private static void assertFails(Runnable action, String message) {
        assertThatThrownBy(action::run).isInstanceOf(AdminException.class).satisfies(e -> {
            AdminException ex = (AdminException) e;
            assertThat(ex.messages()).containsExactly(message);
            assertThat(ex.status()).isEqualTo(200);
        });
    }
}
