# syntax=docker/dockerfile:1
FROM --platform=$BUILDPLATFORM node:24.21.0-bookworm-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6 AS frontend
WORKDIR /source/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY frontend/ ./
RUN npm test && npm run build

FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk@sha256:119a3d18f160a3e7655a66034d0f43beee31cd7b3b9142d57a5de29772011de6 AS backend
WORKDIR /source
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY backend/ backend/
COPY benchmarks/connected-review/cases-v2.json benchmarks/connected-review/validator-parity-v2.json benchmarks/connected-review/
COPY --from=frontend /source/frontend/dist/ backend/src/main/resources/web/
RUN ./gradlew --no-daemon :backend:build :backend:installDist
RUN mkdir /source/native && cd /source/native && \
    jar --extract --file /source/backend/build/install/backend/lib/sqlite-jdbc-3.53.4.0.jar \
        org/sqlite/native/Linux/aarch64/libsqlitejdbc.so

FROM eclipse-temurin:25-jre@sha256:8da0490fa9a3c26867012019565948eef0ee69438f5c75ac28146967bae984b5
WORKDIR /app
COPY --from=backend --chown=10001:10001 /source/backend/build/install/backend/ ./
COPY --from=backend --chown=10001:10001 /source/native/org/sqlite/native/Linux/aarch64/libsqlitejdbc.so /app/native/libsqlitejdbc.so
USER 10001:10001
ENV GTRAINER_HOST=0.0.0.0 GTRAINER_PORT=8080 JAVA_OPTS="-Xmx512m -Dorg.sqlite.lib.path=/app/native"
EXPOSE 8080
ENTRYPOINT ["/app/bin/backend"]
