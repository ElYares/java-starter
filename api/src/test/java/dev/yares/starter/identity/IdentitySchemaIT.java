package dev.yares.starter.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifica el esquema de identity contra un Postgres real, con las mismas
 * migraciones que produccion.
 *
 * <p>Cada prueba corresponde a un criterio de aceptacion de HU-001.
 */
@Testcontainers
@SpringBootTest
class IdentitySchemaIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayAplicaLaMigracionInicial() {
        List<String> estados = jdbc.queryForList(
                "SELECT success::text FROM flyway_schema_history WHERE version = '1'",
                String.class);

        assertThat(estados).containsExactly("true");
    }

    @Test
    void existenLasTresTablasConSusColumnas() {
        assertThat(columnasDe("users"))
                .contains("id", "email", "password_hash", "display_name", "enabled",
                        "created_at", "updated_at", "created_by", "updated_by");
        assertThat(columnasDe("user_roles")).contains("user_id", "role");
        assertThat(columnasDe("refresh_tokens"))
                .contains("id", "user_id", "token_hash", "issued_at", "expires_at",
                        "revoked_at", "replaced_by", "user_agent", "ip");
    }

    @Test
    void elEmailEsInsensibleAMayusculas() {
        insertaUsuario(UUID.randomUUID(), "Yared@x.com");

        assertThatThrownBy(() -> insertaUsuario(UUID.randomUUID(), "yared@x.com"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void borrarUnUsuarioSeLlevaSusRolesYSusTokens() {
        UUID userId = UUID.randomUUID();
        insertaUsuario(userId, "cascada@x.com");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'USER')", userId);
        jdbc.update("""
                INSERT INTO refresh_tokens (id, user_id, token_hash, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?)""",
                UUID.randomUUID(), userId, "hash-" + userId,
                OffsetDateTime.now(), OffsetDateTime.now().plusDays(14));

        jdbc.update("DELETE FROM users WHERE id = ?", userId);

        assertThat(cuenta("SELECT count(*) FROM user_roles WHERE user_id = ?", userId)).isZero();
        assertThat(cuenta("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", userId)).isZero();
    }

    @Test
    void sinPerfilDevNoHaySeed() {
        assertThat(cuenta("SELECT count(*) FROM users WHERE email LIKE ?", "%@java-starter.localhost"))
                .isZero();
    }

    private List<String> columnasDe(String tabla) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?",
                String.class, tabla);
    }

    private void insertaUsuario(UUID id, String email) {
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, display_name)
                VALUES (?, ?, 'x', 'x')""", id, email);
    }

    private long cuenta(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
