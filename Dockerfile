FROM node:22-slim AS web
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build
FROM eclipse-temurin:17-jdk AS jar
WORKDIR /src
COPY backend/ ./
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew bootJar --no-daemon
FROM eclipse-temurin:17-jre
RUN useradd --system songrush && mkdir -p /app/run
WORKDIR /app/run
COPY --from=jar /src/build/libs/backend-0.0.1-SNAPSHOT.jar /app/songrush.jar
COPY --from=web /src/dist /app/web
USER songrush
ENV SONGRUSH_DATA_DIR=/data \
    SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:/app/web/ \
    SPRING_WEB_RESOURCES_CACHE_CACHECONTROL_NO_CACHE=true
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx384m", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/songrush.jar"]