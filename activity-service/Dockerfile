FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/activity-service-*.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
