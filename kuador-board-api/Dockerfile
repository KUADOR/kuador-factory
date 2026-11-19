# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY mvnw ./
COPY .mvn .mvn
COPY pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package \
    && java -Djarmode=tools -jar target/kuador-board-api-*.jar extract --layers --launcher --destination target/extracted

FROM eclipse-temurin:25-jre
# pebble (administrador de servicios que trae la base de Ubuntu) no lo usa la API y arrastra fallas de su Go: fuera.
RUN rm -f /usr/bin/pebble \
    && groupadd --system board && useradd --system --gid board --no-create-home board
WORKDIR /app
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./
USER board
# En el contenedor escucha en todas las interfaces; quién lo alcanza lo decide la red de Dokploy.
ENV PORT=8091 SERVER_ADDRESS=0.0.0.0
EXPOSE 8091
# Toda la configuración llega por variables de entorno (ver .env.example y docs/despliegue.md).
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
