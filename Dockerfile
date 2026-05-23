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
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# Non-root user.
RUN groupadd -r app && useradd -r -g app -d /app app && chown -R app:app /app
USER app

COPY --from=build /workspace/target/membership.jar /app/membership.jar

ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/membership.jar"]
