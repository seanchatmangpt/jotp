package io.github.seanchatmangpt.jotp.dogfood.toyota;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import io.github.seanchatmangpt.jotp.dogfood.toyota.HeijunkaAgent.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.*;

@Timeout(10)
class HeijunkaAgentTest {

    private HeijunkaAgent agent;

    @BeforeEach
    void setUp() {
        agent = HeijunkaAgent.start(List.of("feature", "bugfix", "chore"), Duration.ofMillis(150));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        agent.stop();
    }

    @Test
    @DisplayName("Items submitted to each row are dispatched after pitches")
    void itemsDispatchedAfterPitch() {
        agent.submit("feature", new HeijunkaItem("F-1", "Add OAuth"));
        agent.submit("bugfix", new HeijunkaItem("B-1", "Fix null user"));
        agent.submit("chore", new HeijunkaItem("C-1", "Upgrade deps"));

        await().atMost(Duration.ofSeconds(3))
                .until(() -> agent.dispatched(Duration.ofSeconds(1)).size() >= 3);

        var dispatched = agent.dispatched(Duration.ofSeconds(1));
        var types = dispatched.stream().map(DispatchedItem::workType).toList();
        assertThat(types).containsExactlyInAnyOrder("feature", "bugfix", "chore");
    }

    @Test
    @DisplayName("Each pitch is monotonically increasing")
    void pitchCountIncreases() {
        await().atMost(Duration.ofSeconds(3))
                .until(() -> agent.metrics(Duration.ofSeconds(1)).pitchCount() >= 2);

        var m = agent.metrics(Duration.ofSeconds(1));
        assertThat(m.pitchCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Dispatched-by-type counts match submitted items")
    void dispatchedByTypeMatchesSubmissions() {
        agent.submit("feature", new HeijunkaItem("F-1", "Feature 1"));
        agent.submit("feature", new HeijunkaItem("F-2", "Feature 2"));
        agent.submit("bugfix", new HeijunkaItem("B-1", "Bug 1"));

        await().atMost(Duration.ofSeconds(3))
                .until(() -> agent.dispatched(Duration.ofSeconds(1)).size() >= 3);

        var byType = agent.metrics(Duration.ofSeconds(1)).dispatchedByType();
        assertThat(byType.get("feature")).isEqualTo(2L);
        assertThat(byType.get("bugfix")).isEqualTo(1L);
    }

    @Test
    @DisplayName("Unknown work type throws IllegalArgumentException")
    void unknownTypeThrows() {
        assertThatThrownBy(() -> agent.submit("invalid-type", new HeijunkaItem("X", "bad")))
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid-type");
    }
}
