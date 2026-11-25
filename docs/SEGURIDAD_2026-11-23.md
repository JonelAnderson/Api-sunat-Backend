# Revisión de seguridad — 2026-11-23

Revisión de solo lectura del código propio, la configuración, el Dockerfile, el compose y las migraciones, hecha junto con
la migración a Spring Boot 4.2 / PostgreSQL 18. Complementa [`AUDITORIA.md`](AUDITORIA.md) (Fase 8).

**Dependencias:** las 174 dependencias de ejecución con Spring Boot 4.2.0 se consultaron en OSV (osv.dev) el 2026-11-23:
**0 avisos**. Una consulta de control con una versión vieja de Jackson sí devolvió avisos, así que la búsqueda funcionó.

**Calificación:** el código de la aplicación obtiene **7.5/10**: criptografía correcta, XXE cerrado, aislamiento entre
empresas verificado y SQL parametrizado. Tal como se despliega con el `docker-compose.yml` actual obtiene **5/10**, porque
Redis y PostgreSQL quedan publicados y Redis no tiene contraseña.

## Hallazgos

| ID | Severidad | Hallazgo | Evidencia | Corrección |
| --- | --- | --- | --- | --- |
| H1 | **Crítica** (si el puerto es alcanzable) | Redis publicado en el host y sin contraseña: con escribir una clave en Redis se obtienen credenciales nuevas de **cualquier** empresa | `docker-compose.yml` (redis con `ports` y sin `--requirepass`); `CredencialesController` toma el `tenant_id` del token guardado en Redis | No publicar Redis ni Postgres (o solo `127.0.0.1`); `--requirepass` obligatorio; guardar el token de recuperación como hash |
| H2 | **Alta** | El bridge "adopta" una empresa que registró antes un tercero y le devuelve las **mismas** credenciales | `RegistroController` (registro público de cualquier RUC); `BridgeController.provision` (si el RUC existe, reemplaza SOL y certificado y devuelve el api_secret existente) | 409 si la empresa existe y no es del mismo usuario del bridge; rotar key y secret al adoptar; validar el RUC |
| H3 | **Alta** (negocio) | Pruebas gratuitas de 14 días repetibles sin límite: plan de pago sin pagar | `SubscriptionService` (crea otra prueba sin revisar si ya hubo una) | Registrar `trial_used` por empresa; no crear pruebas sobre otra prueba |
| H4 | Media | XSS almacenado por el logo: se guarda con la extensión que manda el cliente y se sirve en `/storage/**` | `EmpresaController.storeLogo` usa `clientExtension` | Extensión según el contenido (lista blanca png/jpg/webp); `Content-Security-Policy: default-src 'none'; sandbox` en `/storage/**` |
| H5 | Media | El cuerpo de la petición se lee entero en memoria, sin límite y antes de autenticar | `CachedBodyFilter` | Rechazar cuerpos de más de 1–2 MB; lectura acotada |
| H6 | Media | SSRF por los webhooks: DNS rebinding y fail-open si el nombre no resuelve | `WebhookJob` (valida resolviendo el DNS y conecta resolviendo de nuevo) | Conectar a la IP ya validada; fail-closed; exigir https |
| H7 | Media | El límite de peticiones corre después de autenticar: los intentos fallidos no tienen límite | `WebMvcConfig` (orden de interceptores) | Límite por IP antes de autenticar, contando los 401 |
| H8 | Media | Se confía en `X-Forwarded-For` de cualquier IP privada | `forward-headers-strategy: native` | `server.tomcat.remoteip.internal-proxies` con la IP del proxy |
| H9 | Media | Postgres publicado en el host y la API conectada como superusuario | `docker-compose.yml`; migración `V2` | No publicar el puerto; separar el rol de migración del de ejecución |
| H10 | Baja-Media | El api_secret regenerado queda en claro en `idempotency_keys` | `ApiPipelineFilter` / `IdempotencyStore` | Excluir de idempotencia las rutas de credenciales |
| H11 | Baja-Media | El cupo mensual se revisa una vez en el envío masivo y sin atomicidad | `SaleDocumentEndpoints` (masivo) | Revisar el cupo por documento dentro de la transacción |
| H12 | Baja-Media | El ZIP de exportación se arma entero en memoria | `ExportController` | Streaming y tope |
| H13 | Baja | El RUC no se valida como 11 dígitos en el registro ni en el bridge | `RegistroController`, `BridgeController` | `digits:11` + módulo 11 |
| H14 | Baja | Swagger público también en producción | `application.yml` (springdoc) | Deshabilitar con `APP_ENV=production` |
| H15 | Baja | Empresa demo y `demo-credentials.txt` también en producción | `DatabaseSeeder` | No sembrar la demo en producción |
| H16 | Baja | Sin `Cache-Control: no-store` en respuestas con secretos | `SecurityConfig` | `no-store` en `/api/**` |
| H17 | Baja | Se guarda la contraseña del certificado aunque no se usa | `EmpresaController`, `RegistroController`, `BridgeController` | No guardarla |
| H18 | Baja | AES-GCM sin AAD ni rotación de clave | `SecretCipher` | AAD por columna/fila e id de clave |
| H19 | Baja | Mensajes de excepción internos visibles para la empresa | `GreClient`, `SendSettlementJob`, `CdrReader` | Mensaje genérico + `error_id` |
| H20 | Info | Menores: autorización solo por interceptores, `sol_user` sin enmascarar en el log, GET+DEL de recuperación no atómico, SMTP sin `starttls.required`, descompresión sin tope, desborde del offset de paginación | varios | Ver detalle en la revisión |

