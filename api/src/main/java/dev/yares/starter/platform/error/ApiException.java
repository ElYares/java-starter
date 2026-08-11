package dev.yares.starter.platform.error;

/**
 * Excepcion de dominio que ya sabe con que {@link ErrorCode} debe salir.
 *
 * <p>Las fabricas estaticas existen para que en el codigo de negocio se lea
 * {@code throw ApiException.notFound("dataset")} y no un constructor con tres
 * argumentos posicionales.
 */
public class ApiException extends RuntimeException {

    private final transient ErrorCode code;

    public ApiException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ApiException(ErrorCode code, String detail, Throwable cause) {
        super(detail, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
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
}
