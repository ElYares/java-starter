package dev.yares.starter.platform.web;

import java.util.List;

import dev.yares.starter.platform.error.ApiError;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Los metadatos del contrato que springdoc no puede inferir.
 *
 * <p>Lo que de verdad importa aqui es el <strong>servidor</strong>. Sin este
 * bean springdoc deduce la URL de la peticion con la que se pidio el documento,
 * asi que el mismo backend publica {@code http://localhost:8080/api} si lo pides
 * por el puerto y {@code http://api:8080/api} si lo pides desde otro contenedor.
 * Ninguna de las dos le sirve al cliente generado: la SPA se sirve del mismo
 * origen que el API — es el trato entero de la
 * {@code Decision 003} — y la unica URL correcta desde el navegador es la
 * relativa. Declararla fija tambien evita que el documento cambie segun quien
 * lo pida, que es justo lo que arruinaria el diff al regenerar.
 *
 * <p>La version es una constante y no {@code ${project.version}} a proposito:
 * versionar el contrato esta fuera del alcance de HU-004 — hay un solo consumidor
 * — y colgarla del POM haria que cada bump de version reescribiera el cliente
 * generado entero sin que ningun endpoint hubiera cambiado.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String REF_API_ERROR = "#/components/schemas/ApiError";

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("java-starter API")
                        .version("v1")
                        .description("""
                                El contrato del que se genera el cliente TypeScript de la SPA. \
                                Las rutas van sin '/api': ese prefijo es el servidor, y es \
                                tambien el 'baseURL' de la instancia de Axios del cliente."""))
                .servers(List.of(new Server()
                        .url("/api")
                        .description("El mismo origen que sirve la SPA")));
    }

    /**
     * Le pone cuerpo tipado a todos los errores, y un {@code 500} a todo.
     *
     * <p>Se hace en un solo lugar y no anotando cada endpoint por dos razones.
     * La primera es que repetir el bloque {@code @Content(schema = ...)} en cada
     * respuesta de error es ruido que nadie mantiene. La segunda importa mas:
     * asi <strong>no se puede</strong> declarar una respuesta de error sin
     * cuerpo tipado. Un endpoint nuevo que anote un {@code 409} lo recibe
     * apuntando a {@code ApiError} sin que su autor tenga que saberlo, y el
     * dia que alguien lo olvide no habra un {@code object} generico escondido
     * en el contrato — porque este customizer pasa por encima de todas.
     *
     * <p>El {@code 500} se agrega a todas las operaciones porque todas pueden
     * producirlo: {@code GlobalExceptionHandler.handleUnexpected} atrapa
     * cualquier excepcion no prevista, venga de donde venga. Un contrato que
     * lo omite no es mas simple, es incompleto.
     */
    @Bean
    OpenApiCustomizer erroresConCuerpoTipado() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }

            // 'readAll' arrastra tambien los tipos anidados, asi que FieldIssue
            // entra por su cuenta. Hace falta registrarlos a mano porque ningun
            // metodo de controlador devuelve ApiError: springdoc solo publica
            // los esquemas que ve en una firma o en una anotacion.
            ModelConverters.getInstance().readAll(ApiError.class)
                    .forEach(openApi.getComponents()::addSchemas);

            openApi.getPaths().values().stream()
                    .flatMap(ruta -> ruta.readOperations().stream())
                    .map(operacion -> operacion.getResponses())
                    .forEach(this::tiparErrores);
        };
    }

    private void tiparErrores(ApiResponses respuestas) {
        respuestas.computeIfAbsent("500", codigo -> new ApiResponse()
                .description("Error interno. El cuerpo no describe la implementacion; "
                        + "el detalle esta en los logs, indexado por el mismo traceId"));

        respuestas.forEach((codigo, respuesta) -> {
            if (codigo.startsWith("4") || codigo.startsWith("5")) {
                respuesta.setContent(cuerpoDeError());
            }
        });
    }

    private Content cuerpoDeError() {
        return new Content().addMediaType(PROBLEM_JSON,
                new MediaType().schema(new Schema<>().$ref(REF_API_ERROR)));
    }
}
