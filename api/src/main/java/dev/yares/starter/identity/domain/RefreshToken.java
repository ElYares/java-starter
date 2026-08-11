package dev.yares.starter.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Un refresh token vivo o historico.
 *
 * <p>Es opaco y consultable, no un JWT: un refresh JWT solo se invalida con una
 * lista negra, que es una tabla — la misma tabla, con mas pasos y sin poder
 * rotar. Ver Decision 003.
 *
 * <p>Del valor que recibio el navegador aqui solo vive su SHA-256. Una fuga de
 * la base no entrega sesiones activas.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * El token al que este <strong>sucede</strong>: apunta hacia atras, al que
     * se rotó para emitir este. Asi lo fija CU-002 ("la nueva fila tiene
     * {@code replaced_by} apuntando a la anterior").
     *
     * <p>El nombre de la columna se lee al reves de lo que guarda, y eso es una
     * trampa para quien llegue despues: "X replaced_by Y" suena a "a X lo
     * reemplazo Y", cuando aqui significa "X reemplazo a Y". Se respeta el
     * nombre porque ya esta en la migracion de HU-001 y en los criterios de
     * aceptacion; renombrarlo es una migracion, no una decision de esta rama.
     *
     * <p>Consecuencia practica: "este token ya fue reemplazado" no se responde
     * mirando esta columna, sino preguntando si alguien apunta a mi. De ahi el
     * indice de {@code V2}.
     */
    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "user_agent")
    private String userAgent;

    /**
     * {@code inet} nativo de Postgres, no {@code text}: la base valida que sea
     * una direccion y no una cadena cualquiera. Hibernate necesita que se lo
     * digan — sin {@code @JdbcTypeCode} intenta enviar un {@code varchar} y
     * Postgres rechaza la conversion implicita.
     */
    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ip")
    private String ip;

    protected RefreshToken() {
        // Requerido por JPA.
    }

    private RefreshToken(UUID id, User user, String tokenHash, Instant issuedAt,
            Instant expiresAt, UUID replacedBy, String userAgent, String ip) {
        this.id = id;
        this.user = user;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.replacedBy = replacedBy;
        this.userAgent = userAgent;
        this.ip = ip;
    }

    /** Primer token de una sesion: no sucede a ninguno. */
    public static RefreshToken issue(User user, String tokenHash, Instant issuedAt,
            Instant expiresAt, String userAgent, String ip) {
        return new RefreshToken(UUID.randomUUID(), user, tokenHash, issuedAt, expiresAt,
                null, userAgent, ip);
    }

    /** Token emitido por rotacion, que apunta al que vino antes. */
    public static RefreshToken rotatedFrom(RefreshToken previous, String tokenHash,
            Instant issuedAt, Instant expiresAt, String userAgent, String ip) {
        return new RefreshToken(UUID.randomUUID(), previous.user, tokenHash, issuedAt,
                expiresAt, previous.id, userAgent, ip);
    }

    public void revokeAt(Instant moment) {
        if (revokedAt == null) {
            revokedAt = moment;
        }
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpiredAt(Instant moment) {
        return !expiresAt.isAfter(moment);
    }

    public UUID id() {
        return id;
    }

    public User user() {
        return user;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public UUID replacedBy() {
        return replacedBy;
    }

    public String userAgent() {
        return userAgent;
    }

    public String ip() {
        return ip;
    }
}
