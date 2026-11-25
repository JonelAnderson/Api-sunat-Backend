package pe.facturacion.api.modules.admin.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Permiso que exige el endpoint (ver {@link AdminPermissions}); sin la anotación basta con estar autenticado. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AdminPermission {

    String value();
}
