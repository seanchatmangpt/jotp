# Applications

It is recommended to read this section alongside the `Application` interface and `ApplicationConfig` in the JOTP core library.

## Application Concept

After creating code to implement a specific functionality, you might consider transforming it into an **application** — a component that can be started and stopped as a unit, as well as reused in other systems.

The steps to create an application are as follows:

* Create an **application callback class** that implements the `Application` interface, describing how the application is to be started and stopped.
* Create an **application configuration** using `ApplicationConfig.builder()`, specifying which modules the application consists of, dependencies, and metadata.
* Organize the application following a Maven project structure for proper module isolation and deployment.

If you use Maven with the shade plugin (or similar packaging tools), the code for each application is placed in a separate Maven module following a pre-defined Maven standard directory structure.

## Application Callback Interface

How to start and stop the code for the application, including its supervision tree, is described by two callback methods in the `Application` interface:

```java
public interface Application {
    /**
     * Called when starting the application.
     * Creates and returns the supervision tree by starting the top supervisor.
     *
     * @param type Usually StartType.NORMAL. Has other values in takeover/failover scenarios.
     * @param args Configuration arguments defined by ApplicationConfig.args()
     * @return Result containing the top supervisor reference or an exception
     */
    Result<ProcRef<?>, Exception> start(StartType type, Object... args);

    /**
     * Called after the application has been stopped.
     * Performs any necessary cleanup.
     * Supervision tree shutdown is handled automatically.
     *
     * @param state Optional state returned from start()
     */
    void stop(Object state);
}

public enum StartType {
    /** Normal application startup */
    NORMAL,
    /** Takeover from another node (distributed systems) */
    TAKEOVER,
    /** Failover from another node (distributed systems) */
    FAILOVER
}
```

**Key points:**

* `start()` is called when starting the application and is expected to create the supervision tree by starting the top supervisor.
* It is expected to return the `ProcRef` of the top supervisor and an optional `state` object.
* `StartType` is usually `StartType.NORMAL`. It has other values only in the case of a takeover or failover in distributed systems.
* `args` is defined by the `ApplicationConfig.args()` method.
* `stop()` is called after the application has been stopped and is to do any necessary cleaning up.
* The actual stopping of the application, that is, shutting down the supervision tree, is handled automatically as described in **Starting and Stopping Applications**.

### Example Application Callback Class

Example of an application callback class for packaging the supervision tree:

```java
package com.example.myapp;

import io.github.seanchatmangpt.jotp.*;

public class ChannelApp implements Application {
    private static final Logger LOG = LoggerFactory.getLogger(ChannelApp.class);

    @Override
    public Result<ProcRef<?>, Exception> start(StartType type, Object... args) {
        try {
            // Create the top supervisor
            var supervisor = ChannelSupervisor.create();
            LOG.info("Channel application started with supervisor: {}", supervisor);
            return Result.success(supervisor);
        } catch (Exception e) {
            LOG.error("Failed to start channel application", e);
            return Result.failure(e);
        }
    }

    @Override
    public void stop(Object state) {
        LOG.info("Channel application stopped");
        // Cleanup if needed (supervisor shutdown is automatic)
    }
}
```

The actual supervisor implementation (`ChannelSupervisor`) follows standard JOTP patterns:

```java
public class ChannelSupervisor {
    public static ProcRef<?> create() {
        return Supervisor.builder()
            .childProcess(
                "channel-worker",
                state -> new ChannelWorker(),
                ONE_FOR_ONE)
            .maxRestarts(10)
            .maxRestartWindow(Duration.ofSeconds(60))
            .build();
    }
}
```

## Application Configuration

To define an application, an application configuration is created using `ApplicationConfig.builder()`:

```java
ApplicationConfig config = ApplicationConfig.builder()
    .name("channel-app")
    .version("1.0")
    .description("Channel allocator for message routing")
    .modules(List.of(
        com.example.myapp.ChannelApp.class,
        com.example.myapp.ChannelSupervisor.class,
        com.example.myapp.ChannelWorker.class))
    .registeredNames(List.of("channel_worker"))
    .dependencies(List.of("kernel", "stdlib"))
    .mod(ChannelApp.class)
    .args()
    .env("log_level", "info")
    .env("max_channels", 100)
    .build();
```

**ApplicationConfig provides:**

* `name` (required) — The name of the application as an atom. Used for identification and logging.
* `version` — Version number of the application. Defaults to "1.0".
* `description` — A short description. Defaults to empty string.
* `modules` — All classes that comprise this application. Maven/package tools use this list to identify which classes belong to the application. A module class must only be included in one application.
* `registeredNames` — All process names registered in the application (via `ProcRegistry.register()`). Build tools use this to detect name clashes between applications.
* `dependencies` — All applications that must be started before this application is started. The build tools use this list to generate correct startup order. Note that all applications have dependencies on at least "kernel" and "stdlib".
* `mod` — The callback class that implements the `Application` interface. Required for startable applications.
* `args` — Startup arguments passed to `Application.start(type, args)`.
* `env` — Configuration parameters as key-value pairs.

