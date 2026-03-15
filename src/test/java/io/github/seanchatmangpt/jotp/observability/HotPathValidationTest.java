package io.github.seanchatmangpt.jotp.observability;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HotPathValidation}.
 *
 * <p>This test validates that hot path methods remain free from observability contamination. Run
 * during build phase to enforce performance contracts.
 */
@DisplayName("Hot Path Validation Tests")
class HotPathValidationTest {

    @Test
    @DisplayName("Proc.tell() should not contain observability code")
    void validateProcTellIsPure() {
        // This will throw AssertionError with details if violations found
        assertDoesNotThrow(
                () -> HotPathValidation.validateHotPaths(),
                "Hot path methods must be free from observability infrastructure");
    }

    @Test
    @DisplayName("All hot paths should pass validation")
    void allHotPathsShouldBePure() {
        // Comprehensive validation of all registered hot paths
        HotPathValidation.validateHotPaths();
    }
}
