package io.github.seanchatmangpt.jotp;

import java.util.*;

/**
 * Application resource specification — the Java equivalent of an Erlang {@code .app} file.
 *
 * <p>In Erlang/OTP, every application has a resource file ({@code Application.app}) that defines
 * its metadata, dependencies, and startup callback. {@code ApplicationSpec} is that file as a Java
 * record:
 *
 * <pre>{@code
 * % Erlang .app file
 * {application, ch_app,
 *  [{description, "Channel allocator"},
 *   {vsn, "1"},
 *   {modules, [ch_app, ch_sup, ch3]},
 *   {registered, [ch3]},
 *   {applications, [kernel, stdlib, sasl]},
 *   {mod, {ch_app, []}},
 *   {env, [{file, "/usr/local/log"}]}
 *  ]}.
 * }</pre>
 *
 * <p><strong>Java equivalent:</strong>
 *
 * <pre>{@code
 * var spec = ApplicationSpec.builder("ch-app")
 *     .description("Channel allocator")
 *     .vsn("1")
 *     .modules("ch_app", "ch_sup", "ch3")
 *     .registered("ch3")
 *     .applications("stdlib", "sasl")
 *     .env("file", "/usr/local/log")
 *     .mod((type, args) -> {
 *         var sup = new Supervisor(Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
 *         sup.supervise("ch3", 0, channelHandler);
 *         return sup;
 *     })
 *     .build();
 * }</pre>
 *
 * @param name the application name (required)
 * @param description a human-readable description; defaults to {@code ""}
 * @param vsn the version string; defaults to {@code ""}
 * @param modules the class names introduced by this application (informational)
 * @param registered the registered process names used by this application
 * @param applications the names of applications that must be started before this one (dependencies)
 * @param env application configuration parameters, equivalent to OTP's {@code env} key
 * @param mod the application callback module; {@code null} for library applications with no startup
 * @param startArgs the start arguments passed to {@link ApplicationCallback#start}
 * @see ApplicationCallback
 * @see ApplicationController
 * @see ApplicationInfo
 */
public record ApplicationSpec(
        String name,
        String description,
        String vsn,
        List<String> modules,
        List<String> registered,
        List<String> applications,
        Map<String, Object> env,
        ApplicationCallback<?> mod,
        Object startArgs) {

    /** Compact canonical constructor — validates required fields and copies collections. */
    public ApplicationSpec {
        Objects.requireNonNull(name, "name must not be null");
        if (description == null) description = "";
        if (vsn == null) vsn = "";
        modules = modules == null ? List.of() : List.copyOf(modules);
        registered = registered == null ? List.of() : List.copyOf(registered);
        applications = applications == null ? List.of() : List.copyOf(applications);
        env = env == null ? Map.of() : Map.copyOf(env);
        if (mod == null) mod = (type, args) -> null;
    }

    /**
     * Convert this spec to an {@link ApplicationInfo} summary (name + description + vsn).
     *
     * @return an {@link ApplicationInfo} record
     */
    public ApplicationInfo toInfo() {
        return new ApplicationInfo(name, description, vsn);
    }

    /**
     * Create a fluent builder for an {@code ApplicationSpec}.
     *
     * @param name the application name
     * @return a new {@link Builder}
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** Fluent builder for {@link ApplicationSpec}. */
    public static final class Builder {
        private final String name;
        private String description = "";
        private String vsn = "";
        private final List<String> modules = new ArrayList<>();
        private final List<String> registered = new ArrayList<>();
        private final List<String> applications = new ArrayList<>();
        private final Map<String, Object> env = new LinkedHashMap<>();
        private ApplicationCallback<?> mod = (type, args) -> null;
        private Object startArgs = null;

        Builder(String name) {
            this.name = Objects.requireNonNull(name);
        }

        /** Set the human-readable description. */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /** Set the version string. */
        public Builder vsn(String vsn) {
            this.vsn = vsn;
            return this;
        }

        /** Add module names introduced by this application. */
        public Builder modules(String... moduleNames) {
            this.modules.addAll(Arrays.asList(moduleNames));
            return this;
        }

        /** Add registered process names used by this application. */
        public Builder registered(String... names) {
            this.registered.addAll(Arrays.asList(names));
            return this;
        }

        /**
         * Add application dependency names that must be started before this application.
         *
         * <p>Equivalent to the {@code applications} key in a {@code .app} file.
         */
        public Builder applications(String... deps) {
            this.applications.addAll(Arrays.asList(deps));
            return this;
        }

        /** Set an environment configuration parameter. Equivalent to the {@code env} key. */
        public Builder env(String key, Object value) {
            this.env.put(key, value);
            return this;
        }

        /**
         * Set the application callback module.
         *
         * <p>Equivalent to {@code {mod, {CallbackModule, StartArgs}}} in the {@code .app} file.
         */
        public Builder mod(ApplicationCallback<?> callback) {
            this.mod = callback;
            return this;
        }

        /** Set the start arguments passed to {@link ApplicationCallback#start}. */
        public Builder startArgs(Object args) {
            this.startArgs = args;
            return this;
        }

        /** Build the immutable {@link ApplicationSpec}. */
        public ApplicationSpec build() {
            return new ApplicationSpec(
                    name, description, vsn, modules, registered, applications, env, mod, startArgs);
        }
    }
}
