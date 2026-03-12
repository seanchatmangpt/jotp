package io.github.seanchatmangpt.jotp;

/**
 * Metadata about a loaded or running application.
 *
 * <p>Returned by {@link ApplicationController#loadedApplications()} and {@link
 * ApplicationController#whichApplications()}.
 *
 * <p>Erlang equivalent: the {@code {Name, Description, Vsn}} tuple in the lists returned by {@code
 * application:loaded_applications/0} and {@code application:which_applications/0}:
 *
 * <pre>{@code
 * % Erlang
 * [{kernel,"ERTS  CXC 138 10","2.8.1.3"},
 *  {stdlib,"ERTS  CXC 138 10","1.11.4.3"},
 *  {ch_app,"Channel allocator","1"}]
 *
 * // Java equivalent
 * List<ApplicationInfo> apps = ApplicationController.whichApplications();
 * // [{name="ch-app", description="Channel allocator", vsn="1"}]
 * }</pre>
 *
 * @param name the application name (matches {@link ApplicationSpec#name()})
 * @param description a human-readable description (matches {@link ApplicationSpec#description()})
 * @param vsn the version string (matches {@link ApplicationSpec#vsn()})
 * @see ApplicationController
 * @see ApplicationSpec
 */
public record ApplicationInfo(String name, String description, String vsn) {}
