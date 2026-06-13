---
title: "Alice Agent"
summary: "A modular Java agent framework built with Gradle and Spock"
read_when:
  - "getting started with the project"
  - "understanding the project structure and build process"
  - "running the application or tests"
scope:
  - "alice-bootstrap"
  - "alice-core-agent"
  - "alice-core-planner"
  - "alice-model"
  - "alice-env-adapter"
  - "alice-tool-gateway"
  - "alice-guardrail"
  - "alice-memory-vault"
  - "alice-agent-command"
  - "alice-facade-cmd"
  - "alice-facade-tui"
status: "active"
updated: "2026-06-13"
---
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
./gradlew :alice-bootstrap:run
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
