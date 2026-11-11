# Build stage: compile and package the service
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
# A Windows checkout can turn the wrapper's line endings into CRLF, which Linux can't run
# ("./mvnw: not found"). Strip them here so the image builds from any machine.
RUN sed -i 's/\r$//' mvnw .mvn/wrapper/maven-wrapper.properties && chmod +x mvnw
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
