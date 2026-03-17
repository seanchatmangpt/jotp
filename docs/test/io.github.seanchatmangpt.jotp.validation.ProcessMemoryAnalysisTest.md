# io.github.seanchatmangpt.jotp.validation.ProcessMemoryAnalysisTest

## Table of Contents

- [10K Process Memory Analysis](#10kprocessmemoryanalysis)
- [100K Process Memory Analysis](#100kprocessmemoryanalysis)
- [100 Process Memory Analysis](#100processmemoryanalysis)
- [Empty Process Memory Analysis](#emptyprocessmemoryanalysis)
- [Process with Mailbox Messages Memory Analysis](#processwithmailboxmessagesmemoryanalysis)
- [1K Process Memory Analysis](#1kprocessmemoryanalysis)
- [Process with Small State Memory Analysis](#processwithsmallstatememoryanalysis)


## 10K Process Memory Analysis

Validating memory footprint for 10,000 processes



Expected: ~10-12 MB total (~1KB per process)



Test Configuration:

| Key | Value |
| --- | --- |
| `JVM Max Heap` | `12.00 GB` |
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
| `JVM Max Heap` | `12.00 GB` |
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



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Heap Growth` | `3.01 MB` |
| `Baseline Heap` | `262.48 MB` |
| `Cleanup Time` | `1.20 sec` |
| `GC Stabilization Time` | `1.32 sec` |
| `Creation Time` | `0.00 sec` |
| `After Creation Heap` | `265.49 MB` |
| `KB Per Process` | `0.31 KB` |
| `Bytes Per Process` | `315.29 bytes` |
| `Processes Created` | `10,000` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Heap Growth` | `72.00 MB` |
| `Baseline Heap` | `262.48 MB` |
| `Final Heap` | `811.55 MB` |
| `Peak Heap` | `334.48 MB` |

## 100 Process Memory Analysis

Validating memory footprint for 100 processes



Expected: ~100-120 KB total (~1KB per process)



Test Configuration:

| Key | Value |
| --- | --- |
| `JVM Max Heap` | `12.00 GB` |
| `Target Processes` | `100` |



Phase 1: Creating processes...



Phase 2: Forcing GC to stabilize measurement...

## Empty Process Memory Analysis

Validating memory footprint for 10,000 empty processes (no state, no messages)



Expected: ~3.5-4.5 KB total (virtual thread + mailbox overhead only)



Test Configuration:

| Key | Value |
| --- | --- |
| `JVM Max Heap` | `12.00 GB` |
| `Target Processes` | `10,000` |



Phase 1: Creating processes...

  Created 10,000 processes (100.0%)



Phase 2: Forcing GC to stabilize measurement...

## Process with Mailbox Messages Memory Analysis

Validating memory footprint for 10,000 processes with messages in mailboxes



Expected: ~5-7 KB total (includes 10 messages per process)



Phase 1: Creating processes and populating mailboxes...

## 1K Process Memory Analysis

Validating memory footprint for 1,000 processes



Expected: ~1-1.2 MB total (~1KB per process)



Test Configuration:

| Key | Value |
| --- | --- |
| `JVM Max Heap` | `12.00 GB` |
| `Target Processes` | `1,000` |



Phase 1: Creating processes...



Phase 2: Forcing GC to stabilize measurement...



Phase 3: Measuring memory footprint...



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...



Phase 4: Cleaning up processes...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Heap Growth` | `29.41 MB` |
| `Baseline Heap` | `299.21 MB` |
| `Cleanup Time` | `0.31 sec` |
| `GC Stabilization Time` | `1.65 sec` |
| `Creation Time` | `0.00 sec` |
| `After Creation Heap` | `328.62 MB` |
| `KB Per Process` | `301.11 KB` |
| `Bytes Per Process` | `308339.04 bytes` |
| `Processes Created` | `100` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Heap Growth` | `29.41 MB` |
| `Baseline Heap` | `299.21 MB` |
| `Final Heap` | `513.43 MB` |
| `Peak Heap` | `328.62 MB` |

## Process with Small State Memory Analysis

Validating memory footprint for 10,000 processes with small state objects



Expected: ~4-5 KB total (includes ~100-byte state object)



Phase 1: Creating processes with SmallState...



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Total Messages` | `100,000` |
| `Messages Per Process` | `10` |
| `Heap Growth` | `31.36 MB` |
| `Baseline Heap` | `308.90 MB` |
| `Creation Time` | `0.01 sec` |
| `After Creation Heap` | `340.26 MB` |
| `KB Per Process` | `3.21 KB` |
| `Bytes Per Process` | `3288.65 bytes` |
| `Processes Created` | `10,000` |



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Heap Growth` | `-1.51 MB` |
| `Baseline Heap` | `333.78 MB` |
| `Cleanup Time` | `0.06 sec` |
| `GC Stabilization Time` | `1.97 sec` |
| `Creation Time` | `0.00 sec` |
| `After Creation Heap` | `332.26 MB` |
| `KB Per Process` | `-1.55 KB` |
| `Bytes Per Process` | `-1587.84 bytes` |
| `Processes Created` | `1,000` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Heap Growth` | `0.00 MB` |
| `Baseline Heap` | `333.78 MB` |
| `Final Heap` | `428.26 MB` |
| `Peak Heap` | `333.78 MB` |



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Heap Growth` | `28.86 MB` |
| `Baseline Heap` | `299.76 MB` |
| `Cleanup Time` | `1.31 sec` |
| `GC Stabilization Time` | `1.45 sec` |
| `Creation Time` | `0.01 sec` |
| `After Creation Heap` | `328.62 MB` |
| `KB Per Process` | `2.96 KB` |
| `Bytes Per Process` | `3026.00 bytes` |
| `Processes Created` | `10,000` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Heap Growth` | `33.14 MB` |
| `Baseline Heap` | `299.76 MB` |
| `Final Heap` | `436.26 MB` |
| `Peak Heap` | `332.90 MB` |



Analysis:

> [!NOTE]
> Message overhead: -69.47 bytes per message



Memory Summary:

| Key | Value |
| --- | --- |
| `Heap Growth` | `0.00 MB` |
| `Baseline Heap` | `308.90 MB` |
| `Final Heap` | `556.26 MB` |
| `Peak Heap` | `308.90 MB` |



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Baseline Heap` | `520.69 MB` |
| `Processes Created` | `10,000` |
| `KB Per Process` | `21.66 KB` |
| `Creation Time` | `0.01 sec` |
| `Heap Growth` | `211.57 MB` |
| `After Creation Heap` | `732.26 MB` |
| `State Type` | `SmallState (3 fields)` |
| `Bytes Per Process` | `22184.64 bytes` |



Analysis:

> [!NOTE]
> State object overhead: 17.77 KB per process



Memory Summary:

| Key | Value |
| --- | --- |
| `Heap Growth` | `0.00 MB` |
| `Baseline Heap` | `520.69 MB` |
| `Final Heap` | `505.01 MB` |
| `Peak Heap` | `520.69 MB` |



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Heap Growth` | `-514.55 MB` |
| `Baseline Heap` | `822.10 MB` |
| `Cleanup Time` | `6.42 sec` |
| `GC Stabilization Time` | `1.59 sec` |
| `Creation Time` | `0.07 sec` |
| `After Creation Heap` | `307.55 MB` |
| `KB Per Process` | `-5.27 KB` |
| `Bytes Per Process` | `-5395.46 bytes` |
| `Processes Created` | `100,000` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Heap Growth` | `224.00 MB` |
| `Baseline Heap` | `822.10 MB` |
| `Final Heap` | `841.01 MB` |
| `Peak Heap` | `1046.10 MB` |

---
*Generated by [DTR](http://www.dtr.org)*
