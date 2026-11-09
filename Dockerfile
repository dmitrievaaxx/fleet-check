FROM gradle:8-jdk21 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /srv
COPY --from=build /src/build/libs/*.jar app.jar
ENV PORT=8000
CMD ["java", "-jar", "app.jar"]
