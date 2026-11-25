package pe.facturacion.api.modules.portal.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT HS256 del portal de clientes (nimbus-jose-jwt), con la misma forma que el del panel pero de otro mundo: cada
 * token lleva {@code iss = api-sunat-portal} y {@code aud = portal}, y se firma con {@code PORTAL_JWT_SECRET}. Se
 * comprueban emisor <b>y</b> audiencia al verificar, así que un token del panel ({@code iss = api-sunat-admin}) nunca
 * sirve aquí ni uno del portal en {@code /api/admin/**}, aunque alguien configurara la misma clave en los dos.
 * <ul>
 *   <li>acceso ({@code typ=access}, 30 min): {@code sub} (id), {@code sv} (session_version), {@code amr=["pwd","mfa"]}.
 *       Sin datos personales ni empresas;</li>
 *   <li>ticket MFA ({@code typ=mfa}, 5 min): {@code sub}, {@code stage} ({@code verify} / {@code setup}) y {@code sv}.
 *       Nunca sirve como token de acceso.</li>
 * </ul>
 * Sin estado ni reloj propio: quien llama pasa "ahora" (así las pruebas fijan la hora).
 */
public final class PortalJwt {

    public static final String ISSUER = "api-sunat-portal";
    public static final String AUDIENCE = "portal";
    public static final String TYP_ACCESS = "access";
    public static final String TYP_MFA = "mfa";
    public static final String STAGE_VERIFY = "verify";
    public static final String STAGE_SETUP = "setup";
    public static final Duration ACCESS_TTL = Duration.ofMinutes(30);
    public static final Duration MFA_TICKET_TTL = Duration.ofMinutes(5);

    /** Claims del token de acceso ya verificado. */
    public record AccessClaims(long userId, int sessionVersion, Instant expiresAt) {
    }

    /** Claims del ticket MFA ya verificado. */
    public record MfaClaims(long userId, String stage, int sessionVersion) {
    }

    private final byte[] secret;

    /** @throws IllegalArgumentException si la clave tiene menos de 32 bytes. */
    public PortalJwt(byte[] secret) {
        if (secret == null || secret.length < 32) {
            throw new IllegalArgumentException("La clave del JWT del portal debe tener al menos 32 bytes");
        }
        this.secret = secret.clone();
    }

    public String issueAccess(long userId, int sessionVersion, Instant now) {
        JWTClaimsSet claims = base(userId, now, ACCESS_TTL)
                .claim("typ", TYP_ACCESS)
                .claim("sv", sessionVersion)
                .claim("amr", List.of("pwd", "mfa"))
                .build();
        return sign(claims);
    }

    public String issueMfaTicket(long userId, String stage, int sessionVersion, Instant now) {
        JWTClaimsSet claims = base(userId, now, MFA_TICKET_TTL)
                .claim("typ", TYP_MFA)
                .claim("stage", stage)
                .claim("sv", sessionVersion)
                .build();
        return sign(claims);
    }

    /** Token de acceso válido: firma, emisor, audiencia, {@code typ=access} y no vencido. */
    public Optional<AccessClaims> verifyAccess(String token, Instant now) {
        return parse(token, TYP_ACCESS, now, true).flatMap(c -> {
            try {
                Integer sv = c.getIntegerClaim("sv");
                if (sv == null) {
                    return Optional.empty();
                }
                return Optional.of(new AccessClaims(Long.parseLong(c.getSubject()), sv, c.getExpirationTime().toInstant()));
            } catch (ParseException | NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    /** Ticket MFA válido: firma, emisor, audiencia, {@code typ=mfa}, etapa conocida y no vencido. */
    public Optional<MfaClaims> verifyMfaTicket(String token, Instant now) {
        return parse(token, TYP_MFA, now, true).flatMap(c -> {
            try {
                Integer sv = c.getIntegerClaim("sv");
                String stage = c.getStringClaim("stage");
                if (sv == null || !(STAGE_VERIFY.equals(stage) || STAGE_SETUP.equals(stage))) {
                    return Optional.empty();
                }
                return Optional.of(new MfaClaims(Long.parseLong(c.getSubject()), stage, sv));
            } catch (ParseException | NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    /**
     * Usuario de un token de acceso con firma válida aunque ya haya vencido (para el refresh, que recibe el token
     * viejo junto con el refresh token).
     */
    public Optional<Long> subjectOfAccessIgnoringExpiry(String token) {
        return parse(token, TYP_ACCESS, null, false).flatMap(c -> {
            try {
                return Optional.of(Long.parseLong(c.getSubject()));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    private JWTClaimsSet.Builder base(long userId, Instant now, Duration ttl) {
        Instant iat = Instant.ofEpochSecond(now.getEpochSecond());
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(String.valueOf(userId))
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(iat))
                .expirationTime(Date.from(iat.plus(ttl)));
    }

    private String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("No se pudo firmar el JWT del portal", e);
        }
    }

    private Optional<JWTClaimsSet> parse(String token, String typ, Instant now, boolean checkExpiry) {
        if (token == null || token.isBlank() || token.length() > 4096) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token.trim());
            // Solo HS256: nunca "none" ni otro algoritmo elegido por quien envía el token
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(new MACVerifier(secret))) {
                return Optional.empty();
            }
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            List<String> aud = c.getAudience();
            if (!ISSUER.equals(c.getIssuer()) || aud == null || !aud.contains(AUDIENCE) || !typ.equals(c.getStringClaim("typ"))
                    || c.getSubject() == null) {
                return Optional.empty();
            }
            if (checkExpiry) {
                Date exp = c.getExpirationTime();
                if (exp == null || !exp.toInstant().isAfter(now)) {
                    return Optional.empty();
                }
            }
            return Optional.of(c);
        } catch (ParseException | JOSEException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
