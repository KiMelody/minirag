# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/minirag-*.jar app.jar
RUN mkdir -p /app/data
EXPOSE 18090
ENV JAVA_OPTS=-Xmx192m
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
