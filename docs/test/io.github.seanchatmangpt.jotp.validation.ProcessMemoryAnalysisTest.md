# io.github.seanchatmangpt.jotp.validation.ProcessMemoryAnalysisTest

## Table of Contents

- [10K Process Memory Analysis](#10kprocessmemoryanalysis)
- [100K Process Memory Analysis](#100kprocessmemoryanalysis)
- [100 Process Memory Analysis](#100processmemoryanalysis)
- [Empty Process Memory Analysis](#emptyprocessmemoryanalysis)
- [1K Process Memory Analysis](#1kprocessmemoryanalysis)
- [Process with Small State Memory Analysis](#processwithsmallstatememoryanalysis)
- [Process with Mailbox Messages Memory Analysis](#processwithmailboxmessagesmemoryanalysis)


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



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...

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

  Created 100,000 processes (100.0%)



Phase 2: Forcing GC to stabilize measurement...



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...

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



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Processes Created` | `100` |
| `Bytes Per Process` | `-348889.44 bytes` |
| `KB Per Process` | `-340.71 KB` |
| `After Creation Heap` | `275.71 MB` |
| `Creation Time` | `0.00 sec` |
| `GC Stabilization Time` | `2.57 sec` |
| `Cleanup Time` | `9.04 sec` |
| `Baseline Heap` | `308.98 MB` |
| `Heap Growth` | `-33.27 MB` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Peak Heap` | `308.98 MB` |
| `Final Heap` | `497.43 MB` |
| `Baseline Heap` | `308.98 MB` |
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



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...

## Process with Small State Memory Analysis

Validating memory footprint for 10,000 processes with small state objects



Expected: ~4-5 KB total (includes ~100-byte state object)



Phase 1: Creating processes with SmallState...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Processes Created` | `10,000` |
| `Bytes Per Process` | `14850.05 bytes` |
| `KB Per Process` | `14.50 KB` |
| `After Creation Heap` | `259.47 MB` |
| `Creation Time` | `0.01 sec` |
| `GC Stabilization Time` | `2.44 sec` |
| `Cleanup Time` | `32.10 sec` |
| `Baseline Heap` | `117.85 MB` |
| `Heap Growth` | `141.62 MB` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Peak Heap` | `325.85 MB` |
| `Final Heap` | `523.85 MB` |
| `Baseline Heap` | `117.85 MB` |
| `Heap Growth` | `208.00 MB` |



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `After Creation Heap` | `293.64 MB` |
| `Heap Growth` | `-53.91 MB` |
| `Creation Time` | `0.01 sec` |
| `KB Per Process` | `-5.52 KB` |
| `Processes Created` | `10,000` |
| `Baseline Heap` | `347.55 MB` |
| `Bytes Per Process` | `-5652.97 bytes` |
| `State Type` | `SmallState (3 fields)` |

## Process with Mailbox Messages Memory Analysis

Validating memory footprint for 10,000 processes with messages in mailboxes



Expected: ~5-7 KB total (includes 10 messages per process)



Phase 1: Creating processes and populating mailboxes...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Processes Created` | `10,000` |
| `Bytes Per Process` | `5537.58 bytes` |
| `KB Per Process` | `5.41 KB` |
| `After Creation Heap` | `344.77 MB` |
| `Creation Time` | `0.11 sec` |
| `GC Stabilization Time` | `4.58 sec` |
| `Cleanup Time` | `18.13 sec` |
| `Baseline Heap` | `291.96 MB` |
| `Heap Growth` | `52.81 MB` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Peak Heap` | `365.96 MB` |
| `Final Heap` | `492.68 MB` |
| `Baseline Heap` | `291.96 MB` |
| `Heap Growth` | `74.00 MB` |



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Processes Created` | `10,000` |
| `Bytes Per Process` | `12733.22 bytes` |
| `KB Per Process` | `12.43 KB` |
| `After Creation Heap` | `444.07 MB` |
| `Creation Time` | `0.11 sec` |
| `Baseline Heap` | `322.64 MB` |
| `Heap Growth` | `121.43 MB` |
| `Messages Per Process` | `10` |
| `Total Messages` | `100,000` |



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Processes Created` | `1,000` |
| `Bytes Per Process` | `165502.17 bytes` |
| `KB Per Process` | `161.62 KB` |
| `After Creation Heap` | `539.97 MB` |
| `Creation Time` | `0.00 sec` |
| `GC Stabilization Time` | `3.28 sec` |
| `Cleanup Time` | `19.63 sec` |
| `Baseline Heap` | `382.14 MB` |
| `Heap Growth` | `157.84 MB` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Peak Heap` | `539.97 MB` |
| `Final Heap` | `823.68 MB` |
| `Baseline Heap` | `382.14 MB` |
| `Heap Growth` | `157.84 MB` |



Analysis:

> [!NOTE]
> Message overhead: 874.99 bytes per message



Memory Summary:

| Key | Value |
| --- | --- |
| `Peak Heap` | `322.64 MB` |
| `Final Heap` | `825.68 MB` |
| `Baseline Heap` | `322.64 MB` |
| `Heap Growth` | `0.00 MB` |



Analysis:

> [!NOTE]
> State object overhead: -9.41 KB per process



Memory Summary:

| Key | Value |
| --- | --- |
| `Peak Heap` | `347.55 MB` |
| `Final Heap` | `845.68 MB` |
| `Baseline Heap` | `347.55 MB` |
| `Heap Growth` | `0.00 MB` |



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Processes Created` | `100,000` |
| `Bytes Per Process` | `1851.00 bytes` |
| `KB Per Process` | `1.81 KB` |
| `After Creation Heap` | `308.51 MB` |
| `Creation Time` | `2.52 sec` |
| `GC Stabilization Time` | `2.88 sec` |
| `Cleanup Time` | `35.60 sec` |
| `Baseline Heap` | `131.98 MB` |
| `Heap Growth` | `176.53 MB` |



Validation:

| Key | Value |
| --- | --- |
| `Actual` | `1.81 KB/process` |
| `Status` | `PASS (with tolerance)` |
| `Note` | `Outside ideal range but within acceptable JVM variance` |
| `Claim` | `~1KB per process` |



Memory Summary:

| Key | Value |
| --- | --- |
| `Peak Heap` | `308.51 MB` |
| `Final Heap` | `844.68 MB` |
| `Baseline Heap` | `131.98 MB` |
| `Heap Growth` | `176.53 MB` |

---
*Generated by [DTR](http://www.dtr.org)*
