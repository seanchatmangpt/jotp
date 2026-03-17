# io.github.seanchatmangpt.jotp.validation.ProcessMemoryAnalysisTest

## Table of Contents

- [10K Process Memory Analysis](#10kprocessmemoryanalysis)
- [Process with Mailbox Messages Memory Analysis](#processwithmailboxmessagesmemoryanalysis)
- [100K Process Memory Analysis](#100kprocessmemoryanalysis)
- [Process with Small State Memory Analysis](#processwithsmallstatememoryanalysis)
- [1K Process Memory Analysis](#1kprocessmemoryanalysis)
- [100 Process Memory Analysis](#100processmemoryanalysis)
- [Empty Process Memory Analysis](#emptyprocessmemoryanalysis)


## 10K Process Memory Analysis

Validating memory footprint for 10,000 processes



Expected: ~10-12 MB total (~1KB per process)



Test Configuration:

| Key | Value |
| --- | --- |
| `Target Processes` | `10,000` |
| `JVM Max Heap` | `3.93 GB` |



Phase 1: Creating processes...

  Created 10,000 processes (100.0%)



Phase 2: Forcing GC to stabilize measurement...



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Baseline Heap` | `257.07 MB` |
| `Heap Growth` | `57.70 MB` |
| `Processes Created` | `10,000` |
| `Bytes Per Process` | `6050.49 bytes` |
| `KB Per Process` | `5.91 KB` |
| `After Creation Heap` | `314.78 MB` |
| `Creation Time` | `0.04 sec` |
| `GC Stabilization Time` | `1.19 sec` |
| `Cleanup Time` | `0.52 sec` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Heap Growth` | `57.70 MB` |
| `Peak Heap` | `314.78 MB` |
| `Final Heap` | `472.78 MB` |
| `Baseline Heap` | `257.07 MB` |

## Process with Mailbox Messages Memory Analysis

Validating memory footprint for 10,000 processes with messages in mailboxes



Expected: ~5-7 KB total (includes 10 messages per process)



Phase 1: Creating processes and populating mailboxes...

## 100K Process Memory Analysis

Validating memory footprint for 100,000 processes



Expected: ~80-120 MB total (~1KB per process)



> [!NOTE]
> This test demonstrates virtual thread scalability at scale.



Test Configuration:

| Key | Value |
| --- | --- |
| `Target Processes` | `100,000` |
| `JVM Max Heap` | `3.93 GB` |



Phase 1: Creating processes...

  Created 10,000 processes (10.0%)

  Created 20,000 processes (20.0%)

  Created 30,000 processes (30.0%)

  Created 40,000 processes (40.0%)



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Baseline Heap` | `284.94 MB` |
| `Heap Growth` | `-50.60 MB` |
| `Messages Per Process` | `10` |
| `Total Messages` | `100,000` |
| `Processes Created` | `10,000` |
| `Bytes Per Process` | `-5306.22 bytes` |
| `KB Per Process` | `-5.18 KB` |
| `After Creation Heap` | `234.34 MB` |
| `Creation Time` | `0.04 sec` |

  Created 50,000 processes (50.0%)

  Created 60,000 processes (60.0%)

  Created 70,000 processes (70.0%)

  Created 80,000 processes (80.0%)

  Created 90,000 processes (90.0%)

  Created 100,000 processes (100.0%)



Phase 2: Forcing GC to stabilize measurement...



Analysis:

> [!NOTE]
> Message overhead: -928.96 bytes per message



Memory Summary:

| Key | Value |
| --- | --- |
| `Heap Growth` | `0.00 MB` |
| `Peak Heap` | `284.94 MB` |
| `Final Heap` | `386.46 MB` |
| `Baseline Heap` | `284.94 MB` |



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...

## Process with Small State Memory Analysis

Validating memory footprint for 10,000 processes with small state objects



Expected: ~4-5 KB total (includes ~100-byte state object)



Phase 1: Creating processes with SmallState...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `KB Per Process` | `-6.01 KB` |
| `Processes Created` | `10,000` |
| `Baseline Heap` | `452.49 MB` |
| `Bytes Per Process` | `-6155.48 bytes` |
| `State Type` | `SmallState (3 fields)` |
| `After Creation Heap` | `393.79 MB` |
| `Heap Growth` | `-58.70 MB` |
| `Creation Time` | `0.02 sec` |



Analysis:

> [!NOTE]
> State object overhead: -9.90 KB per process



Memory Summary:

| Key | Value |
| --- | --- |
| `Heap Growth` | `0.00 MB` |
| `Peak Heap` | `452.49 MB` |
| `Final Heap` | `521.79 MB` |
| `Baseline Heap` | `452.49 MB` |



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Baseline Heap` | `240.34 MB` |
| `Heap Growth` | `194.15 MB` |
| `Processes Created` | `100,000` |
| `Bytes Per Process` | `2035.83 bytes` |
| `KB Per Process` | `1.99 KB` |
| `After Creation Heap` | `434.49 MB` |
| `Creation Time` | `0.21 sec` |
| `GC Stabilization Time` | `1.50 sec` |
| `Cleanup Time` | `2.33 sec` |



Validation:

| Key | Value |
| --- | --- |
| `Status` | `PASS (with tolerance)` |
| `Note` | `Outside ideal range but within acceptable JVM variance` |
| `Claim` | `~1KB per process` |
| `Actual` | `1.99 KB/process` |



Memory Summary:

| Key | Value |
| --- | --- |
| `Heap Growth` | `194.15 MB` |
| `Peak Heap` | `434.49 MB` |
| `Final Heap` | `236.10 MB` |
| `Baseline Heap` | `240.34 MB` |

## 1K Process Memory Analysis

Validating memory footprint for 1,000 processes



Expected: ~1-1.2 MB total (~1KB per process)



Test Configuration:

| Key | Value |
| --- | --- |
| `Target Processes` | `1,000` |
| `JVM Max Heap` | `3.93 GB` |



Phase 1: Creating processes...



Phase 2: Forcing GC to stabilize measurement...

## 100 Process Memory Analysis

Validating memory footprint for 100 processes



Expected: ~100-120 KB total (~1KB per process)



Test Configuration:

| Key | Value |
| --- | --- |
| `Target Processes` | `100` |
| `JVM Max Heap` | `3.93 GB` |



Phase 1: Creating processes...



Phase 2: Forcing GC to stabilize measurement...

## Empty Process Memory Analysis

Validating memory footprint for 10,000 empty processes (no state, no messages)



Expected: ~3.5-4.5 KB total (virtual thread + mailbox overhead only)



Test Configuration:

| Key | Value |
| --- | --- |
| `Target Processes` | `10,000` |
| `JVM Max Heap` | `3.93 GB` |



Phase 1: Creating processes...

  Created 10,000 processes (100.0%)



Phase 2: Forcing GC to stabilize measurement...



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Baseline Heap` | `172.09 MB` |
| `Heap Growth` | `3.66 MB` |
| `Processes Created` | `1,000` |
| `Bytes Per Process` | `3838.98 bytes` |
| `KB Per Process` | `3.75 KB` |
| `After Creation Heap` | `175.75 MB` |
| `Creation Time` | `0.00 sec` |
| `GC Stabilization Time` | `1.17 sec` |
| `Cleanup Time` | `0.05 sec` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Heap Growth` | `3.66 MB` |
| `Peak Heap` | `175.75 MB` |
| `Final Heap` | `183.75 MB` |
| `Baseline Heap` | `172.09 MB` |



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Baseline Heap` | `133.32 MB` |
| `Heap Growth` | `74.43 MB` |
| `Processes Created` | `100` |
| `Bytes Per Process` | `780463.20 bytes` |
| `KB Per Process` | `762.17 KB` |
| `After Creation Heap` | `207.75 MB` |
| `Creation Time` | `0.00 sec` |
| `GC Stabilization Time` | `1.23 sec` |
| `Cleanup Time` | `0.00 sec` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Heap Growth` | `74.43 MB` |
| `Peak Heap` | `207.75 MB` |
| `Final Heap` | `207.75 MB` |
| `Baseline Heap` | `133.32 MB` |



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Baseline Heap` | `189.76 MB` |
| `Heap Growth` | `39.99 MB` |
| `Processes Created` | `10,000` |
| `Bytes Per Process` | `4193.34 bytes` |
| `KB Per Process` | `4.10 KB` |
| `After Creation Heap` | `229.75 MB` |
| `Creation Time` | `0.01 sec` |
| `GC Stabilization Time` | `1.21 sec` |
| `Cleanup Time` | `0.21 sec` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Heap Growth` | `39.99 MB` |
| `Peak Heap` | `229.75 MB` |
| `Final Heap` | `257.75 MB` |
| `Baseline Heap` | `189.76 MB` |

---
*Generated by [DTR](http://www.dtr.org)*
