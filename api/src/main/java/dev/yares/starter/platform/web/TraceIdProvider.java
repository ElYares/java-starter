package dev.yares.starter.platform.web;

import java.util.UUID;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Devuelve el identificador de traza de la peticion en curso.
 *
 * <p>Sale del span activo de Micrometer Tracing, que es el mismo que aparece en
 * cada linea de log: si se generara uno nuevo por respuesta, el log de la
 * peticion y el cuerpo del error llevarian identificadores distintos y no se
 * podrian cruzar, que es justo para lo que sirve el campo.
 *
 * <p>Nunca devuelve nulo ni cadena vacia. Si no hay span — un error tan temprano
 * que la instrumentacion no alcanzo a correr, o un test de rebanada — inventa
 * uno. Un {@code traceId} inventado sigue siendo mejor que ninguno: al menos
 * correlaciona las lineas de log de esa misma respuesta.
 */
@Component
public class TraceIdProvider {

    private final ObjectProvider<Tracer> tracer;

    public TraceIdProvider(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    public String currentTraceId() {
        Tracer resolved = tracer.getIfAvailable();
        if (resolved != null) {
            Span span = resolved.currentSpan();
            if (span != null) {
                String traceId = span.context().traceId();
                if (traceId != null && !traceId.isBlank()) {
                    return traceId;
                }
            }
        }

        return UUID.randomUUID().toString().replace("-", "");
    }
}