## Lo que está bien hecho

- Comparación en tiempo constante del api_secret, el token interno y la clave del bridge. Sin ellos configurados, esas
  funciones quedan apagadas.
- Sin caché de la empresa: rotar o desactivar tiene efecto inmediato.
- AES-256-GCM con IV aleatorio. No arranca sin una clave de 32 bytes. SOL, client_secret, api_secret y certificado
  quedan cifrados en reposo.
- Aislamiento entre empresas en todas las rutas `{id}`, listados, panel, reportes y ZIP.
- SQL parametrizado y `ORDER BY` con lista blanca. Sin asignación masiva: `PUT /empresa` no cambia plan ni estado.
- XXE cerrado en CDR, SOAP y firma.
- PDF solo con plantillas propias y `th:text`; el renderizador no descarga URLs.
- `FileStorage` no permite salir del disco.
- Logs y `api_logs` enmascaran secretos. Error 500 genérico con `error_id`.
- Contenedor sin root. Actuator solo con `health` e `info`. CORS limitado a `APP_URL`.

## Superficie nueva: panel de administración (`/api/admin/**`, 2026-11-23)

Agregado después de esta revisión (diseño en `Docs/api-sunat-panel-admin-diseno.md`; código en `modules/admin`,
migración `V7__panel_admin.sql`). Apagado (503) sin `ADMIN_JWT_SECRET` de al menos 32 bytes. Protecciones: contraseña
BCrypt con política 8–64 y MFA TOTP obligatorio (clave cifrada con `SecretCipher`, códigos de recuperación como SHA-256,
un código TOTP no se acepta dos veces); 5 fallos → bloqueo de 15 min sin revelar si el correo existe; 10 intentos por
minuto por IP en `/api/admin/auth/**` **antes** de autenticar (no repite H7); JWT HS256 de 30 min solo con id, rol y
`session_version` (sin datos personales), ticket MFA de 5 min que no sirve como acceso, refresh opaco rotativo de 7
días guardado como SHA-256; logout, cambio o restablecimiento de contraseña, restablecer el MFA y desactivar revocan
todas las sesiones; permisos por rol leídos de la BD en cada petición (caché de 30 s) y `lectura` nunca escribe; CORS
propio con `ADMIN_ALLOWED_ORIGINS` (sin comodín) solo para `/api/admin/**`; `Cache-Control: no-store` (H16) en todo
el panel; las respuestas eligen columna por columna y nunca devuelven SOL, certificado, su contraseña, `api_secret`,
`client_secret` ni tokens; no pasa por `api_logs` ni por la idempotencia (H10); cada escritura, login y bloqueo va a
`audit_logs` con `actor_tipo = admin`. Pendiente conocido: H5 (cuerpo leído entero antes de autenticar) aplica también
aquí, y la autorización sigue siendo por interceptores (H20).

