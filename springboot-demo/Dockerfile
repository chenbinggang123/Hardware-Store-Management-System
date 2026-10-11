# syntax=docker/dockerfile:1

FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
# Maven Central can temporarily rate-limit shared deployment runners. Keep the
# repository between builds and retry transient download failures without
# running a separate dependency:go-offline pass (which doubles remote traffic).
RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked \
    for attempt in 1 2 3 4 5; do \
      mvn -B -U -DskipTests \
        -Dmaven.wagon.http.retryHandler.count=5 \
        -Dmaven.wagon.httpconnectionManager.ttlSeconds=60 \
        package && exit 0; \
      if [ "$attempt" -eq 5 ]; then exit 1; fi; \
      sleep $((attempt * 10)); \
    done

FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /workspace/target/springboot-demo-0.0.1-SNAPSHOT.jar app.jar
ENV SERVER_PORT=80
EXPOSE 80
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
