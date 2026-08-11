-- Invierte el sentido de 'replaced_by': ahora guarda el token que SUCEDE a esta
-- fila, no el que la precede. Decision 012.
--
-- Hasta V2 la columna apuntaba hacia atras, tal como fijaba CU-002. Eso cobraba
-- dos precios. El primero es que "este token ya fue reemplazado?" no se podia
-- responder mirando la fila que uno ya tiene en la mano: habia que preguntar
-- quien apunta a mi, una consulta extra con su indice detras, en la operacion
-- mas frecuente de toda la sesion. El segundo es que el nombre mentia: "X
-- replaced_by Y" se lee como "a X lo reemplazo Y" y significaba exactamente lo
-- contrario, que es la clase de trampa que se paga con un bug seis meses
-- despues. Invertido, la pregunta es una lectura de columna y el nombre dice la
-- verdad.
--
-- Se hace ahora porque no hay datos en produccion. Con sesiones reales encima,
-- este mismo cambio es una ventana de mantenimiento.

-- El indice se va ANTES que los datos, por dos razones independientes.
--
-- La primera es que su unico lector era 'existsByReplacedBy', que desaparece
-- con la inversion.
--
-- La segunda es que el UPDATE de abajo no podria correr con el puesto. En una
-- cadena A -> B -> C, al reescribir la fila A para que apunte a B, la fila C
-- todavia conserva su valor viejo -- que tambien es B -- y ese cruce transitorio
-- viola la unicidad. El estado final si es unico; el camino hasta el, no. Un
-- indice UNIQUE no diferible se mira fila por fila y rechazaria la migracion a
-- medio camino.
DROP INDEX IF EXISTS idx_refresh_tokens_replaced_by;

-- La subconsulta lee la instantanea previa al statement, asi que ve los valores
-- viejos aunque este escribiendo sobre la misma columna. Las filas sin sucesor
-- -- el token vivo al final de cada cadena -- reciben NULL, que es justo lo que
-- deben tener ahora: en el sentido nuevo, 'replaced_by' nulo significa "todavia
-- no me han rotado".
--
-- Si alguna subconsulta devolviera mas de una fila, Postgres aborta la
-- migracion. Eso solo puede pasar si un token fue rotado dos veces, que es la
-- corrupcion que V2 impedia; fallar aqui es la respuesta correcta.
UPDATE refresh_tokens p
   SET replaced_by = (
           SELECT c.id
             FROM refresh_tokens c
            WHERE c.replaced_by = p.id);

-- No se crea indice de reemplazo. La carrera que V2 cerraba -- el mismo token
-- rotado dos veces a la vez -- ya no la puede sostener un UNIQUE sobre esta
-- columna: las dos rotaciones escriben la MISMA fila con sucesores distintos, y
-- la segunda simplemente pisaria a la primera sin que ningun indice se entere.
-- Ahora la cierra un UPDATE condicional sobre 'replaced_by IS NULL', que bajo
-- READ COMMITTED reevalua el predicado despues de soltar el bloqueo de fila y
-- afecta cero filas. Ver AuthService.refresh.
