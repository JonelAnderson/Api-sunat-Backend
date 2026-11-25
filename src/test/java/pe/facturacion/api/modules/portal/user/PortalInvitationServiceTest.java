package pe.facturacion.api.modules.portal.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;
import pe.facturacion.api.modules.admin.api.AdminException;
import pe.facturacion.api.modules.admin.security.AdminRole;
import pe.facturacion.api.modules.admin.security.AdminPrincipal;
import pe.facturacion.api.modules.audit.AuditLogService;
import pe.facturacion.api.modules.portal.security.PortalSessionCache;
import pe.facturacion.api.shared.time.Clock;

/**
 * Invitaciones del portal con la base simulada: el token solo se guarda como SHA-256 y vence a las 72 h; una invitación
 * nueva a un correo existente reemplaza empresas y contraseña, invalida las anteriores, reactiva y cierra sesiones;
 * deshabilitar cierra las sesiones. La integración real está en {@code PortalClientesIT}.
 */
class PortalInvitationServiceTest {

    static final Instant NOW = Instant.parse("2026-11-24T15:00:00Z");
    static final AdminPrincipal CONSOLE = new AdminPrincipal(0, "consola:ana@consola", "ana@consola", AdminRole.SERVICIO);

    PortalUserStore store;
    PortalSessionCache sessions;
    AuditLogService audit;
    PortalInvitationService service;
    final MockHttpServletRequest request = new MockHttpServletRequest();

