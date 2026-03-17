package io.github.seanchatmangpt.jotp.crash;

import java.time.Duration;

/**
 * A specific recovery recommendation based on crash dump analysis.
 *
 * <p>Each recommendation includes a suggested recovery type, priority, estimated time, and success
 * probability to guide recovery decisions.
 *
 * @param title short title of the recommendation
 * @param description detailed description of the recommendation
 * @param suggestedType the suggested recovery type
 * @param priority priority level (1-10, higher = more urgent)
 * @param estimatedTime estimated time to execute this recommendation
 * @param successProbability estimated success probability (0.0-1.0)
 */
public record Recommendation(
        String title,
        String description,
        RecoveryType suggestedType,
        int priority,
        Duration estimatedTime,
        double successProbability) {

    /**
     * Create a high-priority recommendation.
     *
     * @param title the recommendation title
     * @param description the description
     * @param type the suggested recovery type
     * @param time the estimated time
     * @return a high-priority recommendation (priority 9, 95% success probability)
     */
    public static Recommendation highPriority(
            String title, String description, RecoveryType type, Duration time) {
        return new Recommendation(title, description, type, 9, time, 0.95);
    }

    /**
     * Create a medium-priority recommendation.
     *
     * @param title the recommendation title
     * @param description the description
     * @param type the suggested recovery type
     * @param time the estimated time
     * @return a medium-priority recommendation (priority 5, 80% success probability)
     */
    public static Recommendation mediumPriority(
            String title, String description, RecoveryType type, Duration time) {
        return new Recommendation(title, description, type, 5, time, 0.80);
    }

    /**
     * Create a low-priority recommendation.
     *
     * @param title the recommendation title
     * @param description the description
     * @param type the suggested recovery type
     * @param time the estimated time
     * @return a low-priority recommendation (priority 2, 70% success probability)
     */
    public static Recommendation lowPriority(
            String title, String description, RecoveryType type, Duration time) {
        return new Recommendation(title, description, type, 2, time, 0.70);
    }

    /**
     * Check if this recommendation has high priority.
     *
     * @return true if priority is 7 or higher
     */
    public boolean isHighPriority() {
        return priority >= 7;
    }

    /**
     * Check if this recommendation has a high success probability.
     *
     * @return true if success probability is 80% or higher
     */
    public boolean hasHighSuccessProbability() {
        return successProbability >= 0.80;
    }

    /**
     * Get a human-readable priority level.
     *
     * @return the priority level as a string
     */
    public String priorityLevel() {
        if (priority >= 8) return "CRITICAL";
        if (priority >= 6) return "HIGH";
        if (priority >= 4) return "MEDIUM";
        if (priority >= 2) return "LOW";
        return "OPTIONAL";
    }
}
