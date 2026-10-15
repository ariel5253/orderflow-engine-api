# Build stage: the Maven Wrapper on JDK 21. Tests are not run here; ./mvnw verify is the test command.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# unzip lets the wrapper fetch the .zip distribution, whose SHA-256 is pinned in maven-wrapper.properties.
RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip \
 && rm -rf /var/lib/apt/lists/*

# Dependency cache layer: rebuilt only when the wrapper or the POM change.
COPY --chmod=0755 mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./
RUN ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

COPY src/ src/
RUN ./mvnw --batch-mode --no-transfer-progress -Dmaven.test.skip=true package \
 && java -Djarmode=tools -jar target/orderflow-engine-api.jar extract --layers --launcher --destination target/extracted

# Run stage: Java 21 runtime as a non-root user, one image layer per Spring Boot layer.
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./
USER app
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
