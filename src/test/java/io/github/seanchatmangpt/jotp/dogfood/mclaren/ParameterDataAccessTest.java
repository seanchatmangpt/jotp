package io.github.seanchatmangpt.jotp.dogfood.mclaren;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.Proc;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Test;
/**
 * Unit tests for {@link ParameterDataAccess} — the gen_server Proc that implements SQL Race {@code
 * IParameterDataAccess}.
 *
 * <p>Tests cover: sample ingestion, data status tagging, GoTo/GetNextSamples, ring buffer cap,
 * stats, and the {@link org.acme.CrashRecovery}-backed spawn.
 */
class ParameterDataAccessTest implements WithAssertions {
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    private Proc<ParameterDataAccess.State, PdaMsg> proc;
    @AfterEach
    void tearDown() throws InterruptedException {
        if (proc != null && proc.thread().isAlive()) {
            proc.stop();
        }
    private static SqlRaceParameter vCar() {
        return SqlRaceParameter.of("vCar", "Chassis", 1L, 0.0, 400.0, "kph");
    private static SqlRaceChannel vCarChannel() {
        return SqlRaceChannel.periodic(1L, "vCar", 200.0, FrequencyUnit.Hz, DataType.Signed16Bit);
    // ── Spawn ─────────────────────────────────────────────────────────────────
    @Test
    void spawnSucceeds() {
        var result = ParameterDataAccess.spawn(vCar(), vCarChannel());
        assertThat(result.isSuccess()).isTrue();
        proc = result.orElseThrow();
        assertThat(proc.thread().isAlive()).isTrue();
    void spawnOrThrowReturnsRunningProcess() {
        proc = ParameterDataAccess.spawnOrThrow(vCar(), vCarChannel());
    // ── AddSamples: good path ─────────────────────────────────────────────────
    void goodSampleStoredAndRetrieved() throws Exception {
        long ts = 1_000_000L;
        proc.tell(new PdaMsg.AddSamples(new long[] {ts}, new double[] {237.5}));
        Thread.sleep(20); // let the virtual thread process
        proc.tell(new PdaMsg.GoTo(0L));
        var values = ParameterDataAccess.getNextSamples(proc, 10, StepDirection.Forward);
        assertThat(values.count()).isEqualTo(1);
        assertThat(values.data()[0]).isEqualTo(237.5);
        assertThat(values.timestamps()[0]).isEqualTo(ts);
        assertThat(values.dataStatus()[0]).isEqualTo(DataStatusType.Good);
    void multipleSamplesInBatch() throws Exception {
        proc.tell(
                new PdaMsg.AddSamples(
                        new long[] {1L, 2L, 3L, 4L, 5L},
                        new double[] {100.0, 150.0, 200.0, 250.0, 300.0}));
        Thread.sleep(20);
        assertThat(values.count()).isEqualTo(5);
        assertThat(values.goodSamples().count()).isEqualTo(5);
    // ── Out-of-range: tagged, process NOT crashed ─────────────────────────────
    void outOfRangeSampleTaggedNotCrashed() throws Exception {
        // 450 kph is above max 400 → OutOfRange
        proc.tell(new PdaMsg.AddSamples(new long[] {1L}, new double[] {450.0}));
        var values = ParameterDataAccess.getNextSamples(proc, 1, StepDirection.Forward);
        assertThat(values.dataStatus()[0]).isEqualTo(DataStatusType.OutOfRange);
    void invalidDataTaggedForNonFiniteValues() throws Exception {
                new PdaMsg.AddSamplesWithStatus(
                        new long[] {1L},
                        new double[] {Double.NaN},
                        new DataStatusType[] {DataStatusType.InvalidData}));
        assertThat(values.dataStatus()[0]).isEqualTo(DataStatusType.InvalidData);
    // ── GoTo + GetNextSamples ─────────────────────────────────────────────────
    void goToSetsStartPositionForForwardScan() throws Exception {
                        new long[] {10L, 20L, 30L, 40L, 50L},
                        new double[] {1.0, 2.0, 3.0, 4.0, 5.0}));
        proc.tell(new PdaMsg.GoTo(25L)); // seek to between 20 and 30
        var values = ParameterDataAccess.getNextSamples(proc, 2, StepDirection.Forward);
        // Should return 30 and 40 (first two entries ≥ 25)
        assertThat(values.count()).isEqualTo(2);
        assertThat(values.timestamps()[0]).isEqualTo(30L);
        assertThat(values.timestamps()[1]).isEqualTo(40L);
    void reverseStepReturnsDescendingOrder() throws Exception {
        proc.tell(new PdaMsg.GoTo(45L));
        var values = ParameterDataAccess.getNextSamples(proc, 3, StepDirection.Reverse);
        // headMap(45, inclusive).descendingMap → 40, 30, 20
        assertThat(values.count()).isEqualTo(3);
        assertThat(values.timestamps()[0]).isEqualTo(40L);
        assertThat(values.timestamps()[1]).isEqualTo(30L);
        assertThat(values.timestamps()[2]).isEqualTo(20L);
    void emptyBufferReturnsEmptyValues() throws Exception {
        assertThat(values.count()).isZero();
    // ── Ring buffer cap ───────────────────────────────────────────────────────
    void ringBufferCapEvictsOldestSamples() throws Exception {
        int cap = ParameterDataAccess.RING_BUFFER_CAP;
        // Add cap+1 samples
        long[] timestamps = new long[cap + 1];
        double[] values = new double[cap + 1];
        for (int i = 0; i < cap + 1; i++) {
            timestamps[i] = i + 1;
            values[i] = 100.0;
        proc.tell(new PdaMsg.AddSamples(timestamps, values));
        Thread.sleep(100); // let the virtual thread process all
        // Buffer should be capped at RING_BUFFER_CAP; oldest (ts=1) evicted
        var future = new java.util.concurrent.CompletableFuture<PdaStats>();
        proc.tell(new PdaMsg.GetStats(future));
        var stats = future.get(5, TimeUnit.SECONDS);
        assertThat(stats.bufferSize()).isEqualTo(cap);
    // ── GetStats ──────────────────────────────────────────────────────────────
    void getStatsReturnsAccurateCounts() throws Exception {
                        new long[] {1L, 2L, 3L},
                        new double[] {100.0, 450.0, 200.0})); // 450 is out-of-range
        var stats = ParameterDataAccess.getStats(proc);
        assertThat(stats.paramIdentifier()).isEqualTo("vCar:Chassis");
        assertThat(stats.totalSamples()).isEqualTo(3);
        assertThat(stats.goodSamples()).isEqualTo(2); // 100 and 200 are good
        assertThat(stats.bufferSize()).isEqualTo(3);
    // ── Clear ─────────────────────────────────────────────────────────────────
    void clearEmptiesBuffer() throws Exception {
                new PdaMsg.AddSamples(new long[] {1L, 2L, 3L}, new double[] {100.0, 200.0, 300.0}));
        proc.tell(new PdaMsg.Clear());
}
