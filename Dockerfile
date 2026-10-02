FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/seat-reservation-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["sh","-c","java -XX:MaxRAMPercentage=75 -jar app.jar"]
