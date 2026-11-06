# syntax=docker/dockerfile:1
# RecyBook : application Angular servie par un serveur Express (SQLite intégré à Node).
#
#   docker build -t recybook .
#   docker run -d -p 8080:3000 -v recybook-data:/app/data --name recybook recybook
#
# Au premier démarrage, la base est initialisée avec les 37 livres de seed/.

# ---- 1. Compilation de l'application Angular ----
FROM node:22-alpine AS client
WORKDIR /app/client
COPY client/package.json client/package-lock.json ./
RUN npm ci
COPY client/ ./
RUN npx ng build

# ---- 2. Compilation du serveur Express (TypeScript -> JavaScript) ----
FROM node:22-alpine AS server
WORKDIR /app/server
COPY server/package.json server/package-lock.json ./
RUN npm ci
COPY server/ ./
RUN npm run build && npm prune --omit=dev

# ---- 3. Image finale : uniquement ce qui sert à l'exécution ----
FROM node:22-alpine
ENV NODE_ENV=production \
    PORT=3000 \
    RECYBOOK_DATA=/app/data \
    NODE_OPTIONS=--disable-warning=ExperimentalWarning
WORKDIR /app
COPY server/package.json server/
COPY --from=server /app/server/node_modules server/node_modules
COPY --from=server /app/server/dist server/dist
COPY --from=client /app/client/dist client/dist
COPY seed seed
RUN mkdir -p /app/data && chown node:node /app/data
USER node
# Base SQLite et images des livres : à conserver entre deux conteneurs
VOLUME /app/data
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
  CMD wget -qO- "http://127.0.0.1:${PORT}/api/stats" > /dev/null || exit 1
CMD ["sh", "-c", "node server/dist/seed.js --if-empty && exec node server/dist/index.js"]
