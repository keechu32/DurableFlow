FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl && addgroup -S durableflow && adduser -S -G durableflow durableflow
WORKDIR /app
ARG MODULE=durableflow-api
COPY --from=build --chown=durableflow:durableflow /workspace/${MODULE}/target/${MODULE}-0.1.0-SNAPSHOT-exec.jar app.jar
USER durableflow
ENV TZ=UTC
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
