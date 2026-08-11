-- Modulo identity: usuarios, roles y refresh tokens.
--
-- Flyway es el unico que toca el esquema; 'ddl-auto' va en 'validate'. Con
-- Docker la base se recrea seguido y 'update' convierte cada 'down -v' en una
-- loteria.

-- 'citext' hace que el email sea insensible a mayusculas en la propia base. Sin
-- esto, 'Yared@x.com' y 'yared@x.com' son dos cuentas, y eso se descubre en
-- produccion. Va dentro de la migracion a proposito: creada a mano por fuera,
-- la primera base recreada no la tiene.
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
    id            uuid        PRIMARY KEY,
    email         citext      NOT NULL UNIQUE,
    password_hash text        NOT NULL,
    display_name  text        NOT NULL,
    enabled       boolean     NOT NULL DEFAULT true,

    -- Los llena Spring Data JPA Auditing, no el codigo de aplicacion. Sin FK a
    -- 'users': el primer usuario del seed no tiene quien lo haya creado, y una
    -- FK aqui convierte el arranque en un problema del huevo y la gallina.
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    updated_by    uuid
);

-- El rol es una tabla y no un enum de Postgres a proposito: agregar un rol es
-- una fila, no un 'ALTER TYPE'. Por la misma razon tampoco lleva un CHECK con
-- la lista de roles, que tendria el mismo costo de migracion que el enum.
CREATE TABLE user_roles (
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role    text NOT NULL,

    PRIMARY KEY (user_id, role)
);

CREATE TABLE refresh_tokens (
    id          uuid        PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- SHA-256 del token, nunca el token. Una fuga de la base no debe entregar
    -- sesiones activas.
    token_hash  text        NOT NULL UNIQUE,

    issued_at   timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,

    -- Rotacion con deteccion de reuso: si llega un token que ya fue reemplazado,
    -- es firma de robo y se revoca la cadena completa del usuario. Ver CU-002.
    replaced_by uuid        REFERENCES refresh_tokens (id),

    user_agent  text,
    ip          inet
);

-- Revocar la cadena completa de un usuario es la operacion caliente de CU-002,
-- y sin indice es un recorrido de tabla en el peor momento posible.
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
