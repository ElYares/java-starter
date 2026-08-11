package dev.yares.starter.identity.app;

/**
 * Una cuenta de intentos y su techo.
 *
 * <p>El techo viaja con la clave porque las dos dimensiones de CU-001 E1 no
 * pueden compartirlo. Si el limite por IP fuera el mismo que el limite por
 * email, cinco fallos de una sola cuenta agotarian tambien la IP y arrastrarian
 * a todos los que salen por ese NAT — que es justo lo que el caso de uso pide
 * evitar. La cuota por IP es mas holgada porque su trabajo es otro: no atrapar
 * a quien se equivoca de contrasena, sino a quien prueba una contrasena contra
 * cien cuentas distintas.
 *
 * @param key         identifica la cuenta, con su dimension adentro
 * @param maxAttempts fallos tolerados dentro de la ventana
 */
public record AttemptBucket(String key, int maxAttempts) {
}
