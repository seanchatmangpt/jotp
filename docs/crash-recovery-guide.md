# Crash Recovery Guide

## Overview

This guide provides step-by-step procedures for recovering from JVM crashes, handling failure scenarios, and troubleshooting recovery issues in JOTP applications.

## Table of Contents

1. [Quick Start Recovery](#quick-start-recovery)
2. [Understanding Crash Scenarios](#understanding-crash-scenarios)
3. [Recovery Procedures](#recovery-procedures)
4. [Common Failure Scenarios](#common-failure-scenarios)
5. [Troubleshooting](#troubleshooting)
6. [Best Practices](#best-practices)
7. [Monitoring and Alerting](#monitoring-and-alerting)

## Quick Start Recovery

### Emergency Recovery Checklist

When a JVM crashes, follow these steps:

```
☐ 1. Identify crash type (OOM, hardware, network, human error)
☐ 2. Check RocksDB data integrity
☐ 3. Verify sequence number consistency
☐ 4. Restart JVM with recovery mode
☐ 5. Monitor recovery progress
☐ 6. Validate system functionality
☐ 7. Update incident report
```

### Immediate Actions

```bash
# 1. Check JVM crash logs
tail -1000 /var/log/jotp/jvm-crash.log

# 2. Verify RocksDB data directory
ls -lh /var/lib/jotp/rocksdb/

# 3. Check for corrupted data
file /var/lib/jotp/rocksdb/*.sst

# 4. Restart JVM
./bin/jotp-start.sh --recovery-mode

# 5. Monitor recovery
tail -f /var/log/jotp/recovery.log
```

## Understanding Crash Scenarios

### Scenario 1: JVM Crash During Write

**Symptoms:**
- JVM killed by OOM killer
- Power failure
- Hardware failure
- `kill -9` command

**Impact:**
- In-flight messages lost
- Partial state writes possible
- ACK markers may be inconsistent

**Recovery Strategy:**
1. Load state with ACK verification
2. Rebuild from event log if inconsistent
3. Skip already-processed messages (idempotence)

### Scenario 2: Network Partition During Distributed Write

**Symptoms:**
- Network timeout during distributed commit
- Partial cluster reachable
- Split-brain condition

**Impact:**
- Inconsistent state across nodes
- Duplicate processing possible
- Sequence number gaps

**Recovery Strategy:**
1. Identify last consistent state
2. Resolve conflicts using sequence numbers
3. Rebuild from event log on affected nodes
4. Resynchronize cluster state

### Scenario 3: Corrupted State Files

**Symptoms:**
- RocksDB cannot open database
- SST file corruption detected
- Invalid JSON in state files

**Impact:**
- Cannot load persisted state
- Need to rebuild from event log
- Extended recovery time

**Recovery Strategy:**
1. Identify corrupted files
2. Restore from backup if available
3. Rebuild from event log
4. Verify consistency before resuming

## Recovery Procedures

### Procedure 1: Standard Recovery

Use this procedure when the JVM crashes but RocksDB data is intact.

```java
public class StandardRecovery {

    public void recover() {
        // 1. Initialize persistence backend
        PersistenceBackend backend = new RocksDBBackend(Path.of("/var/lib/jotp"));

        // 2. Initialize atomic state writer
        AtomicStateWriter<MyState> writer = new AtomicStateWriter<>(
            backend,
            new JsonSnapshotCodec<>(MyState.class)
        );

        // 3. Load all persisted processes
        for (String processId : backend.listKeys()) {
            if (processId.startsWith("process:")) {
                recoverProcess(processId, writer);
            }
        }
    }

    private void recoverProcess(String processId, AtomicStateWriter<MyState> writer) {
        // 1. Try to load state with ACK verification
        Optional<MyState> state = writer.readWithAck(processId);

        if (state.isPresent()) {
            // 2. State is consistent, resume processing
            resumeProcess(processId, state.get());
            System.out.println("Recovered " + processId + " from consistent state");
        } else {
            // 3. State is corrupt/incomplete, rebuild from event log
            MyState recovered = rebuildFromEventLog(processId, writer);
            writer.writeAtomic(processId, recovered, recovered.lastProcessedSeq());
            resumeProcess(processId, recovered);
            System.out.println("Recovered " + processId + " from event log");
        }
    }

    private MyState rebuildFromEventLog(String processId, AtomicStateWriter<MyState> writer) {
        // 1. Get last processed sequence from ACK
        long lastSeq = writer.getLastProcessed(processId);

        // 2. Load initial state or latest snapshot
        MyState state = loadInitialState(processId);

        // 3. Replay events after lastSeq
        for (Event event : backend.loadEvents(processId, lastSeq + 1)) {
            if (event.sequenceNumber() > lastSeq) {
                state = applyEvent(state, event);
            }
        }

        return state;
    }
}
```

### Procedure 2: Backup Recovery

Use this procedure when state files are corrupted but backups exist.

```java
public class BackupRecovery {

    public void recoverFromBackup() {
        Path dbPath = Path.of("/var/lib/jotp/rocksdb");
        Path backupPath = Path.of("/var/lib/jotp/backups/latest");

        // 1. Verify current database is corrupted
        try {
            new RocksDBBackend(dbPath).close();
            System.out.println("Database is intact, no recovery needed");
            return;
        } catch (PersistenceException e) {
            System.out.println("Database corrupted, initiating backup recovery");
        }

        // 2. Stop JVM if running
        shutdownJvm();

        // 3. Rename corrupted database
        Path corruptedPath = Path.of("/var/lib/jotp/rocksdb.corrupted." + System.currentTimeMillis());
        moveDirectory(dbPath, corruptedPath);

        // 4. Restore from backup
        copyDirectory(backupPath, dbPath);

        // 5. Verify backup integrity
        try {
            PersistenceBackend backend = new RocksDBBackend(dbPath);
            System.out.println("Backup restored successfully");
            backend.close();
        } catch (PersistenceException e) {
            System.out.println("Backup is also corrupted, need event log recovery");
            recoverFromEventLog();
        }

        // 6. Restart JVM
        startJvm();
    }
}
```

### Procedure 3: Event Log Recovery

Use this procedure when both state and backups are corrupted.

```java
public class EventLogRecovery {

    public void recoverFromEventLog() {
        // 1. Initialize backend in read-only mode
        PersistenceBackend backend = new RocksDBBackend(Path.of("/var/lib/jotp"));

        // 2. Get list of all processes
        List<String> processIds = backend.listKeys("process:")
            .filter(key -> key.startsWith("process:"))
            .toList();

        System.out.println("Recovering " + processIds.size() + " processes from event log");

        // 3. Recover each process
        for (String processId : processIds) {
            try {
                recoverProcessFromEvents(processId, backend);
            } catch (Exception e) {
                System.err.println("Failed to recover " + processId + ": " + e.getMessage());
            }
        }
    }

    private void recoverProcessFromEvents(String processId, PersistenceBackend backend) {
        // 1. Load initial state (factory fresh or snapshot)
        MyState state = getInitialState(processId);

        // 2. Load all events for this process
        List<Event> events = backend.loadEvents(processId, 0)
            .stream()
            .sorted(Comparator.comparingLong(Event::sequenceNumber))
            .toList();

        System.out.println("Replaying " + events.size() + " events for " + processId);

        // 3. Replay events in order
        for (Event event : events) {
            state = applyEvent(state, event);
        }

        // 4. Write recovered state atomically
        AtomicStateWriter<MyState> writer = new AtomicStateWriter<>(
            backend,
            new JsonSnapshotCodec<>(MyState.class)
        );

        writer.writeAtomic(processId, state, state.lastProcessedSeq());

        System.out.println("Recovered " + processId + " to sequence " + state.lastProcessedSeq());
    }
}
```

## Common Failure Scenarios

### Scenario 1: OutOfMemoryError Crash

**Detection:**
```bash
# Check JVM crash log
grep "OutOfMemoryError" /var/log/jotp/jvm-crash.log

# Check OOM killer
grep "killed process" /var/log/syslog
```

**Recovery Steps:**
1. Increase heap size: `-Xmx8g` (or larger)
2. Enable GC logging: `-Xlog:gc*`
3. Restart JVM with increased memory
4. Monitor GC metrics during recovery

**Prevention:**
```bash
# Run with heap dump on OOM
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/jotp/

# Set reasonable heap size
-Xmx4g -Xms4g

# Use ZGC for large heaps
-XX:+UseZGC
```

### Scenario 2: RocksDB Corruption

**Detection:**
```bash
# Try to open database
jotp-cli --check-db

# Check SST files
file /var/lib/jotp/rocksdb/*.sst | grep -v "data"

# Check WAL files
ls -lh /var/lib/jotp/rocksdb/wal/
```

**Recovery Steps:**
1. Run RocksDB verification: `jotp-cli --verify-db`
2. If corruption detected, restore from backup
3. If no backup, rebuild from event log
4. Verify recovered data consistency

**Prevention:**
- Use SSDs (reduces write errors)
- Enable regular backups: `jotp-cli --backup daily`
- Monitor disk health: `smartctl -a /dev/sda`

### Scenario 3: Sequence Number Mismatch

**Detection:**
```java
// Log during recovery
if (state.lastProcessedSeq() != ackSeq) {
    System.err.println("Sequence mismatch for " + processId +
        ": state=" + state.lastProcessedSeq() + " ack=" + ackSeq);
}
```

**Recovery Steps:**
1. Identify which value is correct (usually ACK)
2. Rebuild state from event log starting from ACK
3. Verify sequence number continuity
4. Update state with correct sequence

**Example:**
```java
private MyState resolveSequenceMismatch(String processId, MyState state, long ackSeq) {
    // ACK is the source of truth
    System.out.println("Resolving sequence mismatch for " + processId);
    System.out.println("  State seq: " + state.lastProcessedSeq());
    System.out.println("  ACK seq: " + ackSeq);

    // Rebuild from event log starting from ACK
    MyState recovered = rebuildFromEventLog(processId, ackSeq);

    System.out.println("  Recovered seq: " + recovered.lastProcessedSeq());

    return recovered;
}
```

## Troubleshooting

### Problem: Recovery Hangs Indefinitely

**Symptoms:**
- Recovery process doesn't complete
- High CPU usage but no progress
- Log messages stop appearing

**Diagnosis:**
```bash
# Check thread dump
jstack <pid> > thread-dump.txt

# Look for blocked threads
grep "BLOCKED" thread-dump.txt

# Check RocksDB compaction
jotp-cli --db-stats
```

**Solutions:**
1. Kill stuck recovery process
2. Run RocksDB manual compaction
3. Restart recovery with smaller batch size
4. Increase timeout values

### Problem: Duplicate Message Processing

**Symptoms:**
- Duplicate transactions
- Counted twice in analytics
- Balance errors

**Diagnosis:**
```java
// Check sequence number gaps
long lastSeq = writer.getLastProcessed(processId);
long expectedSeq = lastSeq + 1;

if (msg.sequenceNumber() < expectedSeq) {
    System.err.println("Duplicate or out-of-order message: " +
        "expected=" + expectedSeq + " got=" + msg.sequenceNumber());
}
```

**Solutions:**
1. Verify idempotence check is enabled
2. Check sequence number is monotonically increasing
3. Skip messages with sequence ≤ last processed
4. Log skipped messages for audit

### Problem: Event Log Replay Too Slow

**Symptoms:**
- Recovery takes hours
- High disk I/O
- CPU at 100%

**Diagnosis:**
```bash
# Check event log size
du -sh /var/lib/jotp/rocksdb/

# Check event count
jotp-cli --count-events

# Check replay speed
jotp-cli --replay-dry-run
```

**Solutions:**
1. Enable snapshots (reduce replay distance)
2. Compact event log (remove old events)
3. Increase replay batch size
4. Use faster disk (SSD)

**Example: Snapshot Strategy**
```java
// Save snapshot every 1000 events
private void maybeSaveSnapshot(String processId, MyState state) {
    long eventCount = getEventCount(processId);

    if (eventCount % 1000 == 0) {
        backend.saveSnapshot(processId, serialize(state));
        System.out.println("Saved snapshot for " + processId + " at event " + eventCount);
    }
}

// During recovery, load latest snapshot first
private MyState loadInitialState(String processId) {
    Optional<byte[]> snapshot = backend.loadLatestSnapshot(processId);

    if (snapshot.isPresent()) {
        return deserialize(snapshot.get());
    } else {
        return MyState.initial();
    }
}
```

## Best Practices

### 1. Regular Backups

```bash
# Daily automated backup
0 2 * * * /usr/local/bin/jotp-cli --backup /backup/jotp/daily

# Weekly full backup
0 3 * * 0 /usr/local/bin/jotp-cli --backup /backup/jotp/weekly

# Keep 30 daily backups
find /backup/jotp/daily -mtime +30 -delete

# Keep 12 weekly backups
find /backup/jotp/weekly -mtime +84 -delete
```

### 2. Monitoring and Alerting

```java
// Add recovery metrics
public class RecoveryMetrics {

    private final Meter recoveryTime;
    private final Counter recoveredProcesses;
    private final Counter failedRecoveries;
    private final Counter corruptedStates;

    public void recordRecovery(String processId, Duration duration, boolean success) {
        recoveryTime.record(duration.toMillis());
        if (success) {
            recoveredProcesses.increment();
        } else {
            failedRecoveries.increment();
        }
    }

    public void recordCorruption(String processId) {
        corruptedStates.increment();
    }
}
```

### 3. Idempotence Testing

```java
@Test
@DisplayName("Should handle duplicate messages idempotently")
void shouldHandleDuplicateMessages() {
    // Process message once
    proc.tell(new Increment(5, sequenceNumber = 1));
    await().until(() -> state.value() == 5);

    // Process same message again (duplicate)
    proc.tell(new Increment(5, sequenceNumber = 1));
    await().until(() -> state.value() == 5);  // Should still be 5, not 10

    assertThat(state.value()).isEqualTo(5);  // Idempotent!
}
```

### 4. Recovery Drills

```bash
# Schedule monthly recovery drills
# Simulate crash and verify recovery works

# 1. Take backup
jotp-cli --backup /backup/drill-$(date +%Y%m%d)

# 2. Simulate crash (kill JVM)
kill -9 $(jotp-cli --pid)

# 3. Corrupt database
rm -rf /var/lib/jotp/rocksdb/CURRENT

# 4. Attempt recovery
./bin/jotp-start.sh --recovery-mode

# 5. Verify functionality
jotp-cli --health-check

# 6. Report results
echo "Drill completed: $(date)" >> /var/log/jotp/drill-log.txt
```

## Monitoring and Alerting

### Key Metrics

Monitor these metrics during recovery:

| Metric | Description | Alert Threshold |
|--------|-------------|-----------------|
| **Recovery time** | Time to recover all processes | >5 minutes |
| **Recovery success rate** | Processes recovered successfully | <99% |
| **Sequence mismatches** | State/ACK inconsistencies | >0 |
| **Event replay count** | Events replayed during recovery | >10,000 |
| **Corrupted states** | Unrecoverable state files | >0 |

### Alert Configuration

```yaml
# Prometheus alerting rules
groups:
  - name: jotp_recovery
    interval: 30s
    rules:
      - alert: JOTPRecoverySlow
        expr: jotp_recovery_time_seconds > 300
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "JOTP recovery taking too long"
          description: "Recovery has been running for {{ $value }} seconds"

      - alert: JOTPRecoveryFailure
        expr: rate(jotp_recovery_failures_total[5m]) > 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "JOTP recovery failures detected"
          description: "{{ $value }} recoveries failed in the last 5 minutes"

      - alert: JOTPSequenceMismatch
        expr: rate(jotp_sequence_mismatches_total[5m]) > 0
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "JOTP sequence number mismatches detected"
          description: "{{ $value }} mismatches in the last 5 minutes"
```

### Recovery Dashboard

Create a Grafana dashboard with:

1. **Recovery Status Panel**
   - Current recovery progress
   - Processes recovered vs. total
   - Time elapsed

2. **Health Check Panel**
   - Database integrity
   - Disk space
   - Memory usage

3. **Event Log Panel**
   - Total events
   - Replay rate
   - Estimated time remaining

4. **Error Panel**
   - Corrupted states
   - Failed recoveries
   - Sequence mismatches

## Conclusion

Effective crash recovery is essential for production systems. Follow these guidelines:

1. **Prevention first** - Regular backups, monitoring, health checks
2. **Test recovery** - Monthly drills, automated testing
3. **Monitor everything** - Metrics, logs, alerts
4. **Document procedures** - Runbooks, incident reports
5. **Learn from failures** - Post-mortems, improvements

## References

- [JVM Crash Survival](jvm-crash-survival.md) - Deep dive on atomic writes
- [Persistence Backends](persistence-backends.md) - Backend configuration
- [Distributed Patterns](distributed-patterns.md) - Cluster recovery
- [ARCHITECTURE.md](ARCHITECTURE.md) - System architecture overview
