# Imagen de desarrollo del frontend.
#
# El servicio 'api' corre sobre la imagen oficial sin Dockerfile; 'web' no puede
# porque necesita una cosa que solo se resuelve en tiempo de build: que
# /workspace/node_modules exista en la imagen y sea del UID del host.
#
# El porque: node_modules va en un volumen nombrado, no en el bind mount (montar
# el node_modules del host dentro del contenedor rompe los binarios nativos). Al
# crear ese volumen, Docker copia el contenido y el propietario del directorio
# que hay en la imagen; si el directorio no existe, lo crea de root y entonces
# 'npm install' falla con EACCES al correr como usuario no root.
ARG NODE_VERSION=22
FROM node:${NODE_VERSION}-alpine

ARG UID=1000
ARG GID=1000

RUN mkdir -p /workspace/node_modules && chown -R ${UID}:${GID} /workspace

WORKDIR /workspace
