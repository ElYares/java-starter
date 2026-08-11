package dev.yares.starter.identity.infra;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import dev.yares.starter.identity.app.AttemptBucket;
import dev.yares.starter.identity.app.LoginAttemptStore;
import dev.yares.starter.platform.security.SecurityProperties;
import org.springframework.stereotype.Component;

/**
 * Ventana deslizante en memoria.
 *
 * <p>Deslizante y no de cubetas fijas: con cubetas de quince minutos, un
 * atacante hace cinco intentos al final de una y otros cinco al principio de la
 * siguiente, y consigue diez en pocos segundos sin cruzar ningun limite.
 *
 * <p>El olvido es perezoso: no hay hilo de limpieza, cada clave se poda cuando
 * alguien la consulta. Lo que si hace falta es que las claves muertas
 * desaparezcan del mapa, porque si no un atacante que rota emails hace crecer
 * este mapa sin techo — que seria convertir la defensa en el ataque.
 */
@Component
class InMemoryLoginAttemptStore implements LoginAttemptStore {

    private final ConcurrentMap<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final SecurityProperties properties;
    private final Clock clock;

    InMemoryLoginAttemptStore(SecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Optional<Duration> blockedFor(AttemptBucket bucket) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(properties.login().window());

        // 'compute' y no 'get' + 'put': el bloque corre bajo el cerrojo del
        // segmento, asi que dos peticiones simultaneas de la misma clave no se
        // pisan la lista. Devolver null borra la entrada.
        Deque<Instant> recent = failures.compute(
                bucket.key(), (ignored, attempts) -> prune(attempts, cutoff));

        if (recent == null || recent.size() < bucket.maxAttempts()) {
            return Optional.empty();
        }

        // El bloqueo se levanta cuando el fallo mas viejo sale de la ventana, no
        // cuando pasa una duracion fija: es lo que hace que la ventana deslice.
        Instant oldest = recent.peekFirst();

        return Optional.of(Duration.between(now, oldest.plus(properties.login().window())));
    }

    @Override
    public void recordFailure(AttemptBucket bucket) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(properties.login().window());

        failures.compute(bucket.key(), (ignored, attempts) -> {
            Deque<Instant> pruned = prune(attempts, cutoff);
            Deque<Instant> updated = pruned == null ? new ArrayDeque<>() : pruned;
            updated.addLast(now);

            return updated;
        });
    }

    @Override
    public void clear(AttemptBucket bucket) {
        failures.remove(bucket.key());
    }

    /** Devuelve null cuando no queda nada, para que la clave salga del mapa. */
    private static Deque<Instant> prune(Deque<Instant> attempts, Instant cutoff) {
        if (attempts == null) {
            return null;
        }

        while (!attempts.isEmpty() && attempts.peekFirst().isBefore(cutoff)) {
            attempts.removeFirst();
        }

        return attempts.isEmpty() ? null : attempts;
    }
}
