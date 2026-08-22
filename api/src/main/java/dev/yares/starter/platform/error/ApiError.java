package dev.yares.starter.platform.error;

import java.net.URI;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * La forma del cuerpo de error, escrita para que el contrato pueda declararla.
 *
 * <p><strong>Este record no se instancia nunca.</strong> El cuerpo que viaja de
 * verdad lo construye {@link Problems#of} sobre el {@code ProblemDetail} nativo
 * de Spring, y ahi {@code code}, {@code traceId} y {@code errors} se ponen con
 * {@code setProperty()} — un mapa abierto que se serializa aplanado. Springdoc
 * no puede inferir nada de eso: lo que ve es un {@code ProblemDetail} con tres
 * campos y una bolsa de propiedades sin tipo, y lo publica como un
 * {@code object} generico. El resultado seria un cliente TypeScript que tipa
 * cada respuesta feliz y deja los errores como {@code unknown}, justo en la
 * parte del contrato que mas se usa.
 *
 * <p>De ahi que exista este espejo. Y de ahi que un espejo sea peligroso: puede
 * separarse del original sin que nada se queje. Lo que impide esa separacion es
 * {@code OpenApiContractIT}, que provoca errores reales contra el servidor y
 * compara las claves que salen con las que este record declara. Si alguien
 * agrega un {@code setProperty()} en {@link Problems} y no lo agrega aqui, esa
 * prueba falla.
 *
 * @param code el discriminador con el que decide el cliente. Va tipado como
 *             {@link ErrorCode} y no como {@code String} a proposito: asi el
 *             cliente generado recibe el catalogo cerrado entero y un
 *             {@code switch} sobre el se puede comprobar en compilacion
 * @param errors solo viaja en {@code VALIDATION_FAILED}; en cualquier otro error
 *               esta ausente
 */
@Schema(name = "ApiError",
        description = "Error en formato RFC 7807. Se sirve como application/problem+json.")
public record ApiError(

        @Schema(description = "URI del tipo de error, relativa",
                example = "/errors/validation-failed",
                requiredMode = Schema.RequiredMode.REQUIRED)
        URI type,

        @Schema(description = "Titulo estable para humanos", example = "La peticion no es valida",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String title,

        @Schema(description = "Codigo de estado HTTP", example = "400",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int status,

        @Schema(description = "Que paso en este caso concreto. Puede cambiar sin aviso: "
                + "el cliente decide con 'code', nunca con este texto",
                example = "Revisa los campos marcados",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String detail,

        @Schema(description = "Ruta que produjo el error", example = "/api/auth/login",
                requiredMode = Schema.RequiredMode.REQUIRED)
        URI instance,

        @Schema(description = "El discriminador. Catalogo cerrado", example = "VALIDATION_FAILED",
                requiredMode = Schema.RequiredMode.REQUIRED)
        ErrorCode code,

        @Schema(description = "Identificador de traza de la peticion. Es lo que convierte un "
                + "'no funciona' en una busqueda en los logs",
                example = "6a8941ac6d3a53508346a4bfe8f1d799",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String traceId,

        @Schema(description = "Detalle campo por campo. Solo presente cuando code es "
                + "VALIDATION_FAILED")
        List<FieldIssue> errors) {
}
