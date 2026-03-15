package io.github.seanchatmangpt.jotp.benchmark.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link BenchmarkReport}. */
class BenchmarkReportTest {

    @Test
    void testEmptyReport() {
        BenchmarkReport report = BenchmarkReport.empty();

        assertThat(report.results()).isEmpty();
        assertThat(report.generatedAt()).isNotNull();
        assertThat(report.metadata().jvmVersion()).isEqualTo("Unknown");
    }

    @Test
    void testGenerateJsonReport() {
        BenchmarkReport report = BenchmarkReport.empty();
        String json = report.generateJsonReport();

        assertThat(json).isNotEmpty();
        assertThat(json).contains("\"generatedAt\"");
        assertThat(json).contains("\"results\"");
        assertThat(json).contains("\"summary\"");
    }

    @Test
    void testGenerateHtmlReport() {
        BenchmarkReport report = BenchmarkReport.empty();
        String html = report.generateHtmlReport();

        assertThat(html).isNotEmpty();
        assertThat(html).contains("<!DOCTYPE html>");
        assertThat(html).contains("<title>JOTP Benchmark Report</title>");
        assertThat(html).contains("Executive Summary");
        assertThat(html).contains("Detailed Results");
    }

    @Test
    void testTrendAnalysisWithNoPreviousRuns() {
        BenchmarkReport report = BenchmarkReport.empty();
        BenchmarkReport.TrendAnalysis trends = report.calculateTrends(List.of());

        assertThat(trends.analyzedAt()).isNotNull();
        assertThat(trends.metricHistory()).isEmpty();
    }

    @Test
    void testFindResultReturnsEmptyForUnknownBenchmark() {
        BenchmarkReport report = BenchmarkReport.empty();
        Optional<BenchmarkReport.BenchmarkResult> result = report.findResult("unknown");

        assertThat(result).isEmpty();
    }

    @Test
    void testBenchmarkRecord() {
        BenchmarkReport.BenchmarkResult result =
                new BenchmarkReport.BenchmarkResult(
                        "test.Benchmark",
                        "Throughput",
                        4,
                        2,
                        10000.0,
                        100.0,
                        1.0,
                        Map.of("95.0", 1.5, "99.0", 2.5),
                        "OpenJDK 26",
                        "OpenJDK 64-Bit");

        assertThat(result.name()).isEqualTo("test.Benchmark");
        assertThat(result.mode()).isEqualTo("Throughput");
        assertThat(result.threads()).isEqualTo(4);
        assertThat(result.score()).isEqualTo(10000.0);

        Optional<Double> p95 = result.getPercentile("95.0");
        assertThat(p95).hasValue(1.5);

        Optional<Double> p999 = result.getPercentile("99.9");
        assertThat(p999).isEmpty();
    }

    @Test
    void testMetadataRecord() {
        BenchmarkReport.Metadata metadata =
                new BenchmarkReport.Metadata(
                        "OpenJDK 26", "OpenJDK 64-Bit", "Linux", "x86_64", 8, "1.0.0");

        assertThat(metadata.jvmVersion()).isEqualTo("OpenJDK 26");
        assertThat(metadata.availableProcessors()).isEqualTo(8);

        Map<String, Object> map = metadata.toMap();
        assertThat(map).hasSize(6);
        assertThat(map.get("jvmVersion")).isEqualTo("OpenJDK 26");
    }

    @Test
    void testWriteToJsonFile(@TempDir Path tempDir) throws Exception {
        BenchmarkReport report = BenchmarkReport.empty();
        Path outputFile = tempDir.resolve("report.json");

        report.writeTo(outputFile, BenchmarkReport.ReportFormat.JSON);

        assertThat(outputFile).isRegularFile();
        String content = Files.readString(outputFile);
        assertThat(content).contains("\"generatedAt\"");
    }

    @Test
    void testWriteToHtmlFile(@TempDir Path tempDir) throws Exception {
        BenchmarkReport report = BenchmarkReport.empty();
        Path outputFile = tempDir.resolve("report.html");

        report.writeTo(outputFile, BenchmarkReport.ReportFormat.HTML);

        assertThat(outputFile).isRegularFile();
        String content = Files.readString(outputFile);
        assertThat(content).contains("<!DOCTYPE html>");
    }
}
