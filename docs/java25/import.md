<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->
**Table of Contents**  *generated with [DocToc](https://github.com/thlorenz/doctoc)*

- [Java 25 Import New Features Concise Usage Guide](#java-25-import-new-features-concise-usage-guide)
  - [I. Four Official Core Features + One Preview Feature](#i-four-official-core-features--one-preview-feature)
    - [1. Grouped Bulk Imports (Stable)](#1-grouped-bulk-imports-stable)
    - [2. Unnamed Imports with `_` (Stable)](#2-unnamed-imports-with-_-stable)
    - [3. Composite Module Imports `requires import` (Stable for module-info)](#3-composite-module-imports-requires-import-stable-for-module-info)
    - [4. Built-in Implicit Imports (Stable)](#4-built-in-implicit-imports-stable)
    - [5. Type Aliases with `as` (Preview, requires `--enable-preview`)](#5-type-aliases-with-as-preview-requires---enable-preview)
  - [II. Preview Compilation Flags (Only Required for Type Aliases)](#ii-preview-compilation-flags-only-required-for-type-aliases)
  - [III. Before & After Code Comparison Examples](#iii-before--after-code-comparison-examples)
    - [Scenario 1: Importing Multiple Classes](#scenario-1-importing-multiple-classes)
    - [Scenario 2: Wildcard Package Import to Prevent Namespace Pollution](#scenario-2-wildcard-package-import-to-prevent-namespace-pollution)
    - [Scenario 3: Resolving Duplicate Class Name Conflicts](#scenario-3-resolving-duplicate-class-name-conflicts)
    - [Scenario 4: Module Dependency Imports](#scenario-4-module-dependency-imports)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

# Java 25 Import New Features Concise Usage Guide
## I. Four Official Core Features + One Preview Feature
### 1. Grouped Bulk Imports (Stable)
Syntax: `import package.{Class1, Class2, staticMethod}`
```java
// Bulk import regular classes
import java.util.{List, ArrayList, Map};
// Bulk static import
import static java.lang.Math.{abs, max};
```

### 2. Unnamed Imports with `_` (Stable)
Imports the entire package without exposing short class names; fully qualified names must be used to avoid naming collisions.
```java
import java.sql._;
// Must reference classes with full qualified name when in use: java.sql.Connection
```

### 3. Composite Module Imports `requires import` (Stable for module-info)
Declares a module dependency and automatically performs an unnamed import of its public APIs, eliminating manual import statements.
```java
module demo {
    requires import java.sql;
}
```

### 4. Built-in Implicit Imports (Stable)
Automatically loads `java.lang`, `java.util._` and `java.io._`; manual unnamed imports for these foundational packages are unnecessary.

### 5. Type Aliases with `as` (Preview, requires `--enable-preview`)
Resolves conflicts from duplicate class names and abbreviates overly long class names.
```java
import com.auth.User as AuthUser;
import java.time.LocalDateTime as LDT;
import static java.lang.Math.sqrt as sqrtNum;
```

## II. Preview Compilation Flags (Only Required for Type Aliases)
```bash
javac --enable-preview --source 25 App.java
java --enable-preview App
```

## III. Before & After Code Comparison Examples
### Scenario 1: Importing Multiple Classes
Legacy code (Java 24 and older)
```java
import java.util.List;
import java.util.Map;
import java.util.Set;
import static java.lang.Math.abs;
import static java.lang.Math.min;
```
Java 25 New Syntax
```java
import java.util.{List, Map, Set};
import static java.lang.Math.{abs, min};
```

### Scenario 2: Wildcard Package Import to Prevent Namespace Pollution
Legacy approach (wildcards pollute namespace and easily cause name collisions)
```java
import java.util.*;
// Short name List is directly available; compilation errors occur if List exists in multiple packages
```
Java 25 Unnamed Import
```java
import java.util._;
// Must use java.util.List exclusively; no namespace conflicts
```

### Scenario 3: Resolving Duplicate Class Name Conflicts
Legacy code (verbose fully qualified names mandatory)
```java
com.login.User u1 = new com.login.User();
com.biz.User u2 = new com.biz.User();
```
Java 25 Preview Type Aliases
```java
import com.login.User as LoginUser;
import com.biz.User as BizUser;
LoginUser u1 = new LoginUser();
BizUser u2 = new BizUser();
```

### Scenario 4: Module Dependency Imports
Legacy module-info.java
```java
module app {
    requires java.sql;
}
// Business code still requires manual import java.sql._;
```
Java 25 Composite Import
```java
module app {
    requires import java.sql;
}
// All classes under java.sql can be referenced via fully qualified names directly in code, no extra imports needed
```
