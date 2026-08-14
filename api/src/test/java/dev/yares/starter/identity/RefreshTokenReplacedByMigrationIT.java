package dev.yares.starter.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * La Decision 012 sobre datos que ya existen.
 *
 * <p>Existe porque el resto de la suite no puede cubrir esto. Flyway corre
 * entero contra un contenedor limpio, asi que en todos los demas tests el
 * {@code UPDATE} de {@code V3} se aplica sobre una tabla vacia y pasa siempre,
 * diga lo que diga. La parte interesante de una migracion que transforma datos
 * es justamente la que solo se ve con datos: hay que migrar a medias, sembrar
 * la orientacion vieja y solo entonces aplicar la nueva.
 *
 * <p>Sin Spring: lo que se prueba es SQL, y levantar un contexto para mirar dos
 * columnas solo agregaria formas de fallar que no tienen que ver con la
 * migracion.
 */
@Testcontainers
class RefreshTokenReplacedByMigrationIT {

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final UUID USUARIO = UUID.randomUUID();

    /** Cadena de tres: A se roto en B, y B en C. C es el token vivo. */
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();

    /** Una sesion distinta que nunca se roto: sola en su cadena. */
    private static final UUID SOLITARIO = UUID.randomUUID();

    /**
     * Un solo test y no dos, aunque compruebe dos cosas: el contenedor es de la
     * clase, asi que un segundo metodo que migrara a {@code V3} dejaria la base
     * ya migrada y el otro se volveria dependiente del orden de ejecucion --
     * verde o rojo segun quien corriera primero. Las dos afirmaciones describen
     * el mismo evento.
     */
    @Test
    void invierteLasCadenasQueYaExistianYSeLlevaElIndiceDeV2() throws Exception {
        migrarHasta("2");
        sembrarEnLaOrientacionVieja();

        migrarHasta("3");

        Map<UUID, UUID> apuntaA = leerReplacedBy();

        assertThat(apuntaA.get(A)).as("A fue reemplazado por B").isEqualTo(B);
        assertThat(apuntaA.get(B)).as("B fue reemplazado por C").isEqualTo(C);
        assertThat(apuntaA.get(C))
                .as("C es el token vivo: todavia no lo ha reemplazado nadie")
                .isNull();
        assertThat(apuntaA.get(SOLITARIO))
                .as("una sesion sin rotar queda igual que nacio")
                .isNull();

        // El indice de V2 tiene que irse antes que los datos, y no por orden: en
        // la cadena A -> B -> C, al reescribir A para que apunte a B, la fila C
        // todavia conserva su valor viejo -- que tambien es B -- y ese cruce
        // transitorio viola la unicidad. Con el indice puesto, la migracion
        // aborta a medio camino. Que este test haya llegado hasta aqui ya lo
        // demuestra; esto lo deja dicho.
        assertThat(existeElIndiceDeV2())
                .as("idx_refresh_tokens_replaced_by ya no puede existir")
                .isFalse();
    }

    private static void migrarHasta(String version) {
        Flyway.configure()
                .dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
    }

    private static void sembrarEnLaOrientacionVieja() throws Exception {
        try (Connection cx = conectar(); Statement st = cx.createStatement()) {
            st.execute("""
                    INSERT INTO users (id, email, password_hash, display_name)
                    VALUES ('%s', 'migracion@java-starter.localhost', 'x', 'Migracion')"""
                    .formatted(USUARIO));

            // replaced_by apuntando hacia atras, como hasta V2.
            insertar(st, A, null);
            insertar(st, B, A);
            insertar(st, C, B);
            insertar(st, SOLITARIO, null);
        }
    }

    private static void insertar(Statement st, UUID id, UUID predecesor) throws Exception {
        st.execute("""
                INSERT INTO refresh_tokens
                    (id, user_id, token_hash, issued_at, expires_at, replaced_by)
                VALUES ('%s', '%s', '%s', now(), now() + interval '14 days', %s)"""
                .formatted(id, USUARIO, id,
                        predecesor == null ? "NULL" : "'" + predecesor + "'"));
    }

    private static Map<UUID, UUID> leerReplacedBy() throws Exception {
        Map<UUID, UUID> filas = new LinkedHashMap<>();

        try (Connection cx = conectar();
                Statement st = cx.createStatement();
                ResultSet rs = st.executeQuery("SELECT id, replaced_by FROM refresh_tokens")) {

            while (rs.next()) {
                filas.put(rs.getObject("id", UUID.class), rs.getObject("replaced_by", UUID.class));
            }
        }

        return filas;
    }

    private static boolean existeElIndiceDeV2() throws Exception {
        try (Connection cx = conectar();
                Statement st = cx.createStatement();
                ResultSet rs = st.executeQuery("""
                        SELECT 1 FROM pg_indexes
                         WHERE indexname = 'idx_refresh_tokens_replaced_by'""")) {

            return rs.next();
        }
    }

    private static Connection conectar() throws Exception {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }
}
