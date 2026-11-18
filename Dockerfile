FROM node:22-slim AS web
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build
# The Flutter mobile web build, served at /m/ (cycle 154, D-140). Flutter is pinned by its git tag.
# --no-web-resources-cdn bundles CanvasKit under /m/canvaskit/ instead of loading it from www.gstatic.com (cycle 155).
FROM debian:bookworm-slim AS mobile
RUN apt-get update \
    && apt-get install -y --no-install-recommends git curl unzip xz-utils ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch 3.50.0 https://github.com/flutter/flutter.git /opt/flutter
ENV PATH=/opt/flutter/bin:$PATH
RUN flutter config --no-analytics --no-cli-animations && flutter precache --web
WORKDIR /src
COPY mobile/pubspec.yaml mobile/pubspec.lock ./
COPY mobile/packages/ ./packages/
RUN flutter pub get
COPY mobile/ ./
RUN flutter build web --release --base-href /m/ --no-web-resources-cdn
FROM eclipse-temurin:17-jdk AS jar
WORKDIR /src
COPY backend/ ./
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew bootJar --no-daemon
FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 songrush && mkdir -p /app/run
WORKDIR /app/run
COPY --from=jar /src/build/libs/backend-0.0.1-SNAPSHOT.jar /app/songrush.jar
COPY --from=web /src/dist /app/web
COPY --from=mobile /src/build/web /app/web/m
USER songrush
ENV SONGRUSH_DATA_DIR=/data \
    SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:/app/web/ \
    SPRING_WEB_RESOURCES_CACHE_CACHECONTROL_NO_CACHE=true
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx384m", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/songrush.jar"]
