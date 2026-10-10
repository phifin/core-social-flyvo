FROM eclipse-temurin:25-jdk-jammy@sha256:318f90a80337fb6f26b025dbec0a95fa6f48abf60ce129babf555618356bdbf8 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
# Integration tests require Docker and run in the preceding CI/host verify gate.
# This packaging step does not claim to replace that gate.
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre-jammy@sha256:25777acfabf927084b7ef46d8bc786b6203c8c344f56541054238b8c4fe73db9 AS runtime
WORKDIR /app
RUN groupadd --gid 10001 socialcore && useradd --uid 10001 --gid socialcore --no-create-home socialcore
COPY --from=build --chown=10001:10001 /workspace/target/social-core-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