### Example ApplicationConfig for a Library Application

For applications without initialization logic (libraries), a minimal configuration suffices:

```java
ApplicationConfig libConfig = ApplicationConfig.builder()
    .name("my-lib")
    .version("1.0")
    .description("Utility library")
    .modules(List.of(
        com.example.mylib.Utilities.class,
        com.example.mylib.Helpers.class))
    .build();
```

For a full application with supervision tree:

```java
ApplicationConfig appConfig = ApplicationConfig.builder()
    .name("channel-app")
    .version("1.0")
    .description("Channel allocator")
    .modules(List.of(
        ChannelApp.class,
        ChannelSupervisor.class,
        ChannelWorker.class))
    .registeredNames(List.of("channel_worker", "channel_pool"))
    .dependencies(List.of("kernel", "stdlib", "sasl"))
    .mod(ChannelApp.class)
    .env("file", "/var/log/channels.log")
    .build();
```

## Directory Structure

When packaging applications using Maven, the code for each application is placed in a separate Maven module following Maven conventions.

### Development Environment Directory Structure

The recommended structure for development is:

```
${application}/
├── src/
│   ├── main/java/
│   │   └── com/example/${application}/
│   │       ├── ${Application}App.java          # Application callback
│   │       ├── ${Application}Supervisor.java  # Supervision tree
│   │       ├── ${Application}Worker.java      # Worker processes
│   │       └── ...                             # Other classes
│   ├── test/java/
│   │   └── com/example/${application}/
│   │       ├── ${Application}AppTest.java
│   │       ├── ${Application}SupervisorTest.java
│   │       └── ...
│   └── main/resources/
│       └── application.properties               # Configuration
├── doc/
│   ├── internal/                               # Implementation documentation
│   ├── examples/                               # Usage examples
│   └── src/                                    # Markdown documentation
├── priv/                                       # Application-specific assets
│   ├── lib/                                    # Native libraries (.so, .dll)
│   └── bin/                                    # Executables
├── include/                                    # Public headers (if using JNI)
├── pom.xml                                     # Maven build configuration
└── README.md                                   # Project overview
```

**Key directories:**

* `src/main/java` — **Required.** Contains the Java source code and internal include files used by the application itself. Additional sub-directories within `src/main/java` can be used as package namespaces to organize source files.
* `src/test/java` — Contains all test classes and test specifications.
* `src/main/resources` — Contains configuration files and property files.
* `priv` — **Optional.** Used for application-specific files (assets, native libraries, executables).
* `doc` — **Recommended.** Any source documentation should be placed in subdirectories here.
  * `doc/internal` — Implementation documentation not intended for publication.
  * `doc/examples` — Example source code demonstrating application usage.
  * `doc/src` — Documentation sources (Markdown, AsciiDoc, etc.).
* `include` — **Optional.** Used for public header files or JNI declarations.

**Directory naming conventions:**

* Directory names should not be capitalized.
* Use package-qualified names: `com.example.myapp` (not `MyApp`).
* It is encouraged to omit empty directories.
* If source code from other languages is used (e.g., C code for JNI), place it in a separate directory prefixed with the language name: `c_src`, `rust_src`, etc.

### Released Application Directory Structure

A released application must follow a certain structure:

```
${application}-${version}/
├── bin/                              # Executables (scripts, CLIs)
├── doc/
│   ├── html/                         # Generated HTML documentation
│   ├── pdf/                          # Generated PDF documentation
│   ├── man[1-9]/                     # Man pages
│   ├── internal/                     # Implementation docs
│   └── examples/                     # Example code
├── lib/
│   └── ${application}-${version}.jar # Compiled JAR
├── src/                              # Optional: source code for debugging
├── conf/                             # Configuration files
├── priv/
│   ├── lib/                          # Native libraries (.so, .jnilib, .dll)
│   └── bin/                          # Executables/port programs
└── README.md                         # Release notes
```

**Key points:**

* `lib/` — **Required.** Contains the compiled JAR files for the application.
* `doc/` — **Optional.** Released documentation (HTML, PDF, man pages).
* `src/` — **Optional.** Useful for debugging but not required.
* `conf/` — **Optional.** Configuration files for the release.
* `priv/lib/` — **Recommended.** Native libraries (JNI bindings, platform-specific code).
* `priv/bin/` — **Recommended.** Executables (scripts, CLIs, port programs).
* It is encouraged to omit empty directories.

