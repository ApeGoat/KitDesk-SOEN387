# Build stage: compile and package the service
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B package -DskipTests

# Run stage: only the JRE and the jar
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system kitdesk
USER kitdesk
COPY --from=build /app/target/kitdesk-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
