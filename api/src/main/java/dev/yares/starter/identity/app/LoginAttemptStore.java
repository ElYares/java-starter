package dev.yares.starter.identity.app;

import java.time.Duration;
import java.util.Optional;

/**
 * Contador de intentos fallidos de login.
 *
 * <p>Existe como interfaz para que la limitacion de la implementacion actual sea
 * visible en vez de estar enterrada: hoy el contador vive en memoria, asi que
 * <strong>con dos instancias cinco intentos se vuelven diez</strong>. Alcanza
 * mientras el starter corra en una sola instancia. Cuando deje de ser cierto, lo
 * que cambia es una clase y una linea de configuracion, no el caso de uso.
 *
 * <p>La clave incluye su propia dimension ({@code email:...}, {@code ip:...})
 * porque CU-001 E1 exige contar por las dos de forma independiente: solo por IP
 * se saltea con NAT o una botnet chica, y solo por email deja bloquear a un
 * tercero a voluntad.
 */
public interface LoginAttemptStore {

    /**
     * Cuanto falta para que esta clave vuelva a poder intentar.
     *
     * <p>Vacio significa "puede intentar". Devuelve la espera en lugar de un
     * booleano para que quien pregunta pueda responder con {@code Retry-After}
     * sin una segunda consulta que podria dar otro resultado.
     */
    Optional<Duration> blockedFor(AttemptBucket bucket);

    void recordFailure(AttemptBucket bucket);

    /** Un login exitoso borra la cuenta: el limite castiga fallos, no usuarios. */
    void clear(AttemptBucket bucket);
}
