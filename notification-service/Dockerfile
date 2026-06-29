# Build Maven en local d'abord :
#   cd .. && mvn package -DskipTests -pl notification-service
# Puis builder l'image depuis notification-service/ :
#   docker build -t dokeryelmariki/notification-service:latest .
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/notification-service-*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
