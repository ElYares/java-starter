-- Seed de desarrollo. Solo existe en el classpath cuando el perfil es 'dev':
-- 'spring.flyway.locations' agrega 'classpath:db/seed' unicamente en
-- application-dev.yml. En produccion este archivo no se aplica porque no esta,
-- no porque un 'if' decida bien.
--
-- Es **repetible** (prefijo R__), no versionada, por dos razones: corre siempre
-- despues de todas las versionadas, y no consume un numero de version que luego
-- choque con las migraciones reales. A cambio tiene que ser idempotente, de ahi
-- los ON CONFLICT.
--
-- Las contrasenas son bcrypt de coste 12. La contrasena en claro de ambos
-- usuarios es 'cambiame', y se escribe aqui a proposito: es una credencial de
-- desarrollo, y esconderla seria teatro. Si esta base llega a produccion, el
-- arranque falla — ver SeedInProductionGuard.

INSERT INTO users (id, email, password_hash, display_name, enabled)
VALUES
    ('11111111-1111-1111-1111-111111111111',
     'admin@java-starter.localhost',
     '$2a$12$7eui22cWp/HlUjRkrmX3kum1BOefoAwzlQhZwJJupp71xQK8UhiRK',
     'Admin de desarrollo',
     true),
    ('22222222-2222-2222-2222-222222222222',
     'user@java-starter.localhost',
     '$2a$12$hMt0gJ0xCL8CiW0KQc6UuO3UDiGm/0BAmrJTmtlOdnWGxwLdpZeBC',
     'Usuario de desarrollo',
     true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_roles (user_id, role)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'ADMIN'),
    ('22222222-2222-2222-2222-222222222222', 'USER')
ON CONFLICT (user_id, role) DO NOTHING;