    @BeforeEach
    void setUp() {
        Clock.useFixed(NOW);
        store = mock(PortalUserStore.class);
        sessions = mock(PortalSessionCache.class);
        audit = mock(AuditLogService.class);
        service = new PortalInvitationService(store, sessions, audit);
        when(store.existingTenantIds(anyCollection())).thenAnswer(i -> Set.copyOf((java.util.Collection<Long>) i.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        Clock.useSystem();
    }

    @Test
    void una_persona_nueva_se_crea_con_sus_empresas_y_una_invitacion_de_72_horas() {
        when(store.insert(eq("ana@cliente.pe"), eq("Ana"), any())).thenReturn(11L);

        PortalInvitationService.Invitation inv = service.invite("ana@cliente.pe", "Ana", List.of(5L, 6L), false, CONSOLE, request);

        assertThat(inv.userId()).isEqualTo(11);
        assertThat(inv.reinvited()).isFalse();
        assertThat(inv.expiresAt()).isEqualTo(LocalDateTime.of(2026, 11, 27, 15, 0, 0));
        // 32 bytes al azar en Base64 URL sin relleno = 43 caracteres
        assertThat(inv.token()).matches("[A-Za-z0-9_-]{43}");
        verify(store, never()).reinvite(anyLong(), anyString(), anyBoolean(), any());
        verify(store).replaceTenants(eq(11L), eq(Set.of(5L, 6L)), any());
        verify(store).invalidateInvitations(eq(11L), any());
    }

    @Test
    void en_la_base_solo_queda_el_sha256_del_token_nunca_el_token() {
        when(store.insert(anyString(), anyString(), any())).thenReturn(11L);

        PortalInvitationService.Invitation inv = service.invite("ana@cliente.pe", "Ana", List.of(5L), false, CONSOLE, request);

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(store).insertInvitation(eq(11L), stored.capture(), eq(inv.expiresAt()), any(), eq("consola:ana@consola"));
        assertThat(stored.getValue()).isEqualTo(PortalAuthService.sha256(inv.token())).isNotEqualTo(inv.token()).matches("[0-9a-f]{64}");
        // Ni la auditoría lleva el token ni su hash
        assertThat(org.mockito.Mockito.mockingDetails(audit).getInvocations()).isNotEmpty();
        org.mockito.Mockito.mockingDetails(audit).getInvocations().forEach(i -> {
            String all = java.util.Arrays.deepToString(i.getArguments());
            assertThat(all).doesNotContain(inv.token()).doesNotContain(stored.getValue());
        });
    }

    @Test
    void dos_invitaciones_son_dos_tokens_distintos() {
        when(store.insert(anyString(), anyString(), any())).thenReturn(11L);
        String a = service.invite("ana@cliente.pe", "Ana", List.of(5L), false, CONSOLE, request).token();
        String b = service.invite("ana@cliente.pe", "Ana", List.of(5L), false, CONSOLE, request).token();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void reinvitar_a_un_correo_existente_reemplaza_empresas_y_contrasena_invalida_las_anteriores_y_cierra_sesiones() {
        PortalUser existing = new PortalUser(11, "ana@cliente.pe", "Ana vieja", "$2a$hash", false, 3, null, 4, "v1:x", LocalDateTime.now(), "[]", null);
        when(store.insert(anyString(), anyString(), any())).thenReturn(0L);
        when(store.findByEmail("ana@cliente.pe")).thenReturn(Optional.of(existing));

        PortalInvitationService.Invitation inv = service.invite("ana@cliente.pe", "Ana Nueva", List.of(8L), false, CONSOLE, request);

        assertThat(inv.userId()).isEqualTo(11);
        assertThat(inv.reinvited()).isTrue();
        InOrder order = inOrder(store, sessions);
        // Nombre nuevo, reactivada, sin contraseña (la reemplaza con el enlace), sin cerrar el MFA salvo que se pida
        order.verify(store).reinvite(eq(11L), eq("Ana Nueva"), eq(false), any());
        order.verify(sessions).invalidate(11);
        order.verify(store).replaceTenants(eq(11L), eq(Set.of(8L)), any());
        // Las invitaciones anteriores dejan de valer antes de emitir la nueva
        order.verify(store).invalidateInvitations(eq(11L), any());
        order.verify(store).insertInvitation(eq(11L), anyString(), any(), any(), anyString());
    }

    @Test
    void con_reset_mfa_se_pide_borrar_el_mfa() {
        when(store.insert(anyString(), anyString(), any())).thenReturn(0L);
        when(store.findByEmail("ana@cliente.pe")).thenReturn(Optional.of(new PortalUser(11, "ana@cliente.pe", "Ana", null, true, 0, null, 0, null, null,
                null, null)));
        service.invite("ana@cliente.pe", "Ana", List.of(5L), true, CONSOLE, request);
        verify(store).reinvite(eq(11L), eq("Ana"), eq(true), any());
    }

    @Test
    void rechaza_empresas_inexistentes_o_eliminadas_sin_crear_nada() {
        when(store.existingTenantIds(anyCollection())).thenReturn(Set.of(5L));

        assertThatThrownBy(() -> service.invite("ana@cliente.pe", "Ana", List.of(5L, 99L), false, CONSOLE, request))
                .isInstanceOf(AdminException.class).satisfies(e -> {
                    AdminException ex = (AdminException) e;
                    assertThat(ex.status()).isEqualTo(200);
                    assertThat(ex.messages().get(0)).contains("99").doesNotContain("5,");
                    assertThat(ex.errors()).hasSize(1);
                });
        verify(store, never()).insert(anyString(), anyString(), any());
        verify(store, never()).replaceTenants(anyLong(), anyCollection(), any());
        verify(store, never()).insertInvitation(anyLong(), anyString(), any(), any(), anyString());
    }

    @Test
    void deshabilitar_cierra_las_sesiones_y_habilitar_no_las_toca() {
        PortalUser u = new PortalUser(11, "ana@cliente.pe", "Ana", "$2a$hash", true, 0, null, 0, null, null, null, null);
        when(store.findById(11)).thenReturn(Optional.of(u));

        service.setActive(11, false, CONSOLE, request);
        verify(store).setActive(eq(11L), eq(false), any());
        verify(store).bumpSession(eq(11L), any());
        verify(sessions).invalidate(11);

        org.mockito.Mockito.clearInvocations(store, sessions);
        service.setActive(11, true, CONSOLE, request);
        verify(store).setActive(eq(11L), eq(true), any());
        verify(store, never()).bumpSession(anyLong(), any());
        verify(sessions).invalidate(11);
    }

    @Test
    void deshabilitar_dos_veces_es_idempotente_y_una_persona_inexistente_es_404() {
        PortalUser off = new PortalUser(11, "ana@cliente.pe", "Ana", "$2a$hash", false, 0, null, 0, null, null, null, null);
        when(store.findById(11)).thenReturn(Optional.of(off));
        service.setActive(11, false, CONSOLE, request);
        service.setActive(11, false, CONSOLE, request);
        verify(store, org.mockito.Mockito.times(2)).setActive(eq(11L), eq(false), any());

        when(store.findById(404)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.setActive(404, false, CONSOLE, request)).isInstanceOf(AdminException.class)
                .satisfies(e -> assertThat(((AdminException) e).status()).isEqualTo(404));
    }
}
