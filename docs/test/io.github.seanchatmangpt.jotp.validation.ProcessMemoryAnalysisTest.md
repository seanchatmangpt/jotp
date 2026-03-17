# io.github.seanchatmangpt.jotp.validation.ProcessMemoryAnalysisTest

## Table of Contents

- [10K Process Memory Analysis](#10kprocessmemoryanalysis)
- [100K Process Memory Analysis](#100kprocessmemoryanalysis)
- [100 Process Memory Analysis](#100processmemoryanalysis)
- [Process with Mailbox Messages Memory Analysis](#processwithmailboxmessagesmemoryanalysis)
- [Empty Process Memory Analysis](#emptyprocessmemoryanalysis)
- [Process with Small State Memory Analysis](#processwithsmallstatememoryanalysis)
- [1K Process Memory Analysis](#1kprocessmemoryanalysis)


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
| `Target Processes` | `100` |
| `JVM Max Heap` | `3.93 GB` |



Phase 1: Creating processes...



Phase 2: Forcing GC to stabilize measurement...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `KB Per Process` | `7.11 KB` |
| `Bytes Per Process` | `7284.77 bytes` |
| `Processes Created` | `10,000` |
| `Heap Growth` | `69.47 MB` |
| `Baseline Heap` | `185.99 MB` |
| `Cleanup Time` | `0.65 sec` |
| `GC Stabilization Time` | `1.41 sec` |
| `Creation Time` | `0.03 sec` |
| `After Creation Heap` | `255.46 MB` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Peak Heap` | `255.46 MB` |
| `Heap Growth` | `69.47 MB` |
| `Baseline Heap` | `185.99 MB` |
| `Final Heap` | `325.99 MB` |



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...

## Process with Mailbox Messages Memory Analysis

Validating memory footprint for 10,000 processes with messages in mailboxes



Expected: ~5-7 KB total (includes 10 messages per process)



Phase 1: Creating processes and populating mailboxes...



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `KB Per Process` | `1244.25 KB` |
| `Bytes Per Process` | `1274112.24 bytes` |
| `Processes Created` | `100` |
| `Heap Growth` | `121.51 MB` |
| `Baseline Heap` | `251.59 MB` |
| `Cleanup Time` | `0.51 sec` |
| `GC Stabilization Time` | `1.59 sec` |
| `Creation Time` | `0.00 sec` |
| `After Creation Heap` | `373.09 MB` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Peak Heap` | `373.09 MB` |
| `Heap Growth` | `121.51 MB` |
| `Baseline Heap` | `251.59 MB` |
| `Final Heap` | `289.79 MB` |

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



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `KB Per Process` | `-14.37 KB` |
| `Bytes Per Process` | `-14712.27 bytes` |
| `Processes Created` | `10,000` |
| `Total Messages` | `100,000` |
| `Messages Per Process` | `10` |
| `Heap Growth` | `-140.31 MB` |
| `Baseline Heap` | `427.10 MB` |
| `Creation Time` | `0.18 sec` |
| `After Creation Heap` | `286.79 MB` |



Analysis:

> [!NOTE]
> Message overhead: -1869.56 bytes per message



Memory Summary:

| Key | Value |
| --- | --- |
| `Peak Heap` | `427.10 MB` |
| `Heap Growth` | `0.00 MB` |
| `Baseline Heap` | `427.10 MB` |
| `Final Heap` | `378.79 MB` |



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `KB Per Process` | `-11.19 KB` |
| `Bytes Per Process` | `-11455.17 bytes` |
| `Processes Created` | `10,000` |
| `Heap Growth` | `-109.24 MB` |
| `Baseline Heap` | `425.81 MB` |
| `Cleanup Time` | `0.13 sec` |
| `GC Stabilization Time` | `1.36 sec` |
| `Creation Time` | `0.06 sec` |
| `After Creation Heap` | `316.57 MB` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Peak Heap` | `425.81 MB` |
| `Heap Growth` | `0.00 MB` |
| `Baseline Heap` | `425.81 MB` |
| `Final Heap` | `346.57 MB` |

## Process with Small State Memory Analysis

Validating memory footprint for 10,000 processes with small state objects



Expected: ~4-5 KB total (includes ~100-byte state object)



Phase 1: Creating processes with SmallState...

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



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `KB Per Process` | `1.06 KB` |
| `Bytes Per Process` | `1086.14 bytes` |
| `Processes Created` | `100,000` |
| `Heap Growth` | `103.58 MB` |
| `Baseline Heap` | `201.51 MB` |
| `Cleanup Time` | `4.51 sec` |
| `GC Stabilization Time` | `1.59 sec` |
| `Creation Time` | `0.11 sec` |
| `After Creation Heap` | `305.10 MB` |



Validation:

| Key | Value |
| --- | --- |
| `Status` | `PASS` |
| `Actual` | `1.06 KB/process` |
| `Claim` | `~1KB per process VALIDATED` |

> [!NOTE]
> Memory footprint is within expected range!



Memory Summary:

| Key | Value |
| --- | --- |
| `Peak Heap` | `327.51 MB` |
| `Heap Growth` | `126.00 MB` |
| `Baseline Heap` | `201.51 MB` |
| `Final Heap` | `301.33 MB` |



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Heap Growth` | `54.92 MB` |
| `After Creation Heap` | `273.33 MB` |
| `State Type` | `SmallState (3 fields)` |
| `Bytes Per Process` | `5759.25 bytes` |
| `Baseline Heap` | `218.41 MB` |
| `Processes Created` | `10,000` |
| `KB Per Process` | `5.62 KB` |
| `Creation Time` | `0.01 sec` |



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `KB Per Process` | `168.54 KB` |
| `Bytes Per Process` | `172587.09 bytes` |
| `Processes Created` | `1,000` |
| `Heap Growth` | `164.59 MB` |
| `Baseline Heap` | `322.74 MB` |
| `Cleanup Time` | `0.19 sec` |
| `GC Stabilization Time` | `1.28 sec` |
| `Creation Time` | `0.00 sec` |
| `After Creation Heap` | `487.33 MB` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Peak Heap` | `487.33 MB` |
| `Heap Growth` | `164.59 MB` |
| `Baseline Heap` | `322.74 MB` |
| `Final Heap` | `343.33 MB` |



Analysis:

> [!NOTE]
> State object overhead: 1.73 KB per process



Memory Summary:

| Key | Value |
| --- | --- |
| `Peak Heap` | `218.41 MB` |
| `Heap Growth` | `0.00 MB` |
| `Baseline Heap` | `218.41 MB` |
| `Final Heap` | `391.33 MB` |

---
*Generated by [DTR](http://www.dtr.org)*
