# ---- build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY src/main ./src/main
RUN mkdir -p out && javac -d out $(find src/main -name '*.java') && jar cfe lb.jar lb.Main -C out .

# ---- runtime stage (same image runs the LB, the demo backends and the benchmark) ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/lb.jar /app/lb.jar
EXPOSE 8080 9100
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75"]
CMD ["-jar", "/app/lb.jar"]
