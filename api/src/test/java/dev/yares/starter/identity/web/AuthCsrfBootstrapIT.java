package dev.yares.starter.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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
 * El arranque en frio de CU-001 E2, sobre HTTP de verdad.
 *
 * <p>Va aparte de {@code AuthLoginIT} por una razon concreta:
 * {@code spring-security-test} sustituye el repositorio de tokens CSRF cuando
 * se usa MockMvc, asi que alli el {@code Set-Cookie} nunca aparece aunque el
 * token se genere. Comprobado con una sonda: bajo MockMvc el filtro corre, el
 * token se resuelve, y la respuesta sale sin cabecera. Es un artefacto del
 * arnes, no del codigo — y la unica forma de distinguir una cosa de la otra es
 * levantar el servidor y mirar la respuesta real.
 *
 * <p>Lo que se verifica es la secuencia entera que hace un navegador al abrir
 * la SPA por primera vez: pedir el perfil sin tener sesion, recibir la cookie
 * CSRF de esa misma respuesta, y poder iniciar sesion con ella.
 *
 * <p>Las rutas van <strong>sin</strong> {@code /api}: {@code TestRestTemplate}
 * antepone el {@code context-path} por su cuenta. Escribirlo igual produce
 * {@code /api/api/auth/login}, que no existe y responde {@code 401} — un fallo
 * que se lee como "las credenciales estan mal" y no lo estan.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class AuthCsrfBootstrapIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    @Test
    void elPrimerMeSinSesionSiembraLaCookieCsrfYConEllaSePuedeIniciarSesion() {
        ResponseEntity<String> me = http.getForEntity("/auth/me", String.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String setCookie = me.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .as("sin esta cookie el login seria la unica ruta mutante que habria "
                        + "que exceptuar de CSRF")
                .isNotNull()
                .contains("XSRF-TOKEN=")
                .doesNotContain("HttpOnly")
                // 'endsWith' y no 'contains': con 'contains("Path=/")' la
                // cookie en 'Path=/api' tambien pasaria — y de hecho paso,
                // escondiendo que la ruta estaba mal puesta.
                .endsWith("; Path=/");

        String token = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        assertThat(token).isNotBlank();

        // Segundo paso: el navegador reenvia la cookie y copia su valor en la
        // cabecera. Eso es el double-submit entero.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + token);
        headers.add("X-XSRF-TOKEN", token);

        ResponseEntity<String> login = http.exchange("/auth/login", HttpMethod.POST,
                new HttpEntity<>("""
                        {"email": "admin@java-starter.localhost", "password": "cambiame"}""",
                        headers),
                String.class);

        assertThat(login.getStatusCode())
                .as("cuerpo=%s", login.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        List<String> sesion = login.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(sesion).isNotNull();
        assertThat(sesion.stream().anyMatch(c -> c.startsWith("at="))).isTrue();
        assertThat(sesion.stream().anyMatch(c -> c.startsWith("rt="))).isTrue();
    }

    @Test
    void sinLaCabeceraXsrfElLoginEsRechazado() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> login = http.exchange("/auth/login", HttpMethod.POST,
                new HttpEntity<>("""
                        {"email": "admin@java-starter.localhost", "password": "cambiame"}""",
                        headers),
                String.class);

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(login.getBody()).contains("\"code\":\"FORBIDDEN\"").contains("traceId");
    }
}
