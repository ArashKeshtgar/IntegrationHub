FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q -DskipTests -Dspotless.check.skip=true package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 hub
USER hub
WORKDIR /app
COPY --from=build /src/target/integrationhub-*.jar app.jar
EXPOSE 5700
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
