package dev.yares.starter.identity.infra;

import java.util.Optional;
import java.util.UUID;

import dev.yares.starter.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * La insensibilidad a mayusculas la da {@code citext} en la columna, no esta
     * firma: {@code 'Yared@x.com'} y {@code 'yared@x.com'} son el mismo valor
     * para Postgres. Por eso no hay {@code IgnoreCase} aqui, que ademas
     * generaria un {@code lower(email)} incapaz de usar el indice unico.
     */
    Optional<User> findByEmail(String email);
}
