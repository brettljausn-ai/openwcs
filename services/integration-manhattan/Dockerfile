# Build the boot jar first:  ./gradlew :services:integration-manhattan:bootJar
FROM eclipse-temurin:21-jre
WORKDIR /app
# Daily per-service log files go to the shared openwcs-logs volume (System info screen).
ENV OPENWCS_LOG_DIR=/var/log/openwcs
COPY build/libs/*.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]
