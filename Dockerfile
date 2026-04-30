FROM amazoncorretto:21-alpine

WORKDIR /app

# Copy everything from root
COPY . .

# Ensure data directory exists
RUN mkdir -p data

# Compile the app
RUN javac -d out/production/bookstore-web src/Main.java

# Run using the dynamic Render PORT
CMD sh -c "java -cp out/production/bookstore-web Main --host=0.0.0.0 --port=${PORT:-10000}"
