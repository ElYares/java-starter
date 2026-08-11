package dev.yares.starter.identity.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

/**
 * Usuario que puede iniciar sesion.
 *
 * <p>Primera entidad JPA del proyecto, y por tanto el momento en que
 * {@code ddl-auto: validate} empieza a comparar de verdad contra el esquema que
 * escribio Flyway en {@code V1__identity.sql}. Si esta clase y esa migracion se
 * separan, el contexto no arranca — que es el punto.
 *
 * <p>No hay setters ni constructor publico: en CU-001 y CU-002 nadie crea ni
 * modifica usuarios, solo se leen. El dia que exista un alta, el metodo que la
 * haga sera explicito y no un setter suelto.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    /**
     * {@code citext} y no {@code text}: la insensibilidad a mayusculas la
     * resuelve la base, no una llamada a {@code toLowerCase()} que alguien
     * olvidara en la segunda consulta que escriba.
     */
    @Column(nullable = false, unique = true, columnDefinition = "citext")
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private boolean enabled;

    /**
     * Los roles viven en su propia tabla, sin entidad propia: no tienen
     * identidad ni ciclo de vida fuera del usuario, que es justo la definicion
     * de {@code @ElementCollection}.
     *
     * <p>{@code EAGER} a proposito. Los roles se necesitan siempre que se carga
     * un usuario — para firmar el access token — y son dos o tres filas. Una
     * carga diferida aqui solo agrega una consulta y la posibilidad de una
     * {@code LazyInitializationException} con {@code open-in-view: false}.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", nullable = false)
    private Set<String> roles;

    /**
     * Columnas de auditoria, mapeadas solo para lectura.
     *
     * <p>Quien las llena es Spring Data JPA Auditing, que entra con HU-003.
     * Hasta entonces {@code insertable = false} evita que esta entidad prometa
     * escribir algo que nadie escribe todavia, y las deja fuera de todo INSERT
     * para que gane el {@code DEFAULT now()} de la migracion.
     */
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "created_by", insertable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by", insertable = false, updatable = false)
    private UUID updatedBy;

    protected User() {
        // Requerido por JPA.
    }

    public UUID id() {
        return id;
    }

    public String email() {
        return email;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public String displayName() {
        return displayName;
    }

    public boolean enabled() {
        return enabled;
    }

    public Set<String> roles() {
        return roles == null ? Set.of() : Collections.unmodifiableSet(roles);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public UUID updatedBy() {
        return updatedBy;
    }
}
