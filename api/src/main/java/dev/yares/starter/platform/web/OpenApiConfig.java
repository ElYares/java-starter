package dev.yares.starter.platform.web;

import java.util.List;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
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
}
