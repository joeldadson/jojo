FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build
COPY src/ src/
RUN javac src/Main.java

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=builder /build/src/*.class .
COPY data/ data/
COPY static/ static/

EXPOSE 8080

CMD sh -c "java -cp . Main --host=0.0.0.0 --port=${PORT:-8080}"
