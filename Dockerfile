FROM eclipse-temurin:17-jre
WORKDIR /app

COPY app/build/libs/nexa-dex.jar app.jar

EXPOSE 9090

ENTRYPOINT ["java", "-jar", "app.jar"]
