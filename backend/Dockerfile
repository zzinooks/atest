# 빌드: docker build -t fgc-backend backend/   (먼저 ./gradlew bootJar 또는 gradle bootJar)
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 fgc
WORKDIR /app
COPY build/libs/fgc-backend-*.jar app.jar
USER fgc
EXPOSE 8080
# 무료 VM(1GB RAM급)에서도 동작하도록 힙 제한
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
