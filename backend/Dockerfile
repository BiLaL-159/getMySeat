# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q dependency:go-offline
COPY src/ src/
# Tests need Docker (Testcontainers) and run in CI, not inside the image build.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q package -DskipTests && cp target/getmyseat-*.jar app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 getmyseat
USER getmyseat
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
