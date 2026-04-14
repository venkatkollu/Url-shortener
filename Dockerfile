FROM gradle:8.7-jdk17 AS build

WORKDIR /app

COPY build.gradle settings.gradle ./
COPY gradle ./gradle

RUN gradle dependencies --no-daemon || true

COPY src ./src

RUN gradle bootJar --no-daemon -x test

# --- Runtime stage ---

FROM eclipse-temurin:17-jre-alpine

RUN apk add --no-cache curl

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=docker"]
