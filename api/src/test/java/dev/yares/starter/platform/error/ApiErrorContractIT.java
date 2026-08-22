package dev.yares.starter.platform.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Que el contrato declare el error, y que lo declare <strong>bien</strong>.
 *
 * <p>{@link ApiError} es un espejo: describe un cuerpo que construye
 * {@link Problems} con {@code setProperty()}, y nada en el compilador ata una
 * cosa a la otra. Un espejo que nadie vigila se separa del original a la
 * primera de cambio, y el sintoma seria un cliente TypeScript que compila
 * perfectamente contra campos que ya no existen.
 *
 * <p>Por eso la prueba central de aqui no mira solo el documento: provoca
 * errores <strong>reales</strong> contra el servidor y compara las claves que
 * salieron por el cable con las que el esquema promete. Es la unica forma de
 * que este espejo no mienta.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class ApiErrorContractIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    /**
     * El criterio que costaba de HU-004.
     *
     * <p>Springdoc ve un {@code ProblemDetail} con tres campos y una bolsa de
     * propiedades sin tipo, asi que por su cuenta publica un {@code object}
     * generico — y el cliente generado deja los errores como {@code unknown}.
     */
    @Test
    void apiErrorSeDeclaraConSusCamposYNoComoObjetoGenerico() throws Exception {
        JsonNode esquema = contrato().path("components").path("schemas").path("ApiError");

        assertThat(esquema.isMissingNode()).as("ApiError ni siquiera esta en el contrato").isFalse();

        Set<String> propiedades = claves(esquema.path("properties"));
        assertThat(propiedades)
                .as("un 'object' generico no tiene propiedades declaradas")
                .contains("type", "title", "status", "detail", "instance",
                        "code", "traceId", "errors");

        assertThat(esquema.path("properties").path("errors").path("items").path("$ref").asText())
                .isEqualTo("#/components/schemas/FieldIssue");
    }

    /**
     * El catalogo cerrado viaja entero, no como una cadena cualquiera.
     *
     * <p>Es lo que convierte el {@code switch (error.code)} del cliente en algo
     * que el compilador puede comprobar. Con {@code code} tipado como
     * {@code string}, un codigo mal escrito no lo detecta nadie.
     */
    @Test
    void elCodigoLlevaElCatalogoCompletoDeErrorCode() throws Exception {
        JsonNode code = contrato()
                .path("components").path("schemas").path("ApiError")
                .path("properties").path("code");

        List<String> declarados = new java.util.ArrayList<>();
        code.path("enum").forEach(valor -> declarados.add(valor.asText()));

        assertThat(declarados)
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
    }

    /**
     * Ninguna respuesta de error puede quedarse sin cuerpo tipado.
     *
     * <p>Lo garantiza el customizer de {@code OpenApiConfig}, que pasa por
     * encima de todas las operaciones. Esta prueba es la que hace que ese
     * "todas" siga siendo cierto cuando aparezcan los endpoints de HU-003.
     */
    @Test
    void todaRespuestaDeErrorApuntaAApiError() throws Exception {
        JsonNode paths = contrato().path("paths");
        int comprobadas = 0;

        for (JsonNode ruta : paths) {
            for (JsonNode operacion : ruta) {
                JsonNode respuestas = operacion.path("responses");

                for (String codigo : claves(respuestas)) {
                    if (!codigo.startsWith("4") && !codigo.startsWith("5")) {
                        continue;
                    }

                    assertThat(respuestas.path(codigo)
                            .path("content").path("application/problem+json")
                            .path("schema").path("$ref").asText())
                            .as("respuesta %s sin cuerpo tipado", codigo)
                            .isEqualTo("#/components/schemas/ApiError");
                    comprobadas++;
                }
            }
        }

        assertThat(comprobadas)
                .as("si no hubo ninguna respuesta de error que mirar, esta prueba no prueba nada")
                .isGreaterThanOrEqualTo(8);
    }

    /**
     * El espejo contra el original: lo que sale por el cable contra lo declarado.
     *
     * <p>Provoca un {@code 401} de la cadena de filtros y un {@code 400} de Bean
     * Validation — los dos caminos distintos por los que este API construye un
     * error — y comprueba que ninguna clave que viaje de verdad falte en el
     * esquema. Si alguien agrega un {@code setProperty()} en {@link Problems} y
     * no lo agrega a {@link ApiError}, aqui es donde se entera.
     */
    @Test
    void ningunaClaveQueSaleDeVerdadFaltaEnElEsquema() throws Exception {
        Set<String> declaradas = claves(contrato()
                .path("components").path("schemas").path("ApiError").path("properties"));

        Set<String> reales = new LinkedHashSet<>();
        reales.addAll(clavesDelCuerpo(unAuthenticated()));
        reales.addAll(clavesDelCuerpo(validationFailed()));

        assertThat(reales)
                .as("el molde dejo de emitir los campos propios; la comparacion seria vacia")
                .contains("code", "traceId", "errors");

        assertThat(declaradas)
                .as("el contrato promete menos de lo que el API manda")
                .containsAll(reales);
    }

    /** El 401 que escribe la cadena de filtros, fuera del alcance del advice. */
    private ResponseEntity<String> unAuthenticated() {
        ResponseEntity<String> respuesta = http.getForEntity("/auth/me", String.class);
        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        return respuesta;
    }

    /**
     * El 400 de Bean Validation, el unico error que lleva {@code errors}.
     *
     * <p>Hay que pasar por el arranque en frio para conseguir la cookie CSRF:
     * sin ella el {@code POST} muere en un {@code 403} antes de que nadie valide
     * nada, y la prueba afirmaria sobre el error equivocado.
     */
    private ResponseEntity<String> validationFailed() {
        String setCookie = http.getForEntity("/auth/me", String.class)
                .getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String token = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + token);
        headers.add("X-XSRF-TOKEN", token);

        ResponseEntity<String> respuesta = http.exchange("/auth/login", HttpMethod.POST,
                new HttpEntity<>("""
                        {"email": "no-es-un-email", "password": ""}""", headers),
                String.class);

        assertThat(respuesta.getStatusCode())
                .as("cuerpo=%s", respuesta.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        return respuesta;
    }

    private Set<String> clavesDelCuerpo(ResponseEntity<String> respuesta) throws Exception {
        return claves(json.readTree(respuesta.getBody()));
    }

    private static Set<String> claves(JsonNode nodo) {
        Set<String> nombres = new LinkedHashSet<>();
        nodo.fieldNames().forEachRemaining(nombres::add);

        return nombres;
    }

    private JsonNode contrato() throws Exception {
        return json.readTree(http.getForObject("/openapi.json", String.class));
    }
}
