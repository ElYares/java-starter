package dev.yares.starter.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * El primer criterio de HU-004: que el contrato exista y se pueda pedir.
 *
 * <p>Va sobre HTTP de verdad y no con MockMvc porque lo que se comprueba no es
 * solo que springdoc genere el documento, sino que <strong>la cadena de
 * seguridad lo deje salir</strong>. Spring Security contesta antes de resolver
 * la ruta: si {@code /openapi.json} no estuviera en la lista de rutas abiertas,
 * este endpoint responderia {@code 401} con un {@code ProblemDetail} — un JSON
 * perfectamente valido que el generador del cliente aceptaria como si fuera el
 * contrato, produciendo un cliente vacio en vez de un error.
 *
 * <p>La ruta va <strong>sin</strong> {@code /api}: {@code TestRestTemplate}
 * antepone el {@code context-path} por su cuenta.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class OpenApiContractIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper json;

    @Test
    void elContratoSePublicaSinSesion() throws Exception {
        ResponseEntity<String> response = http.getForEntity("/openapi.json", String.class);

        assertThat(response.getStatusCode())
                .as("cuerpo=%s", response.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode document = json.readTree(response.getBody());

        assertThat(document.path("openapi").asText())
                .as("sin esto lo que llego es otra cosa con forma de JSON")
                .startsWith("3.");
        assertThat(document.path("info").path("title").asText()).isEqualTo("java-starter API");
    }

    @Test
    void elDocumentoDeclaraLosCuatroEndpointsDeAuth() throws Exception {
        JsonNode paths = contrato().path("paths");

        assertThat(paths.path("/auth/login").has("post")).isTrue();
        assertThat(paths.path("/auth/refresh").has("post")).isTrue();
        assertThat(paths.path("/auth/logout").has("post")).isTrue();
        assertThat(paths.path("/auth/me").has("get")).isTrue();
    }

    /**
     * El prefijo {@code /api} vive en el servidor, no en las rutas.
     *
     * <p>Si springdoc lo metiera en las claves de {@code paths}, el cliente
     * generado sobre un {@code baseURL} de {@code /api} pediria
     * {@code /api/api/auth/login} — que no existe y responde {@code 401}, un
     * fallo que se lee como "las credenciales estan mal" y no lo estan. Es el
     * mismo doble prefijo que ya muerde en el arnes de pruebas.
     */
    @Test
    void elPrefijoApiEstaUnaSolaVez() throws Exception {
        JsonNode contrato = contrato();

        assertThat(contrato.path("servers").path(0).path("url").asText())
                .as("relativa, no absoluta: la SPA y el API comparten origen")
                .isEqualTo("/api");

        assertThat(contrato.path("paths").fieldNames())
                .toIterable()
                .isNotEmpty()
                .allSatisfy(ruta -> assertThat(ruta).doesNotStartWith("/api"));
    }

    /**
     * El contrato tiene que decir lo que el API hace, no lo que springdoc supone.
     *
     * <p>Los tres endpoints mutantes responden {@code 204} y sin cuerpo — los
     * tokens viajan en {@code Set-Cookie}. Sin declararlo, springdoc rellena un
     * {@code 200} por omision porque es lo unico que sabe deducir de un
     * {@code ResponseEntity<Void>}, y el cliente generado nace creyendo que
     * existe una respuesta con cuerpo que no existe.
     */
    @Test
    void losEndpointsMutantesDeclaran204YNoEl200QueSpringdocSupone() throws Exception {
        JsonNode paths = contrato().path("paths");

        for (String ruta : new String[] { "/auth/login", "/auth/refresh", "/auth/logout" }) {
            JsonNode responses = paths.path(ruta).path("post").path("responses");

            assertThat(responses.has("204")).as("%s deberia declarar 204", ruta).isTrue();
            assertThat(responses.has("200")).as("%s no responde 200 nunca", ruta).isFalse();
        }
    }

    /**
     * La cookie {@code rt} es {@code HttpOnly}: ningun cliente puede ponerla.
     *
     * <p>Publicarla como parametro le daria al cliente generado un argumento
     * imposible de rellenar desde el navegador — y quien lo rellenara creeria
     * estar mandando algo que el navegador va a sobrescribir igual.
     */
    @Test
    void laCookieDelRefreshNoSePublicaComoParametro() throws Exception {
        JsonNode paths = contrato().path("paths");

        assertThat(paths.path("/auth/refresh").path("post").has("parameters")).isFalse();
        assertThat(paths.path("/auth/logout").path("post").has("parameters")).isFalse();
    }

    @Test
    void elPerfilSeDeclaraComoJsonYNoComoComodin() throws Exception {
        JsonNode contenido = contrato()
                .path("paths").path("/auth/me").path("get")
                .path("responses").path("200").path("content");

        assertThat(contenido.has("application/json")).isTrue();
        assertThat(contenido.has("*/*")).isFalse();
    }

    private JsonNode contrato() throws Exception {
        return json.readTree(http.getForObject("/openapi.json", String.class));
    }
}