## Estado de H2 (2026-11-24)

`BridgeController.provision` ya no adopta una empresa ajena: si el RUC existe y su `user_id` no es el del usuario (correo)
que pide el bridge —o la empresa no tiene dueño, como las del registro público o la consola— responde **409** con
`{"error":"El RUC ya está registrado en otra cuenta."}`, sin reemplazar SOL ni certificado, sin devolver credenciales y sin
crear el usuario. Solo el dueño puede volver a provisionar (recibe sus mismas credenciales). Además, el registro público
(`POST /api/v1/registro`, la otra mitad de H2) viene apagado por defecto: `REGISTRO_PUBLICO_ENABLED=false`. Pendiente:
validar el dígito verificador del RUC. Pruebas: `BridgeOwnershipTest` (sin Docker) y `CuentaIT.bridgeNoAdoptaLaEmpresaDeOtro`.

## Prioridad

1. **H1 y H9** (despliegue): no publicar Redis ni Postgres y ponerle contraseña a Redis. Es configuración.
2. **H2 y H3** (lógica): pocas líneas cada uno.
3. **H4 a H8**.
4. El resto, en la siguiente iteración.

## Superficie nueva: fases C y D del panel (2026-11-24)

Escrituras sobre los datos de una empresa y acciones ante SUNAT en su nombre desde `/api/admin/**` (diseño en
`Docs/api-sunat-panel-admin-diseno.md` §11; código en `modules/admin/tenant` y `modules/admin/document`). Reutilizan los
mismos servicios de `/api/v1` con la empresa de la ruta (sin atajos: validación, cupo del plan, correlativos, cola y
plazos), con permisos por endpoint (`soporte` solo reenvía, anula y refresca; `lectura` nada; eliminar empresas solo
superadmin), aislamiento por `tenant_id` (otro id → 404), límites por minuto de la empresa y del administrador, e
idempotencia obligatoria al emitir. Credenciales de solo escritura (nunca vuelven en respuestas, vistas, auditoría ni
logs); `api_key` / `api_secret` del alta y de "regenerar" se muestran una sola vez con `no-store`. Hallazgos cerrados en
ese camino: **H4** (logo por su contenido: png / jpg / webp), **H10** (sin idempotencia en respuestas con secretos;
también en `POST /api/v1/empresa/credenciales/regenerar`, que ya no guarda su respuesta en `idempotency_keys`), **H13**
(RUC de 11 dígitos con prefijo y módulo 11 en el alta del panel) y **H17** (el panel no guarda la contraseña del
certificado). Siguen abiertos para `/api/v1`, el registro y el bridge: H4, H13 y H17, y la `Content-Security-Policy` de
`/storage/**`.

## Superficie nueva: portal de clientes (`/api/portal/**`, 2026-11-24)

Web pública del cliente final (diseño en `Docs/api-sunat-portal-clientes-diseno.md`; código en `modules/portal` y
`modules/admin/portal`, migración `V12__portal_clientes.sql`). Es la primera superficie con usuarios **externos** que
llegan desde internet, así que el modelo es más estricto que el del panel:

- **Apagado y sin mezclar mundos.** Sin `PORTAL_JWT_SECRET` (≥ 32 bytes) todo responde 503; si es igual a la del panel
  también queda apagado. El JWT del portal lleva `iss = api-sunat-portal` y `aud = portal` y se verifica emisor, audiencia
  y tipo además de la firma: un token del panel no entra al portal ni uno del portal al panel, **aunque las dos claves
  fueran iguales** (`PortalJwtTest`, `PortalInterceptorTest`). El ticket MFA (5 min) no sirve como acceso. Sin datos
  personales en el token (solo id y `session_version`).
