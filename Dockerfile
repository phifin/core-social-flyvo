FROM eclipse-temurin:22-jdk-jammy@sha256:d8e6ba486df17bf758888d2b1b608133d1eedca8daf69d3fc6bf78d8be81e07e AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
# Integration tests require Docker and run in the preceding CI/host verify gate.
# This packaging step does not claim to replace that gate.
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:22-jre-jammy@sha256:dbcae8b5dd4d63f81739a538ec2c09797735f04a21d814f9071b62f018326043 AS runtime
WORKDIR /app
RUN groupadd --gid 10001 socialcore && useradd --uid 10001 --gid socialcore --no-create-home socialcore
COPY --from=build --chown=10001:10001 /workspace/target/social-core-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
