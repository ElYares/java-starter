package dev.yares.starter.identity.infra;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Aborta el arranque en produccion si encuentra los usuarios del seed de
 * desarrollo.
 *
 * <p>El seed no puede aplicarse en produccion, porque {@code db/seed} solo esta
 * en {@code spring.flyway.locations} bajo el perfil {@code dev}. Esta guarda
 * cubre el otro camino, que es el que de verdad pasa: alguien promueve o
 * restaura una base de desarrollo hacia produccion, con
 * {@code admin@java-starter.localhost} y una contrasena publicada en el
 * repositorio adentro.
 *
 * <p>Falla durante la inicializacion del contexto y no en un
 * {@code ApplicationReadyEvent}: un servicio que primero reporta arrancado y
 * despues se cae queda marcado como sano el tiempo suficiente para recibir
 * trafico.
 */
@Component
@Profile("prod")
@DependsOn("flywayInitializer")
class SeedInProductionGuard implements InitializingBean {

    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final List<UUID> SEED_USER_IDS = List.of(ADMIN_ID, USER_ID);

    private final JdbcTemplate jdbc;

    SeedInProductionGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        // Los UUID van como parametros: el driver de Postgres mapea java.util.UUID
        // a 'uuid' sin castear a mano.
        Long found = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE id IN (?, ?)",
                Long.class,
                ADMIN_ID, USER_ID);

        if (found != null && found > 0) {
            throw new IllegalStateException("""
                    El seed de desarrollo esta presente en una base con el perfil 'prod' activo. \
                    Contiene credenciales publicadas en el repositorio. Se aborta el arranque. \
                    Elimina los usuarios %s antes de continuar."""
                    .formatted(SEED_USER_IDS));
        }
    }
}
