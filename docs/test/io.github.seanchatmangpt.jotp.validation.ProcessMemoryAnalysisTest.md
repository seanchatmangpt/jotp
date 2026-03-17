# io.github.seanchatmangpt.jotp.validation.ProcessMemoryAnalysisTest

## Table of Contents

- [10K Process Memory Analysis](#10kprocessmemoryanalysis)
- [100K Process Memory Analysis](#100kprocessmemoryanalysis)
- [100 Process Memory Analysis](#100processmemoryanalysis)
- [Process with Mailbox Messages Memory Analysis](#processwithmailboxmessagesmemoryanalysis)
- [Empty Process Memory Analysis](#emptyprocessmemoryanalysis)
- [1K Process Memory Analysis](#1kprocessmemoryanalysis)
- [Process with Small State Memory Analysis](#processwithsmallstatememoryanalysis)


## 10K Process Memory Analysis

Validating memory footprint for 10,000 processes



Expected: ~10-12 MB total (~1KB per process)



Test Configuration:

| Key | Value |
| --- | --- |
| `JVM Max Heap` | `3.93 GB` |
| `Target Processes` | `10,000` |



Phase 1: Creating processes...

  Created 10,000 processes (100.0%)



Phase 2: Forcing GC to stabilize measurement...

## 100K Process Memory Analysis

Validating memory footprint for 100,000 processes



Expected: ~80-120 MB total (~1KB per process)



> [!NOTE]
> This test demonstrates virtual thread scalability at scale.



Test Configuration:

| Key | Value |
| --- | --- |
| `JVM Max Heap` | `3.93 GB` |
| `Target Processes` | `100,000` |



Phase 1: Creating processes...

  Created 10,000 processes (10.0%)

  Created 20,000 processes (20.0%)

  Created 30,000 processes (30.0%)

  Created 40,000 processes (40.0%)

  Created 50,000 processes (50.0%)

  Created 60,000 processes (60.0%)

  Created 70,000 processes (70.0%)

  Created 80,000 processes (80.0%)

  Created 90,000 processes (90.0%)

  Created 100,000 processes (100.0%)



Phase 2: Forcing GC to stabilize measurement...



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...

## 100 Process Memory Analysis

Validating memory footprint for 100 processes



Expected: ~100-120 KB total (~1KB per process)



Test Configuration:

| Key | Value |
| --- | --- |
| `JVM Max Heap` | `3.93 GB` |
| `Target Processes` | `100` |



Phase 1: Creating processes...



Phase 2: Forcing GC to stabilize measurement...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Heap Growth` | `155.74 MB` |
| `Baseline Heap` | `73.14 MB` |
| `Cleanup Time` | `0.93 sec` |
| `GC Stabilization Time` | `1.26 sec` |
| `Creation Time` | `0.03 sec` |
| `After Creation Heap` | `228.87 MB` |
| `KB Per Process` | `15.95 KB` |
| `Bytes Per Process` | `16330.12 bytes` |
| `Processes Created` | `10,000` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Baseline Heap` | `73.14 MB` |
| `Final Heap` | `386.56 MB` |
| `Peak Heap` | `228.87 MB` |
| `Heap Growth` | `155.74 MB` |



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Heap Growth` | `94.11 MB` |
| `Baseline Heap` | `236.87 MB` |
| `Cleanup Time` | `0.01 sec` |
| `GC Stabilization Time` | `1.51 sec` |
| `Creation Time` | `0.00 sec` |
| `After Creation Heap` | `330.98 MB` |
| `KB Per Process` | `963.70 KB` |
| `Bytes Per Process` | `986825.76 bytes` |
| `Processes Created` | `100` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Baseline Heap` | `236.87 MB` |
| `Final Heap` | `332.98 MB` |
| `Peak Heap` | `330.98 MB` |
| `Heap Growth` | `94.11 MB` |

## Process with Mailbox Messages Memory Analysis

Validating memory footprint for 10,000 processes with messages in mailboxes



Expected: ~5-7 KB total (includes 10 messages per process)



Phase 1: Creating processes and populating mailboxes...

## Empty Process Memory Analysis

Validating memory footprint for 10,000 empty processes (no state, no messages)



Expected: ~3.5-4.5 KB total (virtual thread + mailbox overhead only)



Test Configuration:

| Key | Value |
| --- | --- |
| `JVM Max Heap` | `3.93 GB` |
| `Target Processes` | `10,000` |



Phase 1: Creating processes...

  Created 10,000 processes (100.0%)



Phase 2: Forcing GC to stabilize measurement...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Heap Growth` | `164.76 MB` |
| `Baseline Heap` | `260.42 MB` |
| `Creation Time` | `0.01 sec` |
| `After Creation Heap` | `425.18 MB` |
| `KB Per Process` | `16.87 KB` |
| `Bytes Per Process` | `17276.14 bytes` |
| `Processes Created` | `10,000` |
| `Total Messages` | `100,000` |
| `Messages Per Process` | `10` |



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...



Analysis:

> [!NOTE]
> Message overhead: 1329.28 bytes per message



Memory Summary:

| Key | Value |
| --- | --- |
| `Baseline Heap` | `260.42 MB` |
| `Final Heap` | `453.29 MB` |
| `Peak Heap` | `260.42 MB` |
| `Heap Growth` | `0.00 MB` |

## 1K Process Memory Analysis

Validating memory footprint for 1,000 processes



Expected: ~1-1.2 MB total (~1KB per process)



Test Configuration:

| Key | Value |
| --- | --- |
| `JVM Max Heap` | `3.93 GB` |
| `Target Processes` | `1,000` |



Phase 1: Creating processes...



Phase 2: Forcing GC to stabilize measurement...

## Process with Small State Memory Analysis

Validating memory footprint for 10,000 processes with small state objects



Expected: ~4-5 KB total (includes ~100-byte state object)



Phase 1: Creating processes with SmallState...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Heap Growth` | `49.14 MB` |
| `Baseline Heap` | `308.15 MB` |
| `Cleanup Time` | `1.57 sec` |
| `GC Stabilization Time` | `1.58 sec` |
| `Creation Time` | `0.06 sec` |
| `After Creation Heap` | `357.29 MB` |
| `KB Per Process` | `5.03 KB` |
| `Bytes Per Process` | `5152.37 bytes` |
| `Processes Created` | `10,000` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Baseline Heap` | `308.15 MB` |
| `Final Heap` | `254.86 MB` |
| `Peak Heap` | `357.29 MB` |
| `Heap Growth` | `49.14 MB` |



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Heap Growth` | `109.08 MB` |
| `Baseline Heap` | `205.78 MB` |
| `Cleanup Time` | `0.05 sec` |
| `GC Stabilization Time` | `1.39 sec` |
| `Creation Time` | `0.00 sec` |
| `After Creation Heap` | `314.86 MB` |
| `KB Per Process` | `111.70 KB` |
| `Bytes Per Process` | `114382.54 bytes` |
| `Processes Created` | `1,000` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Baseline Heap` | `205.78 MB` |
| `Final Heap` | `322.86 MB` |
| `Peak Heap` | `314.86 MB` |
| `Heap Growth` | `109.08 MB` |



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Heap Growth` | `90.78 MB` |
| `Baseline Heap` | `170.20 MB` |
| `Cleanup Time` | `5.01 sec` |
| `GC Stabilization Time` | `1.77 sec` |
| `Creation Time` | `0.71 sec` |
| `After Creation Heap` | `260.98 MB` |
| `KB Per Process` | `0.93 KB` |
| `Bytes Per Process` | `951.91 bytes` |
| `Processes Created` | `100,000` |



Validation:

| Key | Value |
| --- | --- |
| `Claim` | `~1KB per process VALIDATED` |
| `Status` | `PASS` |
| `Actual` | `0.93 KB/process` |

> [!NOTE]
> Memory footprint is within expected range!



Memory Summary:

| Key | Value |
| --- | --- |
| `Baseline Heap` | `170.20 MB` |
| `Final Heap` | `350.86 MB` |
| `Peak Heap` | `260.98 MB` |
| `Heap Growth` | `90.78 MB` |



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `KB Per Process` | `13.76 KB` |
| `Creation Time` | `0.01 sec` |
| `Heap Growth` | `134.42 MB` |
| `After Creation Heap` | `368.86 MB` |
| `State Type` | `SmallState (3 fields)` |
| `Bytes Per Process` | `14095.36 bytes` |
| `Baseline Heap` | `234.44 MB` |
| `Processes Created` | `10,000` |



Analysis:

> [!NOTE]
> State object overhead: 9.87 KB per process



Memory Summary:

| Key | Value |
| --- | --- |
| `Baseline Heap` | `234.44 MB` |
| `Final Heap` | `392.86 MB` |
| `Peak Heap` | `234.44 MB` |
| `Heap Growth` | `0.00 MB` |

---
*Generated by [DTR](http://www.dtr.org)*
