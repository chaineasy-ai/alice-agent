# Alice Agent

A modular Java agent framework built with Gradle and Spock.

See [project.tree](./project.tree) for the full project structure (sourced from Git history).

## Using the project: 
1. Add any dependencies to build.gradle.
2. Add logic to AliceAgent.java.

## Format
Format java code:
```
./gradlew spotlessApply
```

## Run Tests
You can run tests with:
```
./gradlew check
```
Gradle HTML report is located in app/build/reports/tests.

Run the sample application with Gradle:
```
./gradlew :app:run
```

## Building the Application
### Packaged Distribution
To package the application for a distribution to be unpacked later:
```
./gradlew assembleDist
````

The distribution archives are found in `app/build/distributions`

### Unpacked Application
You can assemble an "installed" unpacked application with:
```
./gradlew installDist
```

The application is found `app/build/install`

## Running the Application
Run the application commands from the application root directory that contains `bin` and `lib` :

```
./bin/alice-agent 
```

## Additional Information

- [Skeletal Project](https://github.com/cbmarcum/skeletal)
- [Spock Framework](https://spockframework.org/)
- [Gradle Build Tool](https://gradle.org/)