- **Sin registro propio.** Las cuentas nacen de una invitación de la consola: token de 32 bytes al azar, en la base solo su
  SHA-256, 72 h, de un solo uso (consumo atómico con `UPDATE … RETURNING`), invalidada por una invitación nueva. Un token
  inválido, usado, vencido, reemplazado o de una cuenta deshabilitada responde **siempre el mismo mensaje**; la contraseña
  se valida antes de gastar el token. Invitar de nuevo es idempotente y reemplaza empresas y contraseña, cierra sesiones
  y no deja una cuenta a medias (`ON CONFLICT` ante invitaciones simultáneas).
- **Autenticación.** BCrypt con política 8–64, MFA TOTP obligatorio (clave cifrada con `SecretCipher`, códigos de
  recuperación como SHA-256, un paso de TOTP no se acepta dos veces gracias a una marca en Redis), 5 fallos → bloqueo de
  15 min en la base, mensajes que no revelan si el correo existe, 10 intentos por minuto y por IP en `/api/portal/auth/**`
  **antes** de autenticar (incluida la activación, que de otro modo permitiría probar tokens por fuerza bruta).
  Refresh opaco rotativo de 7 días guardado como SHA-256. Cerrar sesión, cambiar la contraseña, deshabilitar y reinvitar
  revocan todas las sesiones al instante (`session_version`, caché de 30 s invalidada en el mismo proceso).
- **Aislamiento entre clientes.** Cada empresa se resuelve desde las de la persona (`portal_user_tenants`) en cada
  endpoint, nunca desde un parámetro libre ni el cuerpo: una empresa ajena responde 404 idéntico a una inexistente. Los
  comprobantes se buscan siempre por `tenant_id`. Una prueba de reglas (`PortalEndpointsRulesTest`) rompe si una ruta
  nueva de empresa no pasa por `PortalTenantAccess.require`.
- **Solo lectura.** El interceptor rechaza (403, falla cerrada) cualquier escritura fuera de `/api/portal/auth/**` salvo
  `POST …/credenciales/regenerar`, aunque algún día se declare otro endpoint.
- **Credenciales de API.** Regenerar exige contraseña y código TOTP en el mismo pedido (un solo mensaje si falla cualquiera
  de los dos, que cuenta para el bloqueo; el quinto cierra las sesiones), la empresa activa y un límite de 3 por hora y por
  empresa que **falla cerrado** sin Redis. Las nuevas se muestran una vez con `no-store`; el secreto no queda en
  auditoría ni logs. Ninguna otra respuesta del portal devuelve SOL, certificado, `client_secret`, `api_secret` guardado,
  tokens, hash ni claves del MFA (las columnas se eligen una por una; lo vigilan `PortalEndpointsRulesTest` y
  `PortalClientesIT`).
- **Auditoría.** Altas, bloqueos, activaciones, detalle y descarga de comprobantes y regeneración quedan en `audit_logs`
  (`actor_tipo = portal` para la persona, `admin` para la consola), sin tokens ni contraseñas. Corrección incluida:
  `AuditLogService.registrarAdmin` ya no intenta guardar `user_id = 0` de la consola (violaba la clave foránea y la fila se
  perdía); ahora deja `user_id` vacío y el actor queda en `actor_etiqueta`.
- **CORS** propio (`PORTAL_ALLOWED_ORIGINS`, sin comodín, sin credenciales) solo para `/api/portal/**`; `Cache-Control:
  no-store` en todo el portal. Los permisos de la consola para invitar o deshabilitar (`Admin.Portal.Manage`) son solo del
  rol de servicio y del superadmin.

Pendiente conocido: H5 (cuerpo leído entero antes de autenticar) aplica también aquí; la autorización sigue siendo por
interceptores (H20); las pruebas de integración (`PortalClientesIT`) necesitan Docker y no se han ejecutado en este
entorno; no hay recuperación de contraseña por correo (la consola reinvita); el límite por IP depende de que el proxy
reenvíe la IP real.
