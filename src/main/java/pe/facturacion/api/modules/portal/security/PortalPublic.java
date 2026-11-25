package pe.facturacion.api.modules.portal.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Endpoint anónimo del portal (activar, login, MFA y refresh). El resto exige un JWT de acceso válido del portal. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PortalPublic {
}
