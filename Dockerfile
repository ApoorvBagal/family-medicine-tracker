# ---- Stage 1: build the jar (full JDK + Maven, discarded afterwards) ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Dependencies first: this layer is cached until pom.xml changes, so rebuilds are fast.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

# Frontend sources (node/ and node_modules/ are excluded by .dockerignore; the Maven build downloads Node
# and runs `npm ci` + `npm run build` into target/classes/static).
COPY frontend frontend
COPY src src
# Tests already ran in CI (they need Docker, which isn't available inside this build).
RUN ./mvnw -B -ntp -DskipTests package

# ---- Stage 2: small runtime image (JRE only, non-root) ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/target/app.jar app.jar
USER app

# Tuned for a 512 MB free-tier instance with a slow shared CPU:
#  - MaxRAMPercentage: heap sized from the container limit, leaving room for metaspace/threads
#  - SerialGC: lowest memory overhead for a single small instance
#  - TieredStopAtLevel=1: faster startup (matters for cold starts) at a small peak-throughput cost
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
