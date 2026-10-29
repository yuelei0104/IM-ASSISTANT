# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
ARG MAVEN_MIRROR_URL=https://repo.maven.apache.org/maven2
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    set -eu; \
    printf '<settings><mirrors><mirror><id>release-mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>\n' "$MAVEN_MIRROR_URL" > /tmp/maven-settings.xml; \
    chmod +x mvnw; \
    ./mvnw -B -ntp -s /tmp/maven-settings.xml -pl im-user-service,im-chat-service,im-gateway -am package -DskipTests
RUN mkdir -p /health && javac --release 21 -d /health deploy/HealthProbe.java

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /health /app/health
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Dfile.encoding=UTF-8"
USER 10001:10001

FROM runtime AS user
COPY --from=build /src/im-user-service/target/im-user-service-0.0.1-SNAPSHOT.jar /app/service.jar
ENTRYPOINT ["java", "-jar", "/app/service.jar"]

FROM runtime AS chat
COPY --from=build /src/im-chat-service/target/im-chat-service-0.0.1-SNAPSHOT.jar /app/service.jar
ENTRYPOINT ["java", "-jar", "/app/service.jar"]

FROM runtime AS gateway
COPY --from=build /src/im-gateway/target/im-gateway-0.0.1-SNAPSHOT.jar /app/service.jar
ENTRYPOINT ["java", "-jar", "/app/service.jar"]
