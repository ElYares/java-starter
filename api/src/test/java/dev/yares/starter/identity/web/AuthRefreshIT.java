package dev.yares.starter.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Criterios de aceptacion de CU-002.
 *
 * <p>El reloj es un doble para poder emitir un access token que ya nacio
 * vencido. La alternativa era bajar su vida a un milisegundo por configuracion,
 * pero entonces el token que emite el refresh tambien naceria vencido y no se
 * podria comprobar que la renovacion sirvio de algo.
 *
 * <p>Tres criterios de la nota no se verifican aqui porque no son del backend:
 * que cinco {@code 401} simultaneos produzcan un solo refresh, que el
 * interceptor no reintente cuando el refresh falla, y que el usuario no vea la
 * pantalla de login. Los tres describen al interceptor de Axios y se prueban en
 * CU-003. Lo que si esta aqui es la garantia que los sostiene desde el
 * servidor: cinco rotaciones simultaneas del mismo token dejan una sola
 * sesion viva.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AuthRefreshIT {

    private static final String ADMIN = "admin@java-starter.localhost";
    private static final String OTRO = "user@java-starter.localhost";
    private static final String PASSWORD = "cambiame";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    Clock clock;

    @BeforeEach
    void relojEnHoraSalvoQueUnTestDigaOtraCosa() {
        Mockito.when(clock.instant()).thenAnswer(invocation -> Instant.now());
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    @Test
    void elRefreshRotaElTokenYEncadenaLaFilaNueva() throws Exception {
        Sesion sesion = iniciarSesion(ADMIN, "10.1.0.1");
        UUID anterior = idDeTokenVivo(sesion.rt());

        MvcResult refresco = mvc.perform(post("/auth/refresh")
                        .with(csrf())
                        .cookie(new Cookie("rt", sesion.rt())))
                .andReturn();

        assertThat(refresco.getResponse().getStatus()).isEqualTo(204);

        String rtNuevo = valorDeCookie(refresco, "rt");
        assertThat(rtNuevo).isNotBlank().isNotEqualTo(sesion.rt());

        assertThat(jdbc.queryForObject(
                "SELECT revoked_at IS NOT NULL FROM refresh_tokens WHERE id = ?",
                Boolean.class, anterior))
                .as("la fila anterior queda revocada")
                .isTrue();

        UUID sucesor = idDeTokenVivo(rtNuevo);
        assertThat(jdbc.queryForObject(
                "SELECT replaced_by FROM refresh_tokens WHERE id = ?", UUID.class, anterior))
                .as("la fila anterior apunta a la nueva")
                .isEqualTo(sucesor);

        assertThat(jdbc.queryForObject(
                "SELECT replaced_by FROM refresh_tokens WHERE id = ?", UUID.class, sucesor))
                .as("la fila nueva todavia no ha sido rotada")
                .isNull();
    }

    @Test
    void conElAccessTokenVencidoElRefreshDevuelveElAccesoSinVolverALogin() throws Exception {
        // La sesion se abre con el reloj veinte minutos atras, asi que el access
        // token nace pasado de su vida de quince y el refresh sigue vigente.
        Instant haceVeinteMinutos = Instant.now().minus(Duration.ofMinutes(20));
        Mockito.when(clock.instant()).thenReturn(haceVeinteMinutos);

        Sesion sesion = iniciarSesion(ADMIN, "10.1.0.2");

        Mockito.when(clock.instant()).thenAnswer(invocation -> Instant.now());

        assertThat(mvc.perform(get("/auth/me").cookie(new Cookie("at", sesion.at())))
                .andReturn().getResponse().getStatus())
                .as("el access token ya vencio")
                .isEqualTo(401);

        MvcResult refresco = mvc.perform(post("/auth/refresh")
                        .with(csrf())
                        .cookie(new Cookie("rt", sesion.rt())))
                .andReturn();

        assertThat(refresco.getResponse().getStatus()).isEqualTo(204);

        String atNuevo = valorDeCookie(refresco, "at");
        assertThat(mvc.perform(get("/auth/me").cookie(new Cookie("at", atNuevo)))
                .andReturn().getResponse().getStatus())
                .as("con el token renovado la peticion original pasaria")
                .isEqualTo(200);
    }

    @Test
    void reusarUnTokenYaReemplazadoRevocaTodasLasSesionesDelUsuario() throws Exception {
        Sesion enElMovil = iniciarSesion(ADMIN, "10.1.1.1");
        Sesion enElPortatil = iniciarSesion(ADMIN, "10.1.1.2");
        UUID usuario = idDeUsuario(ADMIN);

        // Rotacion normal: el token de la primera sesion queda reemplazado.
        mvc.perform(post("/auth/refresh").with(csrf())
                .cookie(new Cookie("rt", enElMovil.rt()))).andReturn();

        // El ladron llega con una copia del valor viejo.
        MvcResult robo = mvc.perform(post("/auth/refresh").with(csrf())
                .cookie(new Cookie("rt", enElMovil.rt()))).andReturn();

        assertThat(robo.getResponse().getStatus()).isEqualTo(401);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM refresh_tokens
                 WHERE user_id = ? AND revoked_at IS NULL""", Long.class, usuario))
                .as("no puede quedar ningun token vivo del usuario")
                .isZero();

        assertThat(mvc.perform(post("/auth/refresh").with(csrf())
                .cookie(new Cookie("rt", enElPortatil.rt()))).andReturn().getResponse().getStatus())
                .as("la otra sesion cae tambien, y eso es deliberado")
                .isEqualTo(401);
    }

    @Test
    void elLogoutCierraSoloLaSesionActual() throws Exception {
        Sesion primera = iniciarSesion(OTRO, "10.1.2.1");
        Sesion segunda = iniciarSesion(OTRO, "10.1.2.2");

        MvcResult salida = mvc.perform(post("/auth/logout").with(csrf())
                .cookie(new Cookie("rt", primera.rt()))).andReturn();

        assertThat(salida.getResponse().getStatus()).isEqualTo(204);

        List<String> borradas = salida.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(borradas).hasSize(2);
        assertThat(borradas).allSatisfy(cookie -> assertThat(cookie).contains("Max-Age=0"));

        assertThat(mvc.perform(post("/auth/refresh").with(csrf())
                .cookie(new Cookie("rt", segunda.rt()))).andReturn().getResponse().getStatus())
                .as("el otro navegador sigue funcionando")
                .isEqualTo(204);
    }

    @Test
    void unTokenRevocadoPorLogoutNoSeConfundeConUnRobo() throws Exception {
        Sesion cerrada = iniciarSesion(OTRO, "10.1.3.1");
        Sesion viva = iniciarSesion(OTRO, "10.1.3.2");

        mvc.perform(post("/auth/logout").with(csrf())
                .cookie(new Cookie("rt", cerrada.rt()))).andReturn();

        MvcResult reintento = mvc.perform(post("/auth/refresh").with(csrf())
                .cookie(new Cookie("rt", cerrada.rt()))).andReturn();

        assertThat(reintento.getResponse().getStatus()).isEqualTo(401);

        assertThat(mvc.perform(post("/auth/refresh").with(csrf())
                .cookie(new Cookie("rt", viva.rt()))).andReturn().getResponse().getStatus())
                .as("revocado y reemplazado son estados distintos: esto no es un robo")
                .isEqualTo(204);
    }

    /**
     * La garantia que sostiene CU-002 A1 desde el servidor.
     *
     * <p>La promesa compartida del interceptor evita que se disparen cinco
     * refresh a la vez, pero eso es disciplina del cliente. Esto es el servidor
     * negandose, que es donde las garantias aguantan.
     *
     * <p>Hasta la Decision 012 esto se comprobaba insertando a mano una fila que
     * violara el indice unico. Ya no sirve: con {@code replaced_by} invertido,
     * dos rotaciones simultaneas escriben la misma fila y ningun indice las
     * distingue. Hay que provocar la carrera de verdad.
     *
     * <p>Se afirma sobre el invariante y no sobre el camino: cual de los dos
     * perdedores responde {@code 401} por la carrera y cual por deteccion de
     * reuso depende de a que velocidad confirme cada transaccion, y afirmar eso
     * seria un test intermitente. Lo que nunca puede pasar, gane quien gane, es
     * que una rotacion deje dos refresh tokens validos.
     */
    @Test
    void cincoRefreshSimultaneosConElMismoTokenSoloDejanUnaSesionViva() throws Exception {
        Sesion sesion = iniciarSesion(ADMIN, "10.1.4.1");
        UUID usuario = idDeUsuario(ADMIN);

        int intentos = 5;
        ExecutorService pool = Executors.newFixedThreadPool(intentos);
        CountDownLatch salida = new CountDownLatch(1);

        try {
            List<Future<Integer>> respuestas = new ArrayList<>();
            for (int i = 0; i < intentos; i++) {
                respuestas.add(pool.submit(() -> {
                    // Todos esperan aqui y arrancan juntos: sin esto el primero
                    // termina antes de que el ultimo empiece y no hay carrera.
                    salida.await();
                    return mvc.perform(post("/auth/refresh").with(csrf())
                            .cookie(new Cookie("rt", sesion.rt())))
                            .andReturn().getResponse().getStatus();
                }));
            }

            salida.countDown();

            List<Integer> codigos = new ArrayList<>();
            for (Future<Integer> respuesta : respuestas) {
                codigos.add(respuesta.get(30, TimeUnit.SECONDS));
            }

            assertThat(codigos)
                    .as("un solo refresh prospera; los otros cuatro son 401")
                    .containsOnly(204, 401)
                    .filteredOn(codigo -> codigo == 204)
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM refresh_tokens
                 WHERE user_id = ? AND revoked_at IS NULL""", Integer.class, usuario))
                .as("una rotacion jamas puede dejar dos refresh tokens validos")
                .isLessThanOrEqualTo(1);
    }

    @Test
    void elRefreshSinCookieResponde401() throws Exception {
        MvcResult result = mvc.perform(post("/auth/refresh").with(csrf())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).contains("UNAUTHENTICATED");
    }

    // ── utilidades ──────────────────────────────────────────────────────────

    private record Sesion(String at, String rt) {
    }

    private Sesion iniciarSesion(String email, String ip) throws Exception {
        MvcResult result = mvc.perform(post("/auth/login")
                        .with(csrf())
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}""".formatted(email, PASSWORD)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("el test necesita una sesion abierta para empezar")
                .isEqualTo(204);

        return new Sesion(valorDeCookie(result, "at"), valorDeCookie(result, "rt"));
    }

    private UUID idDeUsuario(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    /** Encuentra la fila por descarte: la unica viva del usuario que no es otra. */
    private UUID idDeTokenVivo(String rt) {
        return jdbc.queryForObject(
                "SELECT id FROM refresh_tokens WHERE token_hash = ?", UUID.class, sha256(rt));
    }

    private static String sha256(String valor) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(valor.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String valorDeCookie(MvcResult result, String nombre) {
        Cookie cookie = result.getResponse().getCookie(nombre);
        assertThat(cookie).as("no vino la cookie %s", nombre).isNotNull();

        return cookie.getValue();
    }
}