## Application Controller

When a JOTP runtime system is started, a number of processes are started as part of the Kernel application. One of these processes is the **application controller** process, registered as `application_controller`.

All operations on applications are coordinated by the application controller. Use the `JotpApplicationController` class or the `Proc.ask()` mechanism to load, unload, start, and stop applications.

## Loading and Unloading Applications

Before an application can be started, it must be loaded. The application controller reads and stores the information from the `ApplicationConfig`:

```java
// Load an application
var result = JotpApplicationController.load(applicationConfig);
// result is Result<Void, ApplicationError>

// Check loaded applications
List<String> loaded = JotpApplicationController.loaded();
// Returns: ["kernel", "stdlib", "channel-app"]
```

An application that has been stopped, or has never been started, can be unloaded. The information about the application is erased from the internal database of the application controller:

```java
// Unload an application
var result = JotpApplicationController.unload("channel-app");
// result is Result<Void, ApplicationError>

// Check remaining loaded applications
List<String> loaded = JotpApplicationController.loaded();
// Returns: ["kernel", "stdlib"]
```

**Important:** Loading/unloading an application does not load/unload the compiled classes. Class loading is handled in the usual way by the Java class loader and JPMS (Java Platform Module System).

## Starting and Stopping Applications

An application is started by calling:

```java
var result = JotpApplicationController.start("channel-app");
// result is Result<Void, ApplicationError>

// Verify application is running
List<String> running = JotpApplicationController.running();
// Returns: ["kernel", "stdlib", "channel-app"]
```

If the application is not already loaded, the application controller first loads it using `JotpApplicationController.load()`. It checks the value of the `dependencies` key in the `ApplicationConfig` to ensure that all applications that are to be started before this application are running.

Following that, the application controller creates an **application master** for the application:

* The application master establishes itself as the **group leader** of all processes in the application and will forward I/O to the previous group leader.
* The purpose of the application master being the group leader is to easily keep track of which processes belong to the application. This is needed to support functions that query application membership and context, and also when stopping an application to ensure that all processes belonging to the application are terminated.
* The application master starts the application by calling the `start(type, args)` method in the callback class defined by the `mod` key in the `ApplicationConfig`.

An application is stopped (but not unloaded) by calling:

```java
var result = JotpApplicationController.stop("channel-app");
// result is Result<Void, ApplicationError>
```

The application master stops the application by telling the top supervisor to shut down:

* The top supervisor tells all its child processes to shut down.
* Child processes shut down recursively in reverse start order.
* The application master then calls the `stop(state)` method in the callback class defined by the `mod` key.

## Configuring an Application

An application can be configured using configuration parameters. These are key-value pairs specified in the `ApplicationConfig.env()` map:

```java
ApplicationConfig config = ApplicationConfig.builder()
    .name("channel-app")
    .version("1.0")
    .modules(List.of(ChannelApp.class))
    .registeredNames(List.of("channel_worker"))
    .dependencies(List.of("kernel", "stdlib"))
    .mod(ChannelApp.class)
    .env("log_level", "info")
    .env("max_channels", 100)
    .env("file", "/var/log/channels.log")
    .build();
```

The application can retrieve the value of a configuration parameter by calling:

```java
// Get a configuration parameter
Optional<Object> logLevel = JotpApplicationController.getEnv("channel-app", "log_level");
// Returns: Optional.of("info")

// Get with default value
Object value = JotpApplicationController.getEnv("channel-app", "max_channels", 50);
// Returns: 100 (or 50 if not set)
```

### System Configuration Files

The values in the `ApplicationConfig` can be overridden by values in a system configuration file. This is a file that contains configuration parameters for relevant applications:

```properties
# application.properties
channel-app.log_level=debug
channel-app.max_channels=200
channel-app.file=/tmp/channels.log
```

Or using Java properties:

```java
Properties props = new Properties();
props.load(new FileInputStream("application.properties"));

ApplicationConfig config = ApplicationConfig.builder()
    .name("channel-app")
    .version("1.0")
    // ... other config
    .fromProperties(props, "channel-app")  // Override with properties
    .build();
```

The system configuration can also be specified programmatically:

```java
Map<String, Object> systemConfig = Map.of(
    "channel-app.log_level", "debug",
    "channel-app.max_channels", 200,
    "channel-app.file", "/tmp/channels.log"
);

var result = JotpApplicationController.start(
    "channel-app",
    systemConfig);
```

### Command-Line Configuration

Configuration values can also be overridden from the command line:

```bash
java -Dchannel-app.log_level=debug \
     -Dchannel-app.max_channels=200 \
     -jar my-app.jar
```

