package dev.yares.starter.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Criterios de aceptacion de CU-001.
 *
 * <p>Corre con el perfil {@code dev} para tener el seed: los usuarios de
 * {@code R__dev_seed.sql} son credenciales conocidas y con hash bcrypt real, lo
 * que hace que estos tests ejerciten el mismo camino que produccion en vez de
 * un encoder de juguete.
 *
 * <p>Cada test manda su propia {@code X-Forwarded-For}. Sin eso todos
 * compartirian la IP {@code 127.0.0.1} de MockMvc y el contador de intentos de
 * un test bloquearia al siguiente, con fallos que dependen del orden.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AuthLoginIT {

    private static final String ADMIN = "admin@java-starter.localhost";
    private static final String PASSWORD = "cambiame";

    /** Hash bcrypt de 'cambiame', copiado del seed para no recalcularlo. */
    private static final String HASH_CAMBIAME =
            "$2a$12$7eui22cWp/HlUjRkrmX3kum1BOefoAwzlQhZwJJupp71xQK8UhiRK";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Test
    void loginExitosoDevuelve204YDosCookiesDeSesion() throws Exception {
        MvcResult result = mvc.perform(login(ADMIN, PASSWORD, "10.0.0.1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getContentAsString()).isEmpty();

        List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(cookies).hasSize(3);

        String at = cookieNamed(cookies, "at");
        String rt = cookieNamed(cookies, "rt");

        assertThat(at).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/api;");
        assertThat(rt).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/api/auth");
    }

    /**
     * La pista de la Decision 014.
     *
     * <p>Las tres cosas que se afirman son las tres que la hacen servir: que JS
     * la vea, que no diga nada, y que muera con el refresh token. Si dejara de
     * ser legible el interceptor no podria consultarla y volveria el refresh
     * fantasma; si llevara algo mas que "1" seria una credencial en claro; y si
     * su Max-Age no fuera el del 'rt', sobreviviria a la sesion y devolveria el
     * refresh perdido que vino a evitar.
     */
    @Test
    void laPistaDeSesionEsLegiblePorJsNoDiceNadaYMuereConElRefreshToken() throws Exception {
        MvcResult result = mvc.perform(login(ADMIN, PASSWORD, "10.0.0.9")).andReturn();

        List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        String pista = cookieNamed(cookies, "has_session");

        assertThat(pista)
                .as("el interceptor la lee con document.cookie: HttpOnly la esconderia")
                .doesNotContain("HttpOnly");
        assertThat(pista)
                .as("Path=/ o la SPA no la ve desde una ruta anidada")
                .contains("Path=/;")
                .contains("SameSite=Lax");
        assertThat(valueOf(pista))
                .as("no lleva informacion: el servidor jamas la mira")
                .isEqualTo("1");
        assertThat(maxAgeOf(pista))
                .as("una pista que sobreviva al rt devuelve el refresh fantasma")
                .isEqualTo(maxAgeOf(cookieNamed(cookies, "rt")));
    }

    @Test
    void laBaseNoGuardaEnNingunaColumnaElValorDelRefreshToken() throws Exception {
        MvcResult result = mvc.perform(login(ADMIN, PASSWORD, "10.0.0.2")).andReturn();
        String rtValue = valueOf(cookieNamed(result.getResponse()
                .getHeaders(HttpHeaders.SET_COOKIE), "rt"));

        assertThat(rtValue).isNotBlank();

        List<Map<String, Object>> filas = jdbc.queryForList("SELECT * FROM refresh_tokens");
        assertThat(filas).isNotEmpty();

        for (Map<String, Object> fila : filas) {
            for (Object columna : fila.values()) {
                assertThat(String.valueOf(columna))
                        .as("ninguna columna puede contener el token en claro")
                        .doesNotContain(rtValue);
            }
        }
    }

    @Test
    void emailInexistenteYPasswordIncorrectaRespondenExactamenteLoMismo() throws Exception {
        MvcResult noExiste = mvc.perform(
                login("nadie@java-starter.localhost", PASSWORD, "10.0.0.3")).andReturn();
        MvcResult malaPassword = mvc.perform(
                login(ADMIN, "incorrecta", "10.0.0.4")).andReturn();

        assertThat(noExiste.getResponse().getStatus()).isEqualTo(401);
        assertThat(malaPassword.getResponse().getStatus()).isEqualTo(401);

        assertThat(sinTraceId(noExiste)).isEqualTo(sinTraceId(malaPassword));
        assertThat(sinTraceId(noExiste)).containsEntry("code", "UNAUTHENTICATED");
    }

    /**
     * El criterio habla de "tiempo de respuesta observable". Medir igualdad de
     * tiempos seria un test inestable; lo que si se puede afirmar sin fragilidad
     * es que el camino del email inexistente <strong>tambien</strong> paga
     * bcrypt. Si alguien quita el hash senuelo, esto baja a microsegundos.
     */
    @Test
    void elEmailInexistenteTambienPagaElCostoDeBcrypt() throws Exception {
        long inicio = System.nanoTime();
        mvc.perform(login("fantasma@java-starter.localhost", PASSWORD, "10.0.0.5")).andReturn();
        long milisegundos = (System.nanoTime() - inicio) / 1_000_000;

        assertThat(milisegundos)
                .as("bcrypt de coste 12 no baja de decenas de milisegundos")
                .isGreaterThan(50);
    }

    @Test
    void elSextoIntentoFallidoResponde429AunqueLaPasswordSeaCorrecta() throws Exception {
        String email = "limite@java-starter.localhost";
        insertarUsuario(email, true);

        for (int intento = 1; intento <= 5; intento++) {
            mvc.perform(login(email, "incorrecta", "10.0.1.1"))
                    .andReturn();
        }

        MvcResult sexto = mvc.perform(login(email, PASSWORD, "10.0.1.1")).andReturn();

        assertThat(sexto.getResponse().getStatus()).isEqualTo(429);
        assertThat(sinTraceId(sexto)).containsEntry("code", "TOO_MANY_REQUESTS");
        assertThat(sexto.getResponse().getHeader(HttpHeaders.RETRY_AFTER))
                .isNotNull()
                .satisfies(valor -> assertThat(Integer.parseInt(valor)).isPositive());
        assertThat(sexto.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    void agotarLosIntentosDeUnEmailNoBloqueaLaIpDeOtroUsuario() throws Exception {
        String victima = "victima@java-starter.localhost";
        insertarUsuario(victima, true);

        // Cinco fallos del mismo email desde una IP compartida: una oficina
        // detras de un NAT, o dos personas en la misma casa.
        for (int intento = 1; intento <= 5; intento++) {
            mvc.perform(login(victima, "incorrecta", "10.0.2.1")).andReturn();
        }

        MvcResult otro = mvc.perform(login(ADMIN, PASSWORD, "10.0.2.1")).andReturn();

        assertThat(otro.getResponse().getStatus())
                .as("el bloqueo por email no puede arrastrar a los demas usuarios de la IP")
                .isEqualTo(204);
    }

    @Test
    void usuarioDeshabilitadoRecibe401YNoDejaRefreshToken() throws Exception {
        String email = "apagado@java-starter.localhost";
        UUID id = insertarUsuario(email, false);

        MvcResult result = mvc.perform(login(email, PASSWORD, "10.0.3.1")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(sinTraceId(result)).containsEntry("code", "UNAUTHENTICATED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Long.class, id))
                .isZero();
    }

    @Test
    void sinTokenCsrfElLoginEsRechazado() throws Exception {
        MvcResult result = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo(ADMIN, PASSWORD)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(sinTraceId(result)).containsEntry("code", "FORBIDDEN");
    }

    /**
     * El {@code 401} del molde. La cookie CSRF que esta misma respuesta debe
     * sembrar se verifica en {@code AuthCsrfBootstrapIT} y no aqui:
     * {@code spring-security-test} sustituye el repositorio de tokens CSRF bajo
     * MockMvc, asi que el {@code Set-Cookie} real nunca llega a la respuesta
     * simulada. Comprobarlo aqui daria un falso negativo.
     */
    @Test
    void meSinSesionResponde401DelMolde() throws Exception {
        MvcResult result = mvc.perform(get("/auth/me")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentType())
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(sinTraceId(result)).containsEntry("code", "UNAUTHENTICATED");
        assertThat(result.getResponse().getContentAsString()).contains("traceId");
    }

    @Test
    void conLaCookieDeSesionMeDevuelveElPerfil() throws Exception {
        MvcResult login = mvc.perform(login(ADMIN, PASSWORD, "10.0.4.1")).andReturn();
        Cookie at = login.getResponse().getCookie("at");

        MvcResult result = mvc.perform(get("/auth/me").cookie(at)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        Map<String, Object> perfil = leer(result);
        assertThat(perfil).containsEntry("email", ADMIN);
        assertThat(perfil).containsEntry("displayName", "Admin de desarrollo");
        assertThat(perfil.get("roles")).isEqualTo(List.of("ADMIN"));
        assertThat(perfil).doesNotContainKeys("passwordHash", "enabled");
    }

    // ── utilidades ──────────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder login(String email, String password, String ip) {
        return post("/auth/login")
                .with(csrf())
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo(email, password));
    }

    private String cuerpo(String email, String password) {
        return """
                {"email": "%s", "password": "%s"}""".formatted(email, password);
    }

    private UUID insertarUsuario(String email, boolean habilitado) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, display_name, enabled)
                VALUES (?, ?, ?, 'De prueba', ?)
                ON CONFLICT (email) DO NOTHING""", id, email, HASH_CAMBIAME, habilitado);

        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private Map<String, Object> leer(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    /** El traceId cambia en cada peticion; comparar cuerpos exige sacarlo. */
    private Map<String, Object> sinTraceId(MvcResult result) throws Exception {
        Map<String, Object> cuerpo = leer(result);
        cuerpo.remove("traceId");
        cuerpo.remove("instance");

        return cuerpo;
    }

    private static String cookieNamed(List<String> setCookies, String name) {
        return setCookies.stream()
                .filter(header -> header.startsWith(name + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no vino la cookie " + name));
    }

    private static long maxAgeOf(String setCookieHeader) {
        String tras = setCookieHeader.substring(setCookieHeader.indexOf("Max-Age=") + 8);
        int fin = tras.indexOf(';');

        return Long.parseLong(fin < 0 ? tras : tras.substring(0, fin));
    }

    private static String valueOf(String setCookieHeader) {
        String withoutName = setCookieHeader.substring(setCookieHeader.indexOf('=') + 1);

        return withoutName.substring(0, withoutName.indexOf(';'));
    }
}
