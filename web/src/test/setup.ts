import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/vue'
import { afterEach } from 'vitest'

// Sin `globals: true` no hay un `afterEach` global del que Testing Library
// pueda colgar su limpieza, asi que el desmontaje va explicito. Sin esto los
// componentes de un test siguen montados durante el siguiente y las consultas
// encuentran de mas: el sintoma es una prueba que pasa sola y falla en suite.
afterEach(cleanup)
