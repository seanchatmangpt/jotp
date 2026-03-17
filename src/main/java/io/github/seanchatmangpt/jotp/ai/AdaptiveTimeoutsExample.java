package io.github.seanchatmangpt.jotp.ai;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Result;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Practical examples of using AdaptiveTimeouts with JOTP processes.
 *
 * <p>Demonstrates:
 * <ol>
 *   <li><strong>Service Client Pattern:</strong> Wraps async service calls with adaptive
 *       timeouts
 *   <li><strong>Process Communication:</strong> Integrates adaptive timeouts with Proc.ask()
 *   <li><strong>Failure Recovery:</strong> Combines adaptive timeouts with Result and
 *       CrashRecovery
 *   <li><strong>Multi-Service Load Balancing:</strong> Adjusts timeouts per-service based on
 *       observed behavior
 * </ol>
 *
 * <p><strong>Real-world scenario:</strong> A payment gateway needs to call multiple backend
 * services (auth, inventory, billing). Some services are consistently fast, others are slow but
 * stable, and a few are flaky. Rather than using a single timeout for all services, adaptive
 * timeouts learn the optimal value for each.
 */
public class AdaptiveTimeoutsExample {

  /**
   * Service Client with adaptive timeouts.
   *
   * <p>Tracks response times from a remote service and adjusts timeouts dynamically.
   */
  public static class AdaptiveServiceClient {
    private final String serviceName;
    private final AdaptiveTimeouts timeouts;
    private final RemoteService service;

    public AdaptiveServiceClient(
        String serviceName, AdaptiveTimeouts timeouts, RemoteService service) {
      this.serviceName = serviceName;
      this.timeouts = timeouts;
      this.service = service;
    }

