package io.github.seanchatmangpt.jotp.messaging.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.ProcRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
/**
 * Unit tests for {@link DynamicRouter} — verifies runtime message routing and handler registration.
 *
 * <p>Tests the dynamic router pattern for late-binding message routing using ProcRegistry.
 */
@DisplayName("DynamicRouter — runtime destination resolution")
class DynamicRouterTest implements WithAssertions {
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {
        @Test
        @DisplayName("requires non-null destination resolver")
        void requiresNonNullResolver() {
            assertThatThrownBy(() -> new DynamicRouter<String>(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("destinationResolver cannot be null");
        }
        @DisplayName("creates router with valid resolver")
        void createsRouterWithValidResolver() {
            DynamicRouter<String> router = new DynamicRouter<>(msg -> "target");
            assertThat(router).isNotNull();
    @DisplayName("route(message)")
    class RouteOperation {
        @DisplayName("returns false when destination not in registry")
        void returnsFalseWhenDestinationNotFound() {
            DynamicRouter<String> router = new DynamicRouter<>(msg -> "nonexistent-service");
            boolean result = router.route("test-message");
            assertThat(result).isFalse();
        @DisplayName("requires non-null message")
        void requiresNonNullMessage() {
            assertThatThrownBy(() -> router.route(null))
                    .hasMessageContaining("message cannot be null");
        @DisplayName("resolves destination at runtime based on message")
        void resolvesDestinationAtRuntime() {
            List<String> resolvedDestinations = new ArrayList<>();
            DynamicRouter<String> router =
                    new DynamicRouter<>(
                            msg -> {
                                if (msg.startsWith("order:")) return "order-service";
                                if (msg.startsWith("payment:")) return "payment-service";
                                return "default-service";
                            });
            // Messages will not route (services don't exist in registry), but we can verify
            // resolver is called
            router.route("order:12345");
            router.route("payment:67890");
            // Verify routing was attempted
    @DisplayName("Handler Registration")
    class HandlerRegistration {
        @DisplayName("requires non-null handler")
        void requiresNonNullHandler() {
            assertThatThrownBy(() -> router.registerHandler("service", null))
                    .hasMessageContaining("handler cannot be null");
        @DisplayName("requires non-null destination name")
        void requiresNonNullDestinationName() {
            assertThatThrownBy(() -> router.registerHandler(null, msg -> {}))
                    .hasMessageContaining("destinationName cannot be null");
        @DisplayName("registers handler and invokes before routing")
        void registersHandlerAndInvokes() {
            AtomicInteger handlerCalls = new AtomicInteger(0);
            DynamicRouter<String> router = new DynamicRouter<>(msg -> "order-service");
            router.registerHandler("order-service", msg -> handlerCalls.incrementAndGet());
            router.route("test-message");
            assertThat(handlerCalls.get()).isEqualTo(1);
        @DisplayName("only invokes handler for matching destination")
        void onlyInvokesForMatchingDestination() {
            AtomicInteger orderHandlerCalls = new AtomicInteger(0);
            AtomicInteger paymentHandlerCalls = new AtomicInteger(0);
                            msg -> msg.startsWith("order:") ? "order-service" : "payment-service");
            router.registerHandler("order-service", msg -> orderHandlerCalls.incrementAndGet());
            router.registerHandler("payment-service", msg -> paymentHandlerCalls.incrementAndGet());
            assertThat(orderHandlerCalls.get()).isEqualTo(1);
            assertThat(paymentHandlerCalls.get()).isEqualTo(1);
        @DisplayName("handlerCount returns registered handler count")
        void handlerCountReturnsRegisteredCount() {
            assertThat(router.handlerCount()).isEqualTo(0);
            router.registerHandler("service-1", msg -> {});
            assertThat(router.handlerCount()).isEqualTo(1);
            router.registerHandler("service-2", msg -> {});
            assertThat(router.handlerCount()).isEqualTo(2);
            router.unregisterHandler("service-1");
        @DisplayName("unregisters handlers by destination name")
        void unregistersHandlers() {
            DynamicRouter<String> router = new DynamicRouter<>(msg -> "service");
            router.registerHandler("service", msg -> handlerCalls.incrementAndGet());
            router.route("message-1");
            router.unregisterHandler("service");
            router.route("message-2");
            assertThat(handlerCalls.get()).isEqualTo(1); // not called again
        @DisplayName("replaces existing handler on re-registration")
        void replacesExistingHandler() {
            AtomicInteger firstHandlerCalls = new AtomicInteger(0);
            AtomicInteger secondHandlerCalls = new AtomicInteger(0);
            router.registerHandler("service", msg -> firstHandlerCalls.incrementAndGet());
            router.registerHandler("service", msg -> secondHandlerCalls.incrementAndGet());
            assertThat(firstHandlerCalls.get()).isEqualTo(1);
            assertThat(secondHandlerCalls.get()).isEqualTo(1);
    @DisplayName("Integration with ProcRegistry")
    class ProcRegistryIntegration {
        @AfterEach
        void cleanup() {
            // Clean up any registered processes
            for (String name : ProcRegistry.registered()) {
                ProcRegistry.unregister(name);
            }
        @DisplayName("successfully routes to registered process")
        void successfullyRoutesToRegisteredProcess() {
            List<String> receivedMessages = new ArrayList<>();
            // Create and register a simple message handler process
            ProcRef<String, String> handlerRef =
                    Proc.spawn(
                            "message-handler",
                            () -> receivedMessages,
                            (state, msg) -> {
                                state.add(msg);
                                return state;
            DynamicRouter<String> router = new DynamicRouter<>(msg -> "message-handler");
            // Route messages
            boolean result1 = router.route("first-message");
            boolean result2 = router.route("second-message");
            assertThat(result1).isTrue();
            assertThat(result2).isTrue();
            // Give virtual thread time to process messages
            Thread.yield();
            // Verify messages were received
            assertThat(receivedMessages).hasSize(2).contains("first-message", "second-message");
            ProcRegistry.unregister("message-handler");
        @DisplayName("dynamic resolution routes to different destinations")
        void dynamicResolutionRoutesDifferentDestinations() {
            List<String> orderMessages = new ArrayList<>();
            List<String> paymentMessages = new ArrayList<>();
            ProcRef<String, String> orderRef =
                            "order-service",
                            () -> orderMessages,
            ProcRef<String, String> paymentRef =
                            "payment-service",
                            () -> paymentMessages,
            router.route("order:54321");
            assertThat(orderMessages).contains("order:12345", "order:54321");
            assertThat(paymentMessages).contains("payment:67890");
            ProcRegistry.unregister("order-service");
            ProcRegistry.unregister("payment-service");
}
