# Imagen del backend para publicarlo en la nube (Render, AWS, etc.).
# 1) Compila con Maven y Java 21.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# 2) Imagen final, solo con Java para ejecutar.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/fidegresa-backend-0.0.1-SNAPSHOT.jar app.jar
# Memoria pensada para planes gratuitos (512 MB).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
