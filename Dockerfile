FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package \
    && java -Djarmode=tools -jar target/relay-agents-*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S relay && adduser -S -G relay -u 10001 relay
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001
EXPOSE 7790
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -qO- http://localhost:7790/actuator/health/readiness | grep -q UP || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
