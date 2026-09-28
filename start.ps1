    # Compiles the project and runs the single-JVM demo (metadata + 4 storage
# nodes + a scripted upload/download/verify/delete walkthrough).
mvn -q compile
mvn -q exec:java
