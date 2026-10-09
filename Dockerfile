FROM eclipse-temurin:21-jdk-jammy@sha256:e0c60c487345d1dc9d0fc7b6f0496f3cc941e5132e09296cc17a6decc71b902b AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
# Integration tests require Docker and run in the preceding CI/host verify gate.
# This packaging step does not claim to replace that gate.
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b AS runtime
WORKDIR /app
RUN groupadd --gid 10001 socialcore && useradd --uid 10001 --gid socialcore --no-create-home socialcore
COPY --from=build --chown=10001:10001 /workspace/target/social-core-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
