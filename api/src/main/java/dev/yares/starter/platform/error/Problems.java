package dev.yares.starter.platform.error;

import dev.yares.starter.platform.web.TraceIdProvider;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Fabrica del molde de HU-002.
 *
 * <p>Existe para que haya un solo lugar donde se decide que campos lleva un
 * error. {@link GlobalExceptionHandler} cubre lo que ocurre dentro de un
 * controlador, pero la cadena de filtros de Spring Security rechaza peticiones
 * antes de llegar ahi; si esos rechazos construyeran su propio cuerpo, el API
 * volveria a tener dos formas de error, que es justo lo que HU-002 vino a
 * evitar.
 */
@Component
public class Problems {

    private final TraceIdProvider traceIds;

    public Problems(TraceIdProvider traceIds) {
        this.traceIds = traceIds;
    }

    /**
     * El identificador de traza de la peticion en curso.
     *
     * <p>Lo necesita quien ya tiene un {@link ProblemDetail} a medio construir
     * y solo le falta este campo, como el manejador de lo que produce Spring
     * MVC por su cuenta.
     */
    public String traceId() {
        return traceIds.currentTraceId();
    }

    public ProblemDetail of(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        problem.setProperty("traceId", traceIds.currentTraceId());

        return problem;
    }
}
