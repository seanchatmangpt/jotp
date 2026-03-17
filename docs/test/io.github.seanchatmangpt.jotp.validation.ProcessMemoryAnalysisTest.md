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



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `KB Per Process` | `617.81 KB` |
| `Bytes Per Process` | `632636.64 bytes` |
| `Processes Created` | `100` |
| `Heap Growth` | `60.33 MB` |
| `Baseline Heap` | `386.62 MB` |
| `Cleanup Time` | `0.07 sec` |
| `GC Stabilization Time` | `2.42 sec` |
| `Creation Time` | `0.00 sec` |
| `After Creation Heap` | `446.95 MB` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Final Heap` | `472.95 MB` |
| `Peak Heap` | `446.95 MB` |
| `Heap Growth` | `60.33 MB` |
| `Baseline Heap` | `386.62 MB` |

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
| `KB Per Process` | `-9.95 KB` |
| `Bytes Per Process` | `-10192.04 bytes` |
| `Processes Created` | `10,000` |
| `Heap Growth` | `-97.20 MB` |
| `Baseline Heap` | `443.45 MB` |
| `Cleanup Time` | `1.69 sec` |
| `GC Stabilization Time` | `2.39 sec` |
| `Creation Time` | `0.01 sec` |
| `After Creation Heap` | `346.25 MB` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Final Heap` | `316.77 MB` |
| `Peak Heap` | `453.45 MB` |
| `Heap Growth` | `10.00 MB` |
| `Baseline Heap` | `443.45 MB` |



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `KB Per Process` | `-1.52 KB` |
| `Bytes Per Process` | `-1559.09 bytes` |
| `Processes Created` | `1,000` |
| `Heap Growth` | `-1.49 MB` |
| `Baseline Heap` | `413.25 MB` |
| `Cleanup Time` | `1.47 sec` |
| `GC Stabilization Time` | `2.00 sec` |
| `Creation Time` | `0.00 sec` |
| `After Creation Heap` | `411.77 MB` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Final Heap` | `365.77 MB` |
| `Peak Heap` | `413.25 MB` |
| `Heap Growth` | `0.00 MB` |
| `Baseline Heap` | `413.25 MB` |

## Process with Small State Memory Analysis

Validating memory footprint for 10,000 processes with small state objects



Expected: ~4-5 KB total (includes ~100-byte state object)



Phase 1: Creating processes with SmallState...

## Process with Mailbox Messages Memory Analysis

Validating memory footprint for 10,000 processes with messages in mailboxes



Expected: ~5-7 KB total (includes 10 messages per process)



Phase 1: Creating processes and populating mailboxes...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Heap Growth` | `18.29 MB` |
| `After Creation Heap` | `307.61 MB` |
| `State Type` | `SmallState (3 fields)` |
| `Bytes Per Process` | `1918.35 bytes` |
| `Baseline Heap` | `289.31 MB` |
| `Processes Created` | `10,000` |
| `KB Per Process` | `1.87 KB` |
| `Creation Time` | `0.01 sec` |



Analysis:

> [!NOTE]
> State object overhead: -2.02 KB per process



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `KB Per Process` | `14.96 KB` |
| `Bytes Per Process` | `15318.04 bytes` |
| `Processes Created` | `10,000` |
| `Total Messages` | `100,000` |
| `Messages Per Process` | `10` |
| `Heap Growth` | `146.08 MB` |
| `Baseline Heap` | `298.52 MB` |
| `Creation Time` | `0.01 sec` |
| `After Creation Heap` | `444.61 MB` |



Memory Summary:

| Key | Value |
| --- | --- |
| `Final Heap` | `450.61 MB` |
| `Peak Heap` | `289.31 MB` |
| `Heap Growth` | `0.00 MB` |
| `Baseline Heap` | `289.31 MB` |



Analysis:

> [!NOTE]
> Message overhead: 1133.47 bytes per message



Memory Summary:

| Key | Value |
| --- | --- |
| `Final Heap` | `486.61 MB` |
| `Peak Heap` | `298.52 MB` |
| `Heap Growth` | `0.00 MB` |
| `Baseline Heap` | `298.52 MB` |



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `KB Per Process` | `9.26 KB` |
| `Bytes Per Process` | `9485.02 bytes` |
| `Processes Created` | `10,000` |
| `Heap Growth` | `90.46 MB` |
| `Baseline Heap` | `160.51 MB` |
| `Cleanup Time` | `16.68 sec` |
| `GC Stabilization Time` | `1.55 sec` |
| `Creation Time` | `0.03 sec` |
| `After Creation Heap` | `250.97 MB` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Final Heap` | `586.61 MB` |
| `Peak Heap` | `250.97 MB` |
| `Heap Growth` | `90.46 MB` |
| `Baseline Heap` | `160.51 MB` |



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `KB Per Process` | `1.57 KB` |
| `Bytes Per Process` | `1609.11 bytes` |
| `Processes Created` | `100,000` |
| `Heap Growth` | `153.46 MB` |
| `Baseline Heap` | `168.51 MB` |
| `Cleanup Time` | `16.49 sec` |
| `GC Stabilization Time` | `2.07 sec` |
| `Creation Time` | `0.37 sec` |
| `After Creation Heap` | `321.97 MB` |



Validation:

| Key | Value |
| --- | --- |
| `Actual` | `1.57 KB/process` |
| `Claim` | `~1KB per process` |
| `Note` | `Outside ideal range but within acceptable JVM variance` |
| `Status` | `PASS (with tolerance)` |



Memory Summary:

| Key | Value |
| --- | --- |
| `Final Heap` | `587.61 MB` |
| `Peak Heap` | `321.97 MB` |
| `Heap Growth` | `153.46 MB` |
| `Baseline Heap` | `168.51 MB` |

---
*Generated by [DTR](http://www.dtr.org)*
