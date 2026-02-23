# Build stage
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

COPY pom.xml .
COPY src ./src

RUN apk add --no-cache maven && \
    mvn clean package -DskipTests -q

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S batch && adduser -S batch -G batch

WORKDIR /app

COPY --from=build /build/target/*.jar app.jar

RUN mkdir -p /tmp/roubometro && chown batch:batch /tmp/roubometro

USER batch

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