    /**
     * Call the remote service with adaptive timeout.
     *
     * <p>Records response time for timeout tuning.
     *
     * @param request the request to send
     * @return result containing response or error
     */
    public Result<String, Exception> callWithAdaptiveTimeout(String request) {
      long startNanos = System.nanoTime();

      try {
        Duration timeout = timeouts.getTimeout(serviceName);
        String response = service.call(request, timeout);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        timeouts.recordResponse(serviceName, elapsedMs, true);
        return Result.ok(response);
      } catch (java.util.concurrent.TimeoutException e) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        timeouts.recordResponse(serviceName, elapsedMs, false);
        return Result.err(e);
      } catch (Exception e) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        timeouts.recordResponse(serviceName, elapsedMs, true); // Count as success for timing
        return Result.err(e);
      }
    }
  }

  /**
   * Process-based request handler with adaptive timeouts.
   *
   * <p>Shows how to integrate adaptive timeouts with JOTP's Proc.ask() pattern.
   *
   * <p>Example: A payment processor that calls multiple backend services.
   */
  public static class PaymentProcessor {
    private final AdaptiveTimeouts timeouts;
    private final ProcRef<?, ?> authService;
    private final ProcRef<?, ?> inventoryService;
    private final ProcRef<?, ?> billingService;

    public PaymentProcessor(
        AdaptiveTimeouts timeouts,
        ProcRef<?, ?> authService,
        ProcRef<?, ?> inventoryService,
        ProcRef<?, ?> billingService) {
      this.timeouts = timeouts;
      this.authService = authService;
      this.inventoryService = inventoryService;
      this.billingService = billingService;
    }

    /**
     * Process a payment with adaptive timeouts per service.
     *
     * @param userId user ID
     * @param orderId order ID
     * @param amount payment amount in cents
     * @return payment result
     */
    public Result<PaymentResult, PaymentError> processPayment(String userId, String orderId, long amount) {
      // Step 1: Authenticate user with adaptive timeout
      Duration authTimeout = timeouts.getTimeout("auth-service");
      long authStartNanos = System.nanoTime();

      var authResult = authenticate(userId, authTimeout);
      long authElapsedMs = (System.nanoTime() - authStartNanos) / 1_000_000;
      timeouts.recordResponse("auth-service", authElapsedMs, authResult.isSuccess());

      if (authResult.isError()) {
        return Result.err(new PaymentError("auth_failed", authResult.fold(x -> x, e -> e.toString())));
      }

      // Step 2: Check inventory with adaptive timeout
      Duration inventoryTimeout = timeouts.getTimeout("inventory-service");
      long inventoryStartNanos = System.nanoTime();

      var inventoryResult = checkInventory(orderId, inventoryTimeout);
      long inventoryElapsedMs = (System.nanoTime() - inventoryStartNanos) / 1_000_000;
      timeouts.recordResponse("inventory-service", inventoryElapsedMs, inventoryResult.isSuccess());

      if (inventoryResult.isError()) {
        return Result.err(
            new PaymentError("inventory_check_failed", inventoryResult.fold(x -> x, e -> e.toString())));
      }

      // Step 3: Process billing with adaptive timeout
      Duration billingTimeout = timeouts.getTimeout("billing-service");
      long billingStartNanos = System.nanoTime();

      var billingResult = processBilling(userId, orderId, amount, billingTimeout);
      long billingElapsedMs = (System.nanoTime() - billingStartNanos) / 1_000_000;
      timeouts.recordResponse("billing-service", billingElapsedMs, billingResult.isSuccess());

      if (billingResult.isError()) {
        return Result.err(
            new PaymentError("billing_failed", billingResult.fold(x -> x, e -> e.toString())));
      }

      return Result.ok(
          new PaymentResult(
              orderId,
              amount,
              "success",
              authElapsedMs + inventoryElapsedMs + billingElapsedMs));
    }

    private Result<String, Exception> authenticate(String userId, Duration timeout) {
      // In real code, call authService with timeout
      return Result.ok("token-" + userId);
    }

    private Result<String, Exception> checkInventory(String orderId, Duration timeout) {
      // In real code, call inventoryService with timeout
      return Result.ok("inventory-ok");
    }

    private Result<String, Exception> processBilling(String userId, String orderId, long amount, Duration timeout) {
      // In real code, call billingService with timeout
      return Result.ok("transaction-id-123");
    }
  }

  /**
   * Demonstrates timeout monitoring and alerting.
   *
   * <p>Periodically checks if services are under stress (high timeout rate) and logs alerts.
   */
  public static class TimeoutMonitor implements Runnable {
    private final AdaptiveTimeouts timeouts;
    private final TimeoutAlert alertHandler;

    public TimeoutMonitor(AdaptiveTimeouts timeouts, TimeoutAlert alertHandler) {
      this.timeouts = timeouts;
      this.alertHandler = alertHandler;
    }

    @Override
    public void run() {
      var allStats = timeouts.getAllStats();

      for (var stats : allStats.values()) {
        // Alert if service is under stress (> 1% timeout rate)
        if (stats.isUnderStress()) {
          alertHandler.alertHighTimeoutRate(
              stats.serviceName(),
              stats.totalTimeouts(),
              stats.totalRequests(),
              stats.currentTimeoutMs());
        }

        // Alert if timeout is at max bound (may need higher bound)
        if (stats.currentTimeoutMs() >= 30000) {
          alertHandler.alertTimeoutAtBound(
              stats.serviceName(),
              stats.p999Ms(),
              stats.currentTimeoutMs());
        }

        // Alert if jitter is high (service becoming unstable)
        if (stats.jitterRatio() > 2.0) {
          alertHandler.alertHighJitter(
              stats.serviceName(),
              stats.p50Ms(),
              stats.p99Ms(),
              stats.jitterRatio());
        }
      }
    }
  }

  /** Callback interface for timeout alerts. */
  public interface TimeoutAlert {
    void alertHighTimeoutRate(String serviceName, long timeouts, long total, long currentTimeout);

    void alertTimeoutAtBound(String serviceName, long p999Ms, long currentTimeout);

    void alertHighJitter(String serviceName, long p50Ms, long p99Ms, double jitterRatio);
  }

  /** Stub implementations for examples. */

  public interface RemoteService {
    String call(String request, Duration timeout) throws java.util.concurrent.TimeoutException;
  }

  public record PaymentResult(String orderId, long amount, String status, long elapsedMs) {}

  public record PaymentError(String code, String message) {}

  // --- Example usage ---

  public static void exampleBasicUsage() {
    // Create adaptive timeout manager
    var timeouts = AdaptiveTimeouts.create();

    // Create service client with adaptive timeouts
    var mockService = (RemoteService) (req, timeout) -> "response-" + req;
    var client = new AdaptiveServiceClient("payment-api", timeouts, mockService);

    // Make requests and observe timeout tuning
    for (int i = 0; i < 100; i++) {
      var result = client.callWithAdaptiveTimeout("request-" + i);
      System.out.println("Request " + i + ": " + result);

      // Print current timeout every 10 requests
      if (i % 10 == 0) {
        var stats = timeouts.getStats("payment-api");
        System.out.println(
            "Timeout stats: p99=" + stats.p99Ms() + "ms, current="
                + stats.currentTimeoutMs() + "ms");
      }
    }

    timeouts.shutdown();
  }

  public static void examplePaymentProcessing() {
    var timeouts = AdaptiveTimeouts.create();

    // In real code, these would be actual JOTP processes
    var paymentProcessor = new PaymentProcessor(
        timeouts,
        null, // authService ProcRef
        null, // inventoryService ProcRef
        null); // billingService ProcRef

    // Process payments
    for (int i = 0; i < 50; i++) {
      var result = paymentProcessor.processPayment("user-123", "order-" + i, 9999);
      System.out.println("Payment " + i + ": " + result);
    }

    // Print comprehensive statistics
    System.out.println("\nFinal timeout statistics:");
    for (var stats : timeouts.getAllStats().values()) {
      System.out.println(
          stats.serviceName()
              + ": p50="
              + stats.p50Ms()
              + "ms, p99="
              + stats.p99Ms()
              + "ms, p999="
              + stats.p999Ms()
              + "ms, timeout="
              + stats.currentTimeoutMs()
              + "ms");
    }

    timeouts.shutdown();
  }
}
