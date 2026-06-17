# Alice Agent

A modular Java agent framework built with Gradle and Spock.

## project structure
See [project.tree](./project.tree) for the full project structure (sourced from Git history).

## project tech stack
See 


## Using the project: 
1. Add any dependencies to build.gradle.
2. Add logic to AliceAgent.java.

## Proj Structure
Generate Proj Structure
```bash
struct -i ".git" -i ".gradle" -i "build" -i ".idea" -i ".lazybones" -i "*.class" -i "*.jar" -i ".vscode" -i ".bevel" -i "docs/*" -i "test" -i "resources" -i "logs" -s -o project.tree 
```

## Format
Format java code:
```bash
./gradlew spotlessApply
```

## Run Tests
You can run tests with:
```bash
./gradlew check
```
Gradle HTML report is located in app/build/reports/tests.

Run the sample application with Gradle:
```bash
./gradlew :alice-bootstrap:run
```

## Building the Application
### Packaged Distribution
To package the application for a distribution to be unpacked later:
```bash
./gradlew assembleDist
````

The distribution archives are found in `app/build/distributions`

### Unpacked Application
You can assemble an "installed" unpacked application with:
```bash
./gradlew installDist
```

The application is found `bootstrap/build/install`

### GraalVM Native Image
Build a native executable (requires GraalVM + `native-image` tool):

```bash
./gradlew :alice-bootstrap:nativeCompile
```

Run the native executable directly:

```bash
./gradlew :alice-bootstrap:nativeRun
```

The native binary is located in `alice-bootstrap/build/native/nativeCompile/`.

## Running the Application
Run the application commands from the application root directory that contains `bin` and `lib` :

```bash
./bin/alice-agent 
```

## Additional Information

- [Skeletal Project](https://github.com/cbmarcum/skeletal)
- [Spock Framework](https://spockframework.org/)
- [Gradle Build Tool](https://gradle.org/)
