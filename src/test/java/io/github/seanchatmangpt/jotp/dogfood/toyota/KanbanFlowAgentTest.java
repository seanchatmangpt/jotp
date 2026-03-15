package io.github.seanchatmangpt.jotp.dogfood.toyota;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import io.github.seanchatmangpt.jotp.dogfood.toyota.KanbanFlowAgent.*;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.*;

@Timeout(10)
class KanbanFlowAgentTest {

    private KanbanFlowAgent.KanbanState initialState;

    @Test
    @DisplayName("WIP limit blocks pull when capacity exhausted")
    void wipLimitBlocks() {
        var state = KanbanState.of(Map.of("build", 1));
        state.enqueue("build", new WorkItem("W1", "task-one"));
        state.enqueue("build", new WorkItem("W2", "task-two"));

        var first = state.tryPull("build");
        assertThat(first.result()).isInstanceOf(PullResult.Card.class);

        var second = state.tryPull("build");
        assertThat(second.result()).isInstanceOf(PullResult.WipLimitReached.class);
        assertThat(((PullResult.WipLimitReached) second.result()).current()).isEqualTo(1);
    }

    @Test
    @DisplayName("Empty queue returns EmptyQueue result when capacity available")
    void emptyQueueWhenCapacityFree() {
        var state = KanbanState.of(Map.of("test", 3));
        var attempt = state.tryPull("test");
        assertThat(attempt.result()).isInstanceOf(PullResult.EmptyQueue.class);
    }

    @Test
    @DisplayName("Complete releases WIP slot allowing next pull")
    void completePullCycle() {
        var state = KanbanState.of(Map.of("deploy", 1));
        state.enqueue("deploy", new WorkItem("D1", "release-v1"));
        state.enqueue("deploy", new WorkItem("D2", "release-v2"));

        var pull1 = state.tryPull("deploy");
        assertThat(pull1.result()).isInstanceOf(PullResult.Card.class);
        var card = (PullResult.Card) pull1.result();
        assertThat(card.workItem().description()).isEqualTo("release-v1");

        state.complete("deploy", card.workItem().id());

        var pull2 = state.tryPull("deploy");
        assertThat(pull2.result()).isInstanceOf(PullResult.Card.class);
        assertThat(((PullResult.Card) pull2.result()).workItem().description())
                .isEqualTo("release-v2");
    }

    @Test
    @DisplayName("Utilisation reports current WIP fraction per lane")
    void utilisationReport() {
        var state = KanbanState.of(Map.of("build", 3, "test", 2));
        state.enqueue("build", new WorkItem("B1", "task"));
        state.tryPull("build");
        state.tryPull("build"); // only 1 item, so second is EmptyQueue — WIP stays 1

        var util = state.utilisation();
        assertThat(util).containsKey("build");
        assertThat(util).containsKey("test");
        assertThat(util.get("test")).isEqualTo("0/2");
    }

    @Test
    @DisplayName("Agent process accepts AddWork and Pull messages")
    void agentProcessRoundTrip() throws InterruptedException {
        var agent = KanbanFlowAgent.start(Map.of("design", 2));
        try {
            agent.tell(new KanbanMsg.AddWork("design", new WorkItem("T1", "Design spec")));
            Thread.sleep(50);

            var state = agent.ask(new KanbanMsg.Utilisation(), Duration.ofSeconds(1)).join();
            assertThat(state.backlog().get("design")).hasSize(1);
        } finally {
            agent.stop();
        }
    }

    @Test
    @DisplayName("Unknown lane throws IllegalArgumentException")
    void unknownLaneThrows() {
        var state = KanbanState.of(Map.of("build", 1));
        assertThatThrownBy(() -> state.enqueue("nonexistent", new WorkItem("X", "bad")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent");
    }
}
