FROM amazoncorretto:21-alpine

WORKDIR /app

# Copy all source code and static/data folders
COPY . .

# Ensure data directory exists so the app doesn't crash on startup
RUN mkdir -p data

# Compile the Java application
RUN javac -d out/production/bookstore-web src/Main.java

# Revert out of the application root and use `sh -c` to pass Render's dynamic PORT variable
CMD sh -c "java -cp out/production/bookstore-web Main --host=0.0.0.0 --port=${PORT:-10000}"
