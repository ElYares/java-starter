package dev.yares.starter.platform.error;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpHeaders;

/**
 * Excepcion de dominio que ya sabe con que {@link ErrorCode} debe salir.
 *
 * <p>Las fabricas estaticas existen para que en el codigo de negocio se lea
 * {@code throw ApiException.notFound("dataset")} y no un constructor con tres
 * argumentos posicionales.
 */
public class ApiException extends RuntimeException {

    private final transient ErrorCode code;

    /**
     * Cabeceras que la respuesta de error debe llevar.
     *
     * <p>Casi siempre vacio. Existe porque algunos errores no se explican solo
     * con el cuerpo: un {@code 429} sin {@code Retry-After} le dice al cliente
     * que espere, pero no cuanto, y entonces reintenta cuando se le ocurre.
     */
    private final transient Map<String, String> headers;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, Map.of());
    }

    public ApiException(ErrorCode code, String detail, Map<String, String> headers) {
        super(detail);
        this.code = code;
        this.headers = Map.copyOf(headers);
    }

    public ApiException(ErrorCode code, String detail, Throwable cause) {
        super(detail, cause);
        this.code = code;
        this.headers = Map.of();
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, String> headers() {
        return headers;
    }

    /**
     * Recurso inexistente — y tambien recurso que existe pero es de otro usuario.
     *
     * <p>Un {@code 403} confirmaria que el recurso existe, y eso permite
     * enumerar ids ajenos. Ver Decision 010. El {@code 403} se reserva para
     * cuando el recurso si es tuyo pero la operacion exige un rol que no tienes.
     */
    public static ApiException notFound(String detail) {
        return new ApiException(ErrorCode.NOT_FOUND, detail);
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(ErrorCode.FORBIDDEN, detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(ErrorCode.CONFLICT, detail);
    }

    public static ApiException unprocessable(String detail) {
        return new ApiException(ErrorCode.UNPROCESSABLE, detail);
    }

    /**
     * Limite de intentos agotado.
     *
     * <p>{@code Retry-After} va en segundos y se redondea hacia arriba: decir
     * "0 segundos" cuando faltan 400 milisegundos invita a un reintento que
     * vuelve a fallar.
     */
    public static ApiException tooManyRequests(String detail, Duration retryAfter) {
        long seconds = Math.max(1, (long) Math.ceil(retryAfter.toMillis() / 1000.0));

        return new ApiException(ErrorCode.TOO_MANY_REQUESTS, detail,
                Map.of(HttpHeaders.RETRY_AFTER, Long.toString(seconds)));
    }
}
