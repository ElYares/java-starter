package dev.yares.starter.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * El seed de desarrollo solo existe bajo el perfil 'dev', que es el unico que
 * agrega 'classpath:db/seed' a las locations de Flyway.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("dev")
class DevSeedIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void hayExactamenteDosUsuariosHabilitados() {
        List<String> emails = jdbc.queryForList(
                "SELECT email FROM users ORDER BY email", String.class);

        assertThat(emails).containsExactly(
                "admin@java-starter.localhost",
                "user@java-starter.localhost");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE enabled", Long.class)).isEqualTo(2L);
    }

    @Test
    void cadaUsuarioTieneSuRol() {
        assertThat(rolesDe("admin@java-starter.localhost")).containsExactly("ADMIN");
        assertThat(rolesDe("user@java-starter.localhost")).containsExactly("USER");
    }

    @Test
    void lasContrasenasSonBcryptDeCoste12YNuncaTextoPlano() {
        List<String> hashes = jdbc.queryForList(
                "SELECT password_hash FROM users", String.class);

        assertThat(hashes).allSatisfy(hash -> {
            assertThat(hash).startsWith("$2a$12$");
            assertThat(hash).doesNotContain("cambiame");
        });
    }

    @Test
    void elSeedEsIdempotente() {
        // R__ es repetible: Flyway lo reaplica cuando cambia su checksum. Los
        // ON CONFLICT tienen que aguantar esa segunda pasada sin duplicar nada.
        long antes = usuarios();
        jdbc.execute("""
                INSERT INTO users (id, email, password_hash, display_name, enabled)
                VALUES ('11111111-1111-1111-1111-111111111111',
                        'admin@java-starter.localhost', 'x', 'x', true)
                ON CONFLICT (id) DO NOTHING""");

        assertThat(usuarios()).isEqualTo(antes);
    }

    private List<String> rolesDe(String email) {
        return jdbc.queryForList("""
                SELECT r.role FROM user_roles r
                JOIN users u ON u.id = r.user_id
                WHERE u.email = ?""", String.class, email);
    }

    private long usuarios() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
        return n == null ? 0 : n;
    }
}
