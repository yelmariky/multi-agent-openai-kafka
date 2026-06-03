# Build Maven en local d'abord :
#   cd .. && mvn package -DskipTests -pl invoice-service
# Puis builder l'image depuis invoice-service/ :
#   docker build -t dokeryelmariki/invoice-service:latest .
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/invoice-service-*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
