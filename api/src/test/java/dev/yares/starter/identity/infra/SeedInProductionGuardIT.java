package dev.yares.starter.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * La guarda se prueba directamente y no levantando el perfil 'prod': lo que hay
 * que verificar es que detecte el seed y aborte, no el cableado de @Profile.
 */
@Testcontainers
@SpringBootTest
class SeedInProductionGuardIT {

    private static final UUID ADMIN_SEED_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void dejaArrancarCuandoLaBaseNoTieneElSeed() {
        jdbc.update("DELETE FROM users WHERE id = ?", ADMIN_SEED_ID);

        assertThatCode(() -> new SeedInProductionGuard(jdbc).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void abortaElArranqueCuandoEncuentraUnUsuarioDelSeed() {
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, display_name)
                VALUES (?, 'admin@java-starter.localhost', 'x', 'x')
                ON CONFLICT (id) DO NOTHING""", ADMIN_SEED_ID);

        assertThatThrownBy(() -> new SeedInProductionGuard(jdbc).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seed de desarrollo")
                .hasMessageContaining(ADMIN_SEED_ID.toString());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?",
                Long.class, ADMIN_SEED_ID)).isEqualTo(1L);
    }
}
