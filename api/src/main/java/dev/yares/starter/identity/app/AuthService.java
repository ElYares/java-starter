package dev.yares.starter.identity.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import dev.yares.starter.identity.domain.RefreshToken;
import dev.yares.starter.identity.domain.User;
import dev.yares.starter.identity.infra.RefreshTokenRepository;
import dev.yares.starter.identity.infra.UserRepository;
import dev.yares.starter.platform.error.ApiException;
import dev.yares.starter.platform.error.ErrorCode;
import dev.yares.starter.platform.security.AccessTokens;
import dev.yares.starter.platform.security.SecurityProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El ciclo de vida de una sesion: abrirla (CU-001).
 */
@Service
public class AuthService {

    /**
     * Mensaje unico para credenciales invalidas y para cuenta deshabilitada.
     *
     * <p>Distinguirlos convierte el login en un oraculo de que direcciones estan
     * registradas, que es el primer paso de cualquier campana de phishing
     * dirigido. Ver CU-001 A1 y A2.
     */
    private static final String CREDENCIALES_INVALIDAS = "Email o contrasena incorrectos";

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwords;
    private final LoginAttemptStore attempts;
    private final AccessTokens accessTokens;
    private final OpaqueTokens opaqueTokens;
    private final SecurityProperties properties;
    private final Clock clock;

    /**
     * Hash contra el que se verifica cuando el email no existe.
     *
     * <p>Sin esto, un email inexistente responde en microsegundos y uno
     * existente tarda lo que tarda bcrypt. Esa diferencia es medible desde
     * fuera y basta para enumerar la base de usuarios sin acertar una sola
     * contrasena. Se genera al arrancar en vez de escribirse como constante
     * para que siempre corresponda al coste que el encoder use hoy.
     */
    private final String hashSenuelo;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens,
            PasswordEncoder passwords, LoginAttemptStore attempts, AccessTokens accessTokens,
            OpaqueTokens opaqueTokens, SecurityProperties properties, Clock clock) {

        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
        this.attempts = attempts;
        this.accessTokens = accessTokens;
        this.opaqueTokens = opaqueTokens;
        this.properties = properties;
        this.clock = clock;
        this.hashSenuelo = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public IssuedSession login(String email, String password, String userAgent, String ip) {
        AttemptBucket porEmail = emailBucket(email);
        AttemptBucket porIp = ipBucket(ip);

        // Antes de tocar bcrypt, no despues: verificar primero le regalaria al
        // atacante cientos de milisegundos de CPU del servidor por intento, que
        // es un ataque de denegacion gratis. CU-001 E1.
        rejectIfBlocked(porEmail, porIp);

        Optional<User> found = users.findByEmail(email);

        // La contrasena se verifica siempre, incluso contra un usuario
        // inexistente o deshabilitado, para que las tres respuestas tarden lo
        // mismo. El resultado se descarta en los dos ultimos casos.
        boolean passwordOk = passwords.matches(
                password, found.map(User::passwordHash).orElse(hashSenuelo));

        if (found.isEmpty() || !passwordOk || !found.get().enabled()) {
            attempts.recordFailure(porEmail);
            attempts.recordFailure(porIp);

            throw new ApiException(ErrorCode.UNAUTHENTICATED, CREDENCIALES_INVALIDAS);
        }

        attempts.clear(porEmail);
        attempts.clear(porIp);

        return openSession(found.get(), userAgent, ip);
    }

    private IssuedSession openSession(User user, String userAgent, String ip) {
        Instant now = clock.instant();
        String refreshValue = opaqueTokens.mint();

        refreshTokens.save(RefreshToken.issue(
                user,
                opaqueTokens.hash(refreshValue),
                now,
                now.plus(properties.refresh().ttl()),
                userAgent,
                ip));

        return new IssuedSession(accessTokens.issue(user.id(), user.roles()), refreshValue);
    }

    private void rejectIfBlocked(AttemptBucket porEmail, AttemptBucket porIp) {
        Optional<Duration> espera = attempts.blockedFor(porEmail)
                .or(() -> attempts.blockedFor(porIp));

        if (espera.isPresent()) {
            throw ApiException.tooManyRequests(
                    "Demasiados intentos fallidos. Intenta de nuevo mas tarde.", espera.get());
        }
    }

    /**
     * El email se normaliza para la clave del contador aunque la base sea
     * {@code citext}: el mapa en memoria no sabe nada de {@code citext}, y sin
     * esto {@code Yared@x.com} y {@code yared@x.com} tendrian cinco intentos
     * cada uno.
     */
    private AttemptBucket emailBucket(String email) {
        return new AttemptBucket(
                "email:" + email.trim().toLowerCase(Locale.ROOT),
                properties.login().maxAttempts());
    }

    private AttemptBucket ipBucket(String ip) {
        return new AttemptBucket("ip:" + ip, properties.login().maxAttemptsPerIp());
    }
}
