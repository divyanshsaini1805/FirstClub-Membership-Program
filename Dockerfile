# syntax=docker/dockerfile:1.6
# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# Cache deps separately from source changes.
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

# ---------- runtime stage ----------
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# Non-root user (Alpine busybox addgroup/adduser — avoids Debian shadow-utils segfault on some WSL2 kernels).
RUN addgroup -S app && adduser -S -G app -h /app app && chown -R app:app /app
USER app

COPY --from=build /workspace/target/membership.jar /app/membership.jar

ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/membership.jar"]
