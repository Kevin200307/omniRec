# syntax=docker/dockerfile:1
# Build context is the repo root (see docker-compose.yml) so this stage can see
# the whole Maven reactor — the app depends on its sibling modules, not just itself.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# POMs first so the dependency layer caches independently of source changes.
COPY backend/pom.xml backend/pom.xml
COPY backend/omnirec-commerce-core/pom.xml backend/omnirec-commerce-core/pom.xml
COPY backend/omnirec-event-api/pom.xml backend/omnirec-event-api/pom.xml
COPY backend/omnirec-event-processing/pom.xml backend/omnirec-event-processing/pom.xml
COPY backend/omnirec-amazon-personalize-destination/pom.xml backend/omnirec-amazon-personalize-destination/pom.xml
COPY backend/omnirec-google-retail-destination/pom.xml backend/omnirec-google-retail-destination/pom.xml
COPY backend/omnirec-event-storage/pom.xml backend/omnirec-event-storage/pom.xml
COPY backend/omnirec-event-api-app/pom.xml backend/omnirec-event-api-app/pom.xml

COPY backend backend
# The cache mount keeps ~/.m2 between builds, so a rebuild downloads nothing new.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f backend/pom.xml -pl omnirec-event-api-app -am package -DskipTests -q -B

FROM eclipse-temurin:17-jre
WORKDIR /app

# Don't run as root: this process holds the provider credentials, so it is the
# one container in the stack where a container escape would actually matter.
RUN useradd --system --uid 10001 omnirec
USER omnirec

COPY --from=build /build/backend/omnirec-event-api-app/target/omnirec-event-api-app-*.jar app.jar
EXPOSE 8081

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD ["sh", "-c", "wget -qO- http://localhost:8081/actuator/health || exit 1"]

ENTRYPOINT ["java", "-jar", "app.jar"]
