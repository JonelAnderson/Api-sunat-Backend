package pe.facturacion.api.modules.admin.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Endpoint anónimo del panel (login, MFA y refresh). El resto exige un JWT de acceso válido. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AdminPublic {
}
