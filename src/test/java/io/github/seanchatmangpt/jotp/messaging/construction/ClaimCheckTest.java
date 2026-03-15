package io.github.seanchatmangpt.jotp.messaging.construction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.Result;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
/**
 * Tests for {@link ClaimCheck} — lightweight claim token pattern.
 *
 * <p>ClaimCheck enables deferred loading and bandwidth optimization in distributed systems. This
 * test suite covers storage, retrieval, release, and error cases.
 */
@Timeout(5)
class ClaimCheckTest implements WithAssertions {
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        ClaimCheck.clearAll();
    }
    @AfterEach
    void tearDown() {
    // ── Basic operations ─────────────────────────────────────────────────
    @Test
    void claimCheckStoresPayloadAndReturnsToken() {
        String payload = "Large document content...";
        Map<String, String> metadata = Map.of("type", "document");
        ClaimCheck.CheckedMessage checked = ClaimCheck.claimCheck(payload, metadata);
        assertThat(checked.claimId()).isNotNull();
        assertThat(checked.timestamp()).isGreaterThan(0);
        assertThat(checked.metadata()).isEqualTo(metadata);
    void retrieveReturnsStoredPayload() {
        String payload = "Test document";
        UUID claimId = ClaimCheck.claimCheck(payload).claimId();
        Result<String, String> result = ClaimCheck.<String>retrieve(claimId);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.fold(s -> s, e -> null)).isEqualTo(payload);
    void retrieveNonExistentClaimReturnsError() {
        UUID nonExistentId = UUID.randomUUID();
        Result<String, String> result = ClaimCheck.<String>retrieve(nonExistentId);
        assertThat(result.isFailure()).isTrue();
        assertThat(result.fold(s -> null, e -> e)).contains("No payload found");
    // ── Claim management ─────────────────────────────────────────────────
    void releaseRemovesClaimFromStorage() {
        String payload = "Document";
        boolean released = ClaimCheck.release(claimId);
        assertThat(released).isTrue();
        assertThat(ClaimCheck.retrieve(claimId).isFailure()).isTrue();
    void releaseNonExistentClaimReturnsFalse() {
        boolean released = ClaimCheck.release(nonExistentId);
        assertThat(released).isFalse();
    void existsChecksPresentClaim() {
        assertThat(ClaimCheck.exists(claimId)).isTrue();
    void existsReturnsFalseForMissingClaim() {
        assertThat(ClaimCheck.exists(nonExistentId)).isFalse();
    // ── Single-use/consume pattern ───────────────────────────────────────
    void consumeClaimRetrievesAndRemoves() {
        Result<String, String> result = ClaimCheck.<String>consumeClaim(claimId);
        assertThat(ClaimCheck.exists(claimId)).isFalse();
    void consumeNonExistentClaimReturnsError() {
        Result<String, String> result = ClaimCheck.<String>consumeClaim(nonExistentId);
    // ── Metadata operations ──────────────────────────────────────────────
    void checkedMessagePreservesMetadata() {
        Map<String, String> metadata =
                Map.of(
                        "filename", "report.pdf",
                        "size-mb", "50");
        assertThat(checked.metadata())
                .containsEntry("filename", "report.pdf")
                .containsEntry("size-mb", "50");
    void checkedMessageRetrieveWorks() {
        ClaimCheck.CheckedMessage checked = ClaimCheck.claimCheck(payload);
        Result<String, String> result = checked.<String>retrieve();
    // ── Type safety and generics ─────────────────────────────────────────
    void claimCheckHandlesGenericTypes() {
        record Person(String name, int age) {}
        Person person = new Person("Alice", 30);
        UUID claimId = ClaimCheck.claimCheck(person).claimId();
        Result<Person, String> result = ClaimCheck.retrieve(claimId);
        assertThat(result.fold(p -> p.name(), e -> null)).isEqualTo("Alice");
    // ── Error handling and validation ────────────────────────────────────
    void claimCheckRejectsNullPayload() {
        assertThatThrownBy(() -> ClaimCheck.claimCheck(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload must not be null");
    void retrieveRejectsNullClaimId() {
        Result<String, String> result = ClaimCheck.<String>retrieve(null);
        assertThat(result.fold(s -> null, e -> e)).contains("claimId must not be null");
    void consumeRejectsNullClaimId() {
        Result<String, String> result = ClaimCheck.<String>consumeClaim(null);
    // ── Concurrency and storage ──────────────────────────────────────────
    void claimCountReflectsStorageSize() {
        ClaimCheck.claimCheck("Doc1");
        ClaimCheck.claimCheck("Doc2");
        ClaimCheck.claimCheck("Doc3");
        assertThat(ClaimCheck.claimCount()).isEqualTo(3);
    void clearAllRemovesAllClaims() {
        assertThat(ClaimCheck.claimCount()).isEqualTo(0);
    void multipleClaimsAreIndependent() {
        String payload1 = "Document1";
        String payload2 = "Document2";
        UUID claimId1 = ClaimCheck.claimCheck(payload1).claimId();
        UUID claimId2 = ClaimCheck.claimCheck(payload2).claimId();
        Result<String, String> result1 = ClaimCheck.<String>retrieve(claimId1);
        Result<String, String> result2 = ClaimCheck.<String>retrieve(claimId2);
        assertThat(result1.fold(s -> s, e -> null)).isEqualTo(payload1);
        assertThat(result2.fold(s -> s, e -> null)).isEqualTo(payload2);
    // ── Railway-oriented composition ─────────────────────────────────────
    void resultCompositionWorksWithClaimCheck() {
        String payload = "document-123";
        Result<String, String> result =
                ClaimCheck.<String>retrieve(claimId)
                        .map(s -> s.toUpperCase())
                        .map(s -> s + "-processed");
        assertThat(result.fold(s -> s, e -> null)).isEqualTo("DOCUMENT-123-PROCESSED");
    void resultFlatMapChainHandlesErrors() {
                ClaimCheck.<String>retrieve(nonExistentId)
    void checkedMessageMetadataWithResult() {
        Map<String, String> metadata = Map.of("priority", "high", "version", "2");
        ClaimCheck.CheckedMessage checked = ClaimCheck.claimCheck("payload", metadata);
                checked.<String>retrieve()
                        .map(p -> p + " (priority=" + checked.metadata().get("priority") + ")");
        assertThat(result.fold(s -> s, e -> null)).contains("(priority=high)");
}