These JVM system properties automatically override the `ApplicationConfig` environment values when loaded through `System.getProperty()`.

## Application Start Types

A start type is defined when starting the application:

```java
JotpApplicationController.start("channel-app", StartType.NORMAL);
```

If no start type is specified, it defaults to `StartType.NORMAL`. The start type can also be `StartType.TAKEOVER` or `StartType.FAILOVER`:

* **NORMAL** — Standard application startup.
* **TAKEOVER** — Another node is taking over this application (distributed systems).
* **FAILOVER** — This node is failing over from another node (distributed systems).

### Permanent, Transient, and Temporary Applications

Applications can be registered with different restart policies:

```java
// Permanent: if app terminates, entire runtime terminates
JotpApplicationController.start("channel-app", RestartPolicy.PERMANENT);

// Transient: if app terminates with normal exit, just report it.
//           if abnormally, terminate runtime
JotpApplicationController.start("channel-app", RestartPolicy.TRANSIENT);

// Temporary: if app terminates, just report it (don't restart runtime)
JotpApplicationController.start("channel-app", RestartPolicy.TEMPORARY);
```

**Details:**

* If a **permanent** application terminates, all other applications and the runtime system are also terminated.
* If a **transient** application terminates with reason `ShutdownReason.NORMAL`, this is reported but no other applications are terminated. If a transient application terminates abnormally (any reason other than normal), all other applications and the runtime system are terminated.
* If a **temporary** application terminates, this is reported but no other applications are terminated.

An application can always be stopped explicitly by calling `JotpApplicationController.stop()`. Regardless of the restart policy, stopping the application does not affect other applications.

**Note:** The transient mode is of little practical use, since when a supervision tree terminates, the termination reason is typically `ShutdownReason.SHUTDOWN`, not `ShutdownReason.NORMAL`.

---

## Complete Example: Building a JOTP Application

Here's a complete example of building, configuring, and running a JOTP application:

### Step 1: Define the Application Callback

```java
package com.example.myapp;

import io.github.seanchatmangpt.jotp.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MyApp implements Application {
    private static final Logger LOG = LoggerFactory.getLogger(MyApp.class);

    @Override
    public Result<ProcRef<?>, Exception> start(StartType type, Object... args) {
        try {
            LOG.info("Starting application with type: {}", type);
            var supervisor = MySupervisor.create(args);
            LOG.info("Application started successfully");
            return Result.success(supervisor);
        } catch (Exception e) {
            LOG.error("Application startup failed", e);
            return Result.failure(e);
        }
    }

    @Override
    public void stop(Object state) {
        LOG.info("Stopping application");
    }
}
```

### Step 2: Define the Supervision Tree

```java
package com.example.myapp;

import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;

public class MySupervisor {
    public static ProcRef<?> create(Object... args) {
        return Supervisor.builder()
            .name("my-supervisor")
            .childProcess(
                "worker-1",
                state -> new Worker("worker-1"),
                Supervisor.RestartStrategy.ONE_FOR_ONE)
            .childProcess(
                "worker-2",
                state -> new Worker("worker-2"),
                Supervisor.RestartStrategy.ONE_FOR_ONE)
            .maxRestarts(10)
            .maxRestartWindow(Duration.ofSeconds(60))
            .build();
    }
}
```

### Step 3: Create ApplicationConfig

```java
package com.example.myapp;

import io.github.seanchatmangpt.jotp.*;

public class ApplicationSetup {
    public static ApplicationConfig createConfig() {
        return ApplicationConfig.builder()
            .name("my-app")
            .version("1.0.0")
            .description("My distributed application")
            .modules(List.of(
                MyApp.class,
                MySupervisor.class,
                Worker.class))
            .registeredNames(List.of("worker-pool"))
            .dependencies(List.of("kernel", "stdlib"))
            .mod(MyApp.class)
            .env("log_level", "info")
            .env("max_workers", 100)
            .build();
    }
}
```

### Step 4: Start the Application

```java
public class Main {
    public static void main(String[] args) {
        var config = ApplicationSetup.createConfig();

        // Load the application
        var loadResult = JotpApplicationController.load(config);
        if (loadResult.isFailure()) {
            System.err.println("Failed to load application");
            System.exit(1);
        }

        // Start the application
        var startResult = JotpApplicationController.start("my-app");
        if (startResult.isFailure()) {
            System.err.println("Failed to start application");
            System.exit(1);
        }

        System.out.println("Application started successfully");

        // Application runs until explicitly stopped
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            JotpApplicationController.stop("my-app");
        }));
    }
}
```

This example demonstrates the complete lifecycle: defining a callback, creating a supervision tree, configuring the application, and starting it through the application controller.
