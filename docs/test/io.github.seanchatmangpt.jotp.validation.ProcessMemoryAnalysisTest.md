# io.github.seanchatmangpt.jotp.validation.ProcessMemoryAnalysisTest

## Table of Contents

- [10K Process Memory Analysis](#10kprocessmemoryanalysis)
- [100K Process Memory Analysis](#100kprocessmemoryanalysis)
- [Process with Mailbox Messages Memory Analysis](#processwithmailboxmessagesmemoryanalysis)
- [Process with Small State Memory Analysis](#processwithsmallstatememoryanalysis)
- [100 Process Memory Analysis](#100processmemoryanalysis)
- [1K Process Memory Analysis](#1kprocessmemoryanalysis)
- [Empty Process Memory Analysis](#emptyprocessmemoryanalysis)


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



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Heap Growth` | `79.70 MB` |
| `Baseline Heap` | `188.95 MB` |
| `Cleanup Time` | `0.77 sec` |
| `GC Stabilization Time` | `1.24 sec` |
| `Creation Time` | `0.01 sec` |
| `After Creation Heap` | `268.65 MB` |
| `KB Per Process` | `8.16 KB` |
| `Bytes Per Process` | `8357.28 bytes` |
| `Processes Created` | `10,000` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Heap Growth` | `79.70 MB` |
| `Baseline Heap` | `188.95 MB` |
| `Final Heap` | `443.34 MB` |
| `Peak Heap` | `268.65 MB` |



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...

## Process with Mailbox Messages Memory Analysis

Validating memory footprint for 10,000 processes with messages in mailboxes



Expected: ~5-7 KB total (includes 10 messages per process)



Phase 1: Creating processes and populating mailboxes...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Total Messages` | `100,000` |
| `Messages Per Process` | `10` |
| `Heap Growth` | `45.19 MB` |
| `Baseline Heap` | `503.45 MB` |
| `Creation Time` | `0.05 sec` |
| `After Creation Heap` | `548.64 MB` |
| `KB Per Process` | `4.63 KB` |
| `Bytes Per Process` | `4738.21 bytes` |
| `Processes Created` | `10,000` |



Analysis:

> [!NOTE]
> Message overhead: 75.49 bytes per message



Memory Summary:

| Key | Value |
| --- | --- |
| `Heap Growth` | `0.00 MB` |
| `Baseline Heap` | `503.45 MB` |
| `Final Heap` | `358.10 MB` |
| `Peak Heap` | `503.45 MB` |



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Heap Growth` | `60.43 MB` |
| `Baseline Heap` | `255.02 MB` |
| `Cleanup Time` | `2.37 sec` |
| `GC Stabilization Time` | `1.41 sec` |
| `Creation Time` | `0.17 sec` |
| `After Creation Heap` | `315.45 MB` |
| `KB Per Process` | `0.62 KB` |
| `Bytes Per Process` | `633.65 bytes` |
| `Processes Created` | `100,000` |



Validation:

| Key | Value |
| --- | --- |
| `Note` | `Outside ideal range but within acceptable JVM variance` |
| `Status` | `PASS (with tolerance)` |
| `Actual` | `0.62 KB/process` |
| `Claim` | `~1KB per process` |



Memory Summary:

| Key | Value |
| --- | --- |
| `Heap Growth` | `72.00 MB` |
| `Baseline Heap` | `255.02 MB` |
| `Final Heap` | `226.89 MB` |
| `Peak Heap` | `327.02 MB` |

## Process with Small State Memory Analysis

Validating memory footprint for 10,000 processes with small state objects



Expected: ~4-5 KB total (includes ~100-byte state object)



Phase 1: Creating processes with SmallState...

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
| `Bytes Per Process` | `2622.78 bytes` |
| `Baseline Heap` | `212.51 MB` |
| `Processes Created` | `10,000` |
| `KB Per Process` | `2.56 KB` |
| `Creation Time` | `0.00 sec` |
| `Heap Growth` | `25.01 MB` |
| `After Creation Heap` | `237.52 MB` |
| `State Type` | `SmallState (3 fields)` |



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...



Analysis:

> [!NOTE]
> State object overhead: -1.33 KB per process



Memory Summary:

| Key | Value |
| --- | --- |
| `Heap Growth` | `0.00 MB` |
| `Baseline Heap` | `212.51 MB` |
| `Final Heap` | `289.52 MB` |
| `Peak Heap` | `212.51 MB` |



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Heap Growth` | `148.09 MB` |
| `Baseline Heap` | `141.43 MB` |
| `Cleanup Time` | `0.11 sec` |
| `GC Stabilization Time` | `1.19 sec` |
| `Creation Time` | `0.00 sec` |
| `After Creation Heap` | `289.52 MB` |
| `KB Per Process` | `1516.48 KB` |
| `Bytes Per Process` | `1552874.88 bytes` |
| `Processes Created` | `100` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Heap Growth` | `148.09 MB` |
| `Baseline Heap` | `141.43 MB` |
| `Final Heap` | `132.79 MB` |
| `Peak Heap` | `289.52 MB` |

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
| `Heap Growth` | `47.44 MB` |
| `Baseline Heap` | `214.97 MB` |
| `Cleanup Time` | `0.02 sec` |
| `GC Stabilization Time` | `1.19 sec` |
| `Creation Time` | `0.00 sec` |
| `After Creation Heap` | `262.40 MB` |
| `KB Per Process` | `48.57 KB` |
| `Bytes Per Process` | `49739.58 bytes` |
| `Processes Created` | `1,000` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Heap Growth` | `47.44 MB` |
| `Baseline Heap` | `214.97 MB` |
| `Final Heap` | `266.40 MB` |
| `Peak Heap` | `262.40 MB` |



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Heap Growth` | `57.84 MB` |
| `Baseline Heap` | `129.47 MB` |
| `Cleanup Time` | `0.19 sec` |
| `GC Stabilization Time` | `1.16 sec` |
| `Creation Time` | `0.01 sec` |
| `After Creation Heap` | `187.31 MB` |
| `KB Per Process` | `5.92 KB` |
| `Bytes Per Process` | `6064.80 bytes` |
| `Processes Created` | `10,000` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Heap Growth` | `57.84 MB` |
| `Baseline Heap` | `129.47 MB` |
| `Final Heap` | `227.31 MB` |
| `Peak Heap` | `187.31 MB` |

---
*Generated by [DTR](http://www.dtr.org)*
