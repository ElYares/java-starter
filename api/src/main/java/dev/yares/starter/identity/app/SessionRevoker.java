package dev.yares.starter.identity.app;

import java.time.Instant;
import java.util.UUID;

import dev.yares.starter.identity.infra.RefreshTokenRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revoca sesiones en una transaccion propia.
 *
 * <p>Existe por un detalle que cuesta caro y no se ve leyendo el codigo: la
 * revocacion en cadena de CU-002 E2 ocurre inmediatamente antes de lanzar un
 * {@code 401}. Hecha dentro de la transaccion del refresh, la excepcion
 * provoca el rollback y <strong>deshace la revocacion</strong>: el servidor
 * responde que detecto el robo y deja todas las sesiones del ladron intactas.
 * La defensa parece funcionar y no hace nada.
 *
 * <p>{@code REQUIRES_NEW} confirma la revocacion por su cuenta, antes de que la
 * transaccion de afuera se caiga. Y va en un bean aparte porque Spring
 * implementa {@code @Transactional} con un proxy: llamarse a si mismo desde
 * otro metodo de la misma clase no pasa por el proxy y la anotacion no haria
 * nada — otro fallo silencioso.
 */
@Component
public class SessionRevoker {

    private final RefreshTokenRepository refreshTokens;

    public SessionRevoker(RefreshTokenRepository refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllOfUser(UUID userId, Instant moment) {
        return refreshTokens.revokeAllOfUser(userId, moment);
    }
}
