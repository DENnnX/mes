FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the packaged Spring Boot executable jar
COPY target/lowcode-platform-2.0.0.jar app.jar

# Expose port 8088 (safe port, avoids 80, 8080, 9000, 9001, 6379, 5432)
EXPOSE 8088

# Set SQLite data directory
ENV DB_PATH=/app/data/lowcode.db
VOLUME /app/data

ENTRYPOINT ["java", "-Dserver.port=8088", "-jar", "app.jar"]
