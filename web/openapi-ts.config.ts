import { defineConfig } from '@hey-api/openapi-ts'

/**
 * De donde sale el contrato y donde aterrizan los tipos.
 *
 * La URL por omision es la del contenedor del API dentro de la red de compose,
 * porque este comando se corre por `docker exec` sobre el contenedor de `web`
 * como todo lo demas de npm en este proyecto. Desde el host hay que apuntarlo
 * al proxy:
 *
 *     OPENAPI_URL=http://java-starter.localhost/api/openapi.json npm run api:types
 *
 * **Solo tipos, sin SDK ni cliente.** El transporte ya existe y no es
 * negociable: `shared/api/client.ts` es la instancia de Axios con la cadena de
 * interceptores —refresh primero, normalizacion a `ApiError` despues, y ese
 * orden es contrato— de la que depende CU-003 entero. Un cliente generado
 * traeria su propia instancia sin nada de eso, y cambiaria una duplicacion de
 * tipos por una duplicacion de transporte, que es peor.
 */
export default defineConfig({
  input: process.env.OPENAPI_URL ?? 'http://api:8080/api/openapi.json',
  output: {
    path: 'src/shared/api/generated',
  },
  plugins: ['@hey-api/typescript'],
})
