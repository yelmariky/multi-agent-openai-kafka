FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache tesseract-ocr poppler-utils
WORKDIR /app
COPY target/expense-service-*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
