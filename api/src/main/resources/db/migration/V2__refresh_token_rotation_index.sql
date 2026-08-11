-- Indice que sostiene la deteccion de reuso de CU-002.
--
-- 'replaced_by' guarda el token *anterior*, no el siguiente (asi lo fija
-- CU-002). Por eso la pregunta "este token ya fue reemplazado?" se responde
-- buscando quien apunta a el, y sin indice eso es un recorrido de tabla en cada
-- refresh: la operacion mas frecuente de toda la sesion.
--
-- Es UNIQUE y no un indice comun a proposito. Dos filas apuntando al mismo
-- predecesor significa que el mismo refresh token se roto dos veces, que es
-- justo la condicion de carrera de CU-002 A1 cuando cinco peticiones fallan a
-- la vez. La promesa compartida del interceptor lo evita en el cliente; esto lo
-- vuelve imposible en la base, que es donde las garantias aguantan.
--
-- Parcial porque el primer token de cada sesion tiene 'replaced_by' nulo y hay
-- muchos: sin el WHERE, todos chocarian entre si.
CREATE UNIQUE INDEX idx_refresh_tokens_replaced_by
    ON refresh_tokens (replaced_by)
    WHERE replaced_by IS NOT NULL;
