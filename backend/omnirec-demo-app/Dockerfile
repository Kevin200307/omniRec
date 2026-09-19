# Build context is the repo root (see docker-compose.yml) so this stage can
# see the whole Maven reactor — omnirec-demo-app depends on its sibling
# starter modules, not just itself.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY backend/pom.xml backend/pom.xml
COPY backend/omnirec-core/pom.xml backend/omnirec-core/pom.xml
COPY backend/omnirec-web/pom.xml backend/omnirec-web/pom.xml
COPY backend/omnirec-personalize-starter/pom.xml backend/omnirec-personalize-starter/pom.xml
COPY backend/omnirec-google-recai-starter/pom.xml backend/omnirec-google-recai-starter/pom.xml
COPY backend/omnirec-redis-starter/pom.xml backend/omnirec-redis-starter/pom.xml
COPY backend/omnirec-contract-tests/pom.xml backend/omnirec-contract-tests/pom.xml
COPY backend/omnirec-demo-app/pom.xml backend/omnirec-demo-app/pom.xml
COPY backend backend
RUN mvn -f backend/pom.xml -pl omnirec-demo-app -am package -DskipTests -q

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/backend/omnirec-demo-app/target/omnirec-demo-app-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
