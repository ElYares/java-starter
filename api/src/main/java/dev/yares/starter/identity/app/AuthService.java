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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El ciclo de vida de una sesion: abrirla (CU-001), renovarla y cerrarla
 * (CU-002).
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

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
    private final SessionRevoker sessionRevoker;
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
            PasswordEncoder passwords, LoginAttemptStore attempts, SessionRevoker sessionRevoker,
            AccessTokens accessTokens, OpaqueTokens opaqueTokens, SecurityProperties properties,
            Clock clock) {

        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
        this.attempts = attempts;
        this.sessionRevoker = sessionRevoker;
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

    /**
     * Rota el refresh token y emite un access token nuevo (CU-002).
     *
     * <p>El orden de las comprobaciones no es intercambiable. El reuso se mira
     * <strong>antes</strong> que la revocacion porque un token rotado tambien
     * quedo revocado: preguntando al reves, todo robo se leeria como una sesion
     * cerrada normal y la cadena nunca se revocaria.
     */
    @Transactional
    public IssuedSession refresh(String refreshValue, String userAgent, String ip) {
        if (refreshValue == null || refreshValue.isBlank()) {
            throw sesionInvalida();
        }

        RefreshToken token = refreshTokens.findByTokenHash(opaqueTokens.hash(refreshValue))
                .orElseThrow(AuthService::sesionInvalida);

        Instant now = clock.instant();
        User duenio = token.user();

        // CU-002 E2: este token ya fue rotado. El legitimo y el ladron tienen
        // copias del mismo valor y los dos lo usaron; no hay forma de saber cual
        // es cual, asi que caen los dos.
        //
        // Desde la Decision 012 esto es leer la fila que ya se tiene en la mano.
        // Antes era una consulta contra la base por cada refresh.
        if (token.replacedBy() != null) {
            // En transaccion aparte: el 401 de abajo revierte esta, y con ella
            // se iria la revocacion. Ver SessionRevoker.
            int revocados = sessionRevoker.revokeAllOfUser(duenio.id(), now);
            log.warn("Reuso de refresh token detectado [usuario={} sesionesRevocadas={}]",
                    duenio.id(), revocados);

            throw sesionInvalida();
        }

        if (token.isRevoked() || token.isExpiredAt(now)) {
            throw sesionInvalida();
        }

        String nuevoValor = opaqueTokens.mint();
        RefreshToken sucesor = RefreshToken.issue(duenio, opaqueTokens.hash(nuevoValor),
                now, now.plus(properties.refresh().ttl()), userAgent, ip);

        // El sucesor se escribe primero y con flush: 'replaced_by' es una clave
        // foranea contra esta misma tabla, asi que la fila tiene que existir
        // antes de que nadie pueda apuntarla.
        refreshTokens.saveAndFlush(sucesor);

        // Y el enlace despues, condicionado a que el token siguiera sin rotar.
        // Cero filas significa que otro hilo gano la carrera de CU-002 A1; la
        // promesa compartida del interceptor evita llegar aqui, y esto es la red
        // debajo, para cuando no lo haga. El 401 revierte tambien el insert de
        // arriba, asi que el sucesor huerfano no queda.
        if (refreshTokens.markRotated(token.id(), sucesor.id(), now) == 0) {
            throw sesionInvalida();
        }

        return new IssuedSession(accessTokens.issue(duenio.id(), duenio.roles()), nuevoValor);
    }

    /**
     * Cierra la sesion actual y solo la actual (CU-002 A2).
     *
     * <p>Revocar, no reemplazar: son estados distintos. Si el logout dejara el
     * token marcado como reemplazado, reintentarlo se leeria como un robo y
     * tumbaria todas las sesiones del usuario en todos sus dispositivos.
     *
     * <p>Es idempotente y nunca falla. Un logout que responde error deja al
     * usuario mirando una pantalla que dice que sigue dentro cuando ya no lo
     * esta, y su unica salida es volver a intentarlo.
     */
    @Transactional
    public void logout(String refreshValue) {
        if (refreshValue == null || refreshValue.isBlank()) {
            return;
        }

        refreshTokens.findByTokenHash(opaqueTokens.hash(refreshValue))
                .ifPresent(token -> token.revokeAt(clock.instant()));
    }

    /**
     * Un solo motivo para todos los caminos muertos del refresh.
     *
     * <p>Token inexistente, vencido, revocado o robado responden identico: el
     * cliente no gana nada sabiendo cual, y un atacante si.
     */
    private static ApiException sesionInvalida() {
        return new ApiException(ErrorCode.UNAUTHENTICATED, "Tu sesion no es valida. Inicia sesion.");
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
