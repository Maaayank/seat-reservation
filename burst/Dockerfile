# syntax=docker/dockerfile:1
# Burst tool image. Build from the repo root:
#   docker build -f burst/Dockerfile -t seat-burst .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw ./
COPY burst/pom.xml burst/pom.xml
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -f burst/pom.xml dependency:go-offline
COPY burst/src/ burst/src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -f burst/pom.xml package

FROM eclipse-temurin:21-jre
COPY --from=build /workspace/burst/target/burst.jar /burst.jar
ENTRYPOINT ["java", "-jar", "/burst.jar"]
