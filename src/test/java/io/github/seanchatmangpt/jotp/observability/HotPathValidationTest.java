package io.github.seanchatmangpt.jotp.observability;

import static org.junit.jupiter.api.Assertions.*;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HotPathValidation}.
 *
 * <p>This test validates that hot path methods remain free from observability contamination. Run
 * during build phase to enforce performance contracts.
 *
 * <p>The zero-overhead principle requires that observability infrastructure does not impact hot
 * path performance. This test enforces that constraint by analyzing bytecode to ensure no
 * observability calls are present in critical methods like {@code Proc.tell()}.
 */
@DtrTest
@DisplayName("Hot Path Validation Tests")
class HotPathValidationTest {

    @Test
    @DisplayName("Proc.tell() should not contain observability code")
    void validateProcTellIsPure(DtrContext ctx) {
        ctx.say("Hot path validation ensures Proc.tell() maintains zero-overhead");
        ctx.say("The tell() method is the most frequently called operation in JOTP");
        ctx.say("Any observability code in this path would impact millions of messages per second");

        // This will throw AssertionError with details if violations found
        assertDoesNotThrow(
                () -> HotPathValidation.validateHotPaths(),
                "Hot path methods must be free from observability infrastructure");

        ctx.say("Validation passed: hot paths remain pure and performant");
    }

    @Test
    @DisplayName("All hot paths should pass validation")
    void allHotPathsShouldBePure(DtrContext ctx) {
        ctx.say("Comprehensive validation of all registered hot paths");
        ctx.say("This includes Proc.tell(), Proc.ask(), and mailbox operations");

        // Comprehensive validation of all registered hot paths
        HotPathValidation.validateHotPaths();

        ctx.say("All hot paths validated successfully");
    }
}
