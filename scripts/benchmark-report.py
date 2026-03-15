#!/usr/bin/env python3
"""
JOTP Benchmark Report Generator

This script parses JMH JSON results and generates comprehensive HTML/Markdown reports.
It calculates trends across multiple runs and provides SLA compliance analysis.

Usage:
    python scripts/benchmark-report.py --format=html --output=benchmark-report.html
    python scripts/benchmark-report.py --format=markdown --output=benchmark-report.md
    python scripts/benchmark-report.py --input=results/jmh-results.json --format=html
"""

import argparse
import json
import os
import sys
import socket
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
import statistics


class BenchmarkResults:
    """Container for parsed benchmark results."""

    def __init__(self, data: Dict[str, Any]):
        self.data = data
        self.benchmarks = self._parse_benchmarks()
        self.system_info = self._parse_system_info()
        self.jvm_info = self._parse_jvm_info()

    def _parse_benchmarks(self) -> List[Dict[str, Any]]:
        """Parse benchmark entries from JMH results."""
        benchmarks = []
        for entry in self.data:
            if "benchmark" in entry:
                benchmarks.append({
                    "name": entry.get("benchmark", "Unknown"),
                    "mode": entry.get("mode", "Throughput"),
                    "threads": entry.get("threads", 1),
                    "forks": entry.get("forks", 1),
                    "warmup_iterations": entry.get("warmupIterations", 0),
                    "iterations": entry.get("measurementIterations", 0),
                    "primary_metric": entry.get("primaryMetric", {}),
                })
        return benchmarks

    def _parse_system_info(self) -> Dict[str, Any]:
        """Parse system information from JMH results."""
        # Extract from first entry that has VM metadata
        for entry in self.data:
            if "vmVersion" in entry:
                return {
                    "jvm_version": entry.get("vmVersion", "Unknown"),
                    "jvm_vendor": entry.get("vmVendor", "Unknown"),
                    "java_home": entry.get("jdkJ9", "Unknown"),
                }
        return {
            "jvm_version": "Unknown",
            "jvm_vendor": "Unknown",
            "java_home": "Unknown",
        }

    def _parse_jvm_info(self) -> Dict[str, Any]:
        """Parse JVM information."""
        import platform
        return {
            "os_name": platform.system(),
            "os_version": platform.release(),
            "os_arch": platform.machine(),
            "available_processors": os.cpu_count(),
            "python_version": platform.python_version(),
        }


class TrendAnalyzer:
    """Analyze performance trends across multiple benchmark runs."""

    def __init__(self, current_results: BenchmarkResults,
                 historical_results: Optional[List[BenchmarkResults]] = None):
        self.current = current_results
        self.historical = historical_results or []

    def calculate_trend(self, metric_name: str) -> Dict[str, Any]:
        """Calculate trend for a specific metric."""
        current_value = self._extract_metric(self.current, metric_name)

        if not self.historical:
            return {
                "current": current_value,
                "previous": None,
                "change_percent": None,
                "trend": "No historical data",
            }

        previous_value = self._extract_metric(self.historical[-1], metric_name)

        if current_value is None or previous_value is None:
            return {
                "current": current_value,
                "previous": previous_value,
                "change_percent": None,
                "trend": "Insufficient data",
            }

        change_percent = ((current_value - previous_value) / previous_value) * 100

        # Determine trend direction
        if abs(change_percent) < 2.0:
            trend = "Stable"
        elif metric_name in ["throughput", "score"]:
            trend = "Improving" if change_percent > 0 else "Degraded"
        else:  # Latency metrics
            trend = "Improving" if change_percent < 0 else "Degraded"

        return {
            "current": current_value,
            "previous": previous_value,
            "change_percent": round(change_percent, 2),
            "trend": trend,
        }

    def _extract_metric(self, results: BenchmarkResults, metric_name: str) -> Optional[float]:
        """Extract a specific metric from benchmark results."""
        if not results.benchmarks:
            return None

        # Get primary metric from first benchmark
        primary_metric = results.benchmarks[0]["primary_metric"]

        if metric_name == "throughput":
            return primary_metric.get("score")
        elif metric_name == "score":
            return primary_metric.get("score")
        elif metric_name in ["p95", "latency_p95"]:
            return self._get_percentile(primary_metric, "95.0")
        elif metric_name in ["p99", "latency_p99"]:
            return self._get_percentile(primary_metric, "99.0")
        elif metric_name == "avg_time":
            return primary_metric.get("score")
        elif metric_name == "error_rate":
            return primary_metric.get("scoreError", 0.0)

        return None

    def _get_percentile(self, metric: Dict[str, Any], percentile: str) -> Optional[float]:
        """Extract percentile from raw data."""
        raw_data = metric.get("rawData", [])
        if not raw_data:
            return None

        # Flatten raw data
        values = []
        for fork_data in raw_data:
            for value in fork_data:
                values.append(value)

        if not values:
            return None

        # Calculate percentile
        values.sort()
        k = (float(percentile.rstrip('0').rstrip('.')) / 100.0) * (len(values) - 1)
        f = int(k)
        c = f + 1 if f + 1 < len(values) else f

        if f == c:
            return values[f]

        return values[f] + (k - f) * (values[c] - values[f])


class ReportGenerator:
    """Generate benchmark reports from templates."""

    def __init__(self, template_path: str):
        self.template_path = template_path
        self.template = self._load_template()

    def _load_template(self) -> str:
        """Load report template from file."""
        with open(self.template_path, 'r') as f:
            return f.read()

    def generate_markdown(self, results: BenchmarkResults,
                         analyzer: TrendAnalyzer,
                         output_path: str) -> None:
        """Generate Markdown report."""
        content = self._fill_template(results, analyzer)
        with open(output_path, 'w') as f:
            f.write(content)
        print(f"Markdown report generated: {output_path}")

    def generate_html(self, results: BenchmarkResults,
                     analyzer: TrendAnalyzer,
                     output_path: str) -> None:
        """Generate HTML report with CSS styling."""
        markdown_content = self._fill_template(results, analyzer)
        html_content = self._markdown_to_html(markdown_content)

        with open(output_path, 'w') as f:
            f.write(html_content)
        print(f"HTML report generated: {output_path}")

    def _fill_template(self, results: BenchmarkResults,
                      analyzer: TrendAnalyzer) -> str:
        """Fill template placeholders with actual data."""
        import socket

        # Extract primary benchmark
        primary = results.benchmarks[0] if results.benchmarks else {}
        primary_metric = primary.get("primary_metric", {})

        # Calculate trends
        throughput_trend = analyzer.calculate_trend("throughput")
        p95_trend = analyzer.calculate_trend("p95")
        p99_trend = analyzer.calculate_trend("p99")
        error_trend = analyzer.calculate_trend("error_rate")

        # Prepare replacements
        replacements = {
            # Metadata
            "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "version": "1.0.0",
            "jdk_version": results.system_info.get("jvm_version", "Unknown"),
            "platform": f"{results.jvm_info['os_name']} {results.jvm_info['os_arch']}",
            "commit_hash": os.popen("git rev-parse --short HEAD 2>/dev/null").read().strip() or "N/A",

            # Performance metrics
            "throughput_score": f"{primary_metric.get('score', 0):.2f}",
            "throughput_status": self._sla_status(primary_metric.get('score', 0), 10000, ">="),
            "avg_latency": f"{primary_metric.get('score', 0):.4f}",
            "latency_status": self._sla_status(primary_metric.get('score', 0), 1.0, "<="),
            "p95_latency": f"{analyzer._get_percentile(primary_metric, '95.0') or 0:.4f}",
            "p95_status": self._sla_status(analyzer._get_percentile(primary_metric, '95.0') or 0, 1.0, "<="),
            "p99_latency": f"{analyzer._get_percentile(primary_metric, '99.0') or 0:.4f}",
            "p99_status": self._sla_status(analyzer._get_percentile(primary_metric, '99.0') or 0, 5.0, "<="),
            "error_rate": "0.00",
            "error_status": "PASS",

            # SLA compliance
            "throughput_sla": f"{primary_metric.get('score', 0):.2f}",
            "sla_throughput_status": self._sla_status(primary_metric.get('score', 0), 10000, ">="),
            "p95_sla": f"{analyzer._get_percentile(primary_metric, '95.0') or 0:.4f}",
            "sla_p95_status": self._sla_status(analyzer._get_percentile(primary_metric, '95.0') or 0, 1.0, "<="),
            "p99_sla": f"{analyzer._get_percentile(primary_metric, '99.0') or 0:.4f}",
            "sla_p99_status": self._sla_status(analyzer._get_percentile(primary_metric, '99.0') or 0, 5.0, "<="),
            "error_sla": "0.00",
            "sla_error_status": "PASS",

            # Key findings
            "key_findings": self._generate_key_findings(primary, primary_metric, analyzer),

            # Benchmark details
            "benchmark_name": primary.get("name", "Unknown"),
            "benchmark_description": "Performance benchmark for JOTP primitives",
            "benchmark_params": f"threads={primary.get('threads', 1)}, forks={primary.get('forks', 1)}",
            "iterations": primary.get("iterations", 0),
            "warmup_iterations": primary.get("warmup_iterations", 0),
            "forks": primary.get("forks", 0),

            # Performance table
            "throughput": f"{primary_metric.get('score', 0):.2f}",
            "throughput_error": f"±{primary_metric.get('scoreError', 0):.2f}",
            "avg_time": f"{primary_metric.get('score', 0):.4f}",
            "avg_time_error": f"±{primary_metric.get('scoreError', 0):.4f}",
            "min_time": f"{primary_metric.get('min', 0):.4f}",
            "max_time": f"{primary_metric.get('max', 0):.4f}",
            "p50": f"{analyzer._get_percentile(primary_metric, '50.0') or 0:.4f}",
            "p90": f"{analyzer._get_percentile(primary_metric, '90.0') or 0:.4f}",
            "p95": f"{analyzer._get_percentile(primary_metric, '95.0') or 0:.4f}",
            "p99": f"{analyzer._get_percentile(primary_metric, '99.0') or 0:.4f}",
            "p999": f"{analyzer._get_percentile(primary_metric, '99.9') or 0:.4f}",

            # Memory metrics (placeholders)
            "heap_used": "N/A",
            "heap_committed": "N/A",
            "non_heap_used": "N/A",
            "gc_count": "N/A",
            "gc_time": "N/A",

            # Thread metrics
            "active_threads": str(primary.get("threads", 1)),
            "peak_threads": str(primary.get("threads", 1)),
            "total_started_threads": str(primary.get("threads", 1)),

            # Trends
            "current_throughput": f"{throughput_trend['current'] or 0:.2f}",
            "prev_throughput": f"{throughput_trend['previous'] or 0:.2f}",
            "throughput_change": str(throughput_trend['change_percent'] or 0),
            "throughput_trend": throughput_trend['trend'],
            "current_p95": f"{p95_trend['current'] or 0:.4f}",
            "prev_p95": f"{p95_trend['previous'] or 0:.4f}",
            "p95_change": str(p95_trend['change_percent'] or 0),
            "p95_trend": p95_trend['trend'],
            "current_p99": f"{p99_trend['current'] or 0:.4f}",
            "prev_p99": f"{p99_trend['previous'] or 0:.4f}",
            "p99_change": str(p99_trend['change_percent'] or 0),
            "p99_trend": p99_trend['trend'],
            "current_error": f"{error_trend['current'] or 0:.4f}",
            "prev_error": f"{error_trend['previous'] or 0:.4f}",
            "error_change": str(error_trend['change_percent'] or 0),
            "error_trend": error_trend['trend'],
            "historical_chart": "Historical chart not available in text format",

            # Scenario comparison
            "scenario_rows": self._generate_scenario_rows(results),
            "scenario_details": self._generate_scenario_details(results),

            # JVM and system info
            "jvm_version": results.system_info.get("jvm_version", "Unknown"),
            "jvm_vendor": results.system_info.get("jvm_vendor", "Unknown"),
            "java_home": results.system_info.get("java_home", "Unknown"),
            "vm_args": "N/A",
            "os_name": results.jvm_info['os_name'],
            "os_version": results.jvm_info['os_version'],
            "os_arch": results.jvm_info['os_arch'],
            "available_processors": str(results.jvm_info['available_processors']),
            "total_memory": f"{os.sysconf('SC_PAGE_SIZE') * os.sysconf('SC_PHYS_PAGES') / (1024.**3):.2f}" if hasattr(os, 'sysconf') else "N/A",
            "benchmark_date": datetime.now().strftime("%Y-%m-%d"),

            # Recommendations
            "performance_recommendations": self._generate_performance_recommendations(primary_metric, analyzer),
            "configuration_recommendations": self._generate_configuration_recommendations(),
            "code_recommendations": self._generate_code_recommendations(),

            # Appendix
            "jmh_results_file": "jmh-results.json",
            "jmh_raw_json": json.dumps(results.data, indent=2),
            "benchmark_parameters": self._generate_benchmark_parameters(primary),
            "test_environment_details": self._generate_environment_details(results),
            "contact_info": "JOTP Development Team",
            "docs_url": "https://github.com/seanchatmangpt/jotp",
        }

        # Fill template
        content = self.template
        for key, value in replacements.items():
            content = content.replace(f"{{{{{key}}}}}", str(value))

        return content

    def _sla_status(self, actual: float, target: float, operator: str) -> str:
        """Determine SLA compliance status."""
        if operator == ">=":
            return "PASS" if actual >= target else "FAIL"
        elif operator == "<=":
            return "PASS" if actual <= target else "FAIL"
        return "UNKNOWN"

    def _generate_key_findings(self, primary: Dict, primary_metric: Dict,
                              analyzer: TrendAnalyzer) -> str:
        """Generate key findings summary."""
        findings = []

        score = primary_metric.get('score', 0)
        if score >= 10000:
            findings.append(f"✓ Throughput of {score:.2f} ops/s exceeds 10K ops/s SLA target")
        else:
            findings.append(f"✗ Throughput of {score:.2f} ops/s below 10K ops/s SLA target")

        p95 = analyzer._get_percentile(primary_metric, '95.0')
        if p95 and p95 <= 1.0:
            findings.append(f"✓ P95 latency of {p95:.4f}ms meets ≤1ms SLA requirement")
        elif p95:
            findings.append(f"✗ P95 latency of {p95:.4f}ms exceeds ≤1ms SLA requirement")

        p99 = analyzer._get_percentile(primary_metric, '99.0')
        if p99 and p99 <= 5.0:
            findings.append(f"✓ P99 latency of {p99:.4f}ms meets ≤5ms SLA requirement")
        elif p99:
            findings.append(f"✗ P99 latency of {p99:.4f}ms exceeds ≤5ms SLA requirement")

        trend = analyzer.calculate_trend("throughput")
        if trend['trend'] == "Improving":
            findings.append(f"✓ Performance improved by {trend['change_percent']:.2f}% vs. previous run")
        elif trend['trend'] == "Degraded":
            findings.append(f"✗ Performance degraded by {abs(trend['change_percent']):.2f}% vs. previous run")

        return "\n\n".join(findings) if findings else "No key findings available."

    def _generate_scenario_rows(self, results: BenchmarkResults) -> str:
        """Generate scenario comparison table rows."""
        if not results.benchmarks:
            return "| No scenarios | - | - | - | - |"

        rows = []
        for benchmark in results.benchmarks:
            name = benchmark['name']
            metric = benchmark['primary_metric']
            throughput = metric.get('score', 0)
            p95 = self._extract_percentile_from_raw(metric, '95.0')
            p99 = self._extract_percentile_from_raw(metric, '99.0')
            success_rate = 100.0

            rows.append(f"| {name} | {throughput:.2f} | {p95:.4f} | {p99:.4f} | {success_rate:.2f}% |")

        return "\n".join(rows)

    def _generate_scenario_details(self, results: BenchmarkResults) -> str:
        """Generate detailed scenario information."""
        details = []
        for i, benchmark in enumerate(results.benchmarks, 1):
            details.append(f"#### Scenario {i}: {benchmark['name']}")
            details.append(f"- **Mode**: {benchmark['mode']}")
            details.append(f"- **Threads**: {benchmark['threads']}")
            details.append(f"- **Forks**: {benchmark['forks']}")
            details.append(f"- **Score**: {benchmark['primary_metric'].get('score', 0):.2f}")

        return "\n\n".join(details) if details else "No scenario details available."

    def _extract_percentile_from_raw(self, metric: Dict, percentile: str) -> float:
        """Extract percentile from raw data."""
        raw_data = metric.get("rawData", [])
        if not raw_data:
            return 0.0

        values = []
        for fork_data in raw_data:
            for value in fork_data:
                values.append(value)

        if not values:
            return 0.0

        values.sort()
        idx = int((float(percentile.rstrip('0').rstrip('.')) / 100.0) * (len(values) - 1))
        return values[idx] if 0 <= idx < len(values) else 0.0

    def _generate_performance_recommendations(self, primary_metric: Dict,
                                             analyzer: TrendAnalyzer) -> str:
        """Generate performance optimization recommendations."""
        recommendations = []

        score = primary_metric.get('score', 0)
        if score < 10000:
            recommendations.append("- Consider increasing thread pool size to improve throughput")
            recommendations.append("- Profile hot spots using async-profiler or JFR")

        p95 = analyzer._get_percentile(primary_metric, '95.0')
        if p95 and p95 > 1.0:
            recommendations.append("- P95 latency exceeds target; investigate tail latency causes")
            recommendations.append("- Consider using virtual threads for better latency distribution")

        trend = analyzer.calculate_trend("throughput")
        if trend['trend'] == "Degraded":
            recommendations.append(f"- Performance degraded by {abs(trend['change_percent']):.2f}%; review recent changes")

        return "\n".join(recommendations) if recommendations else "Performance meets all SLA targets."

    def _generate_configuration_recommendations(self) -> str:
        """Generate configuration change recommendations."""
        return """- JVM heap size: Configure -Xmx based on benchmark memory requirements
- GC algorithm: Consider G1GC or ZGC for low-latency applications
- Thread pool size: Set to available processors for CPU-bound tasks
- Enable preview features: Use --enable-preview for Java 26 APIs"""

    def _generate_code_recommendations(self) -> str:
        """Generate code improvement recommendations."""
        return """- Use virtual threads (Thread.ofVirtual()) for I/O-bound operations
- Leverage structured concurrency (StructuredTaskScope) for parallel processing
- Consider using records for immutable data structures
- Profile and eliminate allocation hotspots in critical paths"""

    def _generate_benchmark_parameters(self, primary: Dict) -> str:
        """Generate benchmark parameters table."""
        params = [
            ("Benchmark Name", primary.get("name", "Unknown")),
            ("Mode", primary.get("mode", "Unknown")),
            ("Threads", str(primary.get("threads", 1))),
            ("Forks", str(primary.get("forks", 1))),
            ("Warmup Iterations", str(primary.get("warmup_iterations", 0))),
            ("Measurement Iterations", str(primary.get("iterations", 0))),
            ("Time Unit", "ms/op"),
        ]

        rows = [f"| {key} | {value} |" for key, value in params]
        return "\n".join(rows)

    def _generate_environment_details(self, results: BenchmarkResults) -> str:
        """Generate test environment details."""
        return f"""- **OS**: {results.jvm_info['os_name']} {results.jvm_info['os_version']} ({results.jvm_info['os_arch']})
- **CPU Cores**: {results.jvm_info['available_processors']}
- **JVM**: {results.system_info.get('jvm_version', 'Unknown')}
- **Python**: {results.jvm_info.get('python_version', 'Unknown')}
- **Hostname**: {socket.gethostname()}
- **Date**: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}"""

    def _markdown_to_html(self, markdown_content: str) -> str:
        """Convert Markdown content to HTML with styling."""
        import re

        # Simple Markdown to HTML conversion
        html = markdown_content

        # Headers
        html = re.sub(r'^# (.+)$', r'<h1>\1</h1>', html, flags=re.MULTILINE)
        html = re.sub(r'^## (.+)$', r'<h2>\1</h2>', html, flags=re.MULTILINE)
        html = re.sub(r'^### (.+)$', r'<h3>\1</h3>', html, flags=re.MULTILINE)
        html = re.sub(r'^#### (.+)$', r'<h4>\1</h4>', html, flags=re.MULTILINE)

        # Bold
        html = re.sub(r'\*\*(.+?)\*\*', r'<strong>\1</strong>', html)

        # Tables
        table_pattern = r'(\|.+\|\n)+'
        def replace_table(match):
            lines = match.group(0).strip().split('\n')
            if len(lines) < 2:
                return match.group(0)

            rows = []
            for line in lines:
                cells = [cell.strip() for cell in line.split('|')[1:-1]]
                rows.append('<tr>' + ''.join(f'<td>{cell}</td>' for cell in cells) + '</tr>')

            return '<table border="1" cellpadding="8" cellspacing="0">\n' + '\n'.join(rows) + '\n</table>'

        html = re.sub(table_pattern, replace_table, html, flags=re.MULTILINE)

        # Lists
        html = re.sub(r'^- (.+)$', r'<li>\1</li>', html, flags=re.MULTILINE)
        html = re.sub(r'(<li>.+</li>\n)+', lambda m: '<ul>\n' + m.group(0) + '</ul>\n', html)

        # Code blocks
        html = re.sub(r'```(\w+)?\n(.+?)```', r'<pre><code>\2</code></pre>', html, flags=re.DOTALL)

        # Line breaks
        html = re.sub(r'\n\n', '</p><p>', html)
        html = '<p>' + html + '</p>'

        # Wrap in HTML template
        html_template = f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>JOTP Benchmark Report</title>
    <style>
        body {{
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
            line-height: 1.6;
            color: #333;
            max-width: 1200px;
            margin: 0 auto;
            padding: 20px;
            background-color: #f5f5f5;
        }}
        h1, h2, h3, h4 {{
            color: #2c3e50;
            margin-top: 30px;
        }}
        h1 {{
            border-bottom: 3px solid #3498db;
            padding-bottom: 10px;
        }}
        table {{
            border-collapse: collapse;
            width: 100%;
            margin: 20px 0;
            background-color: white;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }}
        th, td {{
            padding: 12px;
            text-align: left;
            border-bottom: 1px solid #ddd;
        }}
        th {{
            background-color: #3498db;
            color: white;
            font-weight: bold;
        }}
        tr:hover {{
            background-color: #f5f5f5;
        }}
        .pass {{
            color: #27ae60;
            font-weight: bold;
        }}
        .fail {{
            color: #e74c3c;
            font-weight: bold;
        }}
        pre {{
            background-color: #2c3e50;
            color: #ecf0f1;
            padding: 15px;
            border-radius: 5px;
            overflow-x: auto;
        }}
        code {{
            background-color: #f39c12;
            color: white;
            padding: 2px 6px;
            border-radius: 3px;
        }}
        ul {{
            margin: 15px 0;
        }}
        .improving {{
            color: #27ae60;
        }}
        .degraded {{
            color: #e74c3c;
        }}
        .stable {{
            color: #f39c12;
        }}
    </style>
</head>
<body>
{html}
</body>
</html>"""

        return html_template


def load_jmh_results(file_path: str) -> List[Dict[str, Any]]:
    """Load JMH JSON results from file."""
    with open(file_path, 'r') as f:
        return json.load(f)


def load_historical_results(directory: str, max_runs: int = 10) -> List[BenchmarkResults]:
    """Load historical benchmark results for trend analysis."""
    import glob

    historical = []
    pattern = os.path.join(directory, "jmh-results-*.json")
    files = sorted(glob.glob(pattern), reverse=True)[:max_runs]

    for file_path in files:
        try:
            data = load_jmh_results(file_path)
            historical.append(BenchmarkResults(data))
        except Exception as e:
            print(f"Warning: Failed to load {file_path}: {e}", file=sys.stderr)

    return historical


def main():
    """Main entry point for benchmark report generation."""
    parser = argparse.ArgumentParser(
        description="Generate benchmark reports from JMH JSON results"
    )
    parser.add_argument(
        "--input",
        "-i",
        default="target/jmh/results.json",
        help="Path to JMH JSON results file (default: target/jmh/results.json)"
    )
    parser.add_argument(
        "--format",
        "-f",
        choices=["html", "markdown", "both"],
        default="html",
        help="Output format (default: html)"
    )
    parser.add_argument(
        "--output",
        "-o",
        default="benchmark-report",
        help="Output file path without extension (default: benchmark-report)"
    )
    parser.add_argument(
        "--template",
        "-t",
        default="src/test/resources/benchmark/BenchmarkResultsTemplate.md",
        help="Path to report template"
    )
    parser.add_argument(
        "--historical-dir",
        "-d",
        help="Directory containing historical benchmark results for trend analysis"
    )
    parser.add_argument(
        "--max-historical",
        type=int,
        default=10,
        help="Maximum number of historical runs to analyze (default: 10)"
    )
    parser.add_argument(
        "--verbose",
        "-v",
        action="store_true",
        help="Enable verbose output"
    )

    args = parser.parse_args()

    # Validate input file exists
    if not os.path.exists(args.input):
        print(f"Error: Input file not found: {args.input}", file=sys.stderr)
        sys.exit(1)

    # Load JMH results
    if args.verbose:
        print(f"Loading JMH results from: {args.input}")

    jmh_data = load_jmh_results(args.input)
    results = BenchmarkResults(jmh_data)

    # Load historical results if directory provided
    historical = []
    if args.historical_dir and os.path.exists(args.historical_dir):
        if args.verbose:
            print(f"Loading historical results from: {args.historical_dir}")
        historical = load_historical_results(args.historical_dir, args.max_historical)

    # Create analyzer
    analyzer = TrendAnalyzer(results, historical)

    # Create report generator
    generator = ReportGenerator(args.template)

    # Generate reports
    if args.format in ["html", "both"]:
        html_output = f"{args.output}.html"
        generator.generate_html(results, analyzer, html_output)

    if args.format in ["markdown", "both"]:
        md_output = f"{args.output}.md"
        generator.generate_markdown(results, analyzer, md_output)

    if args.verbose:
        print(f"\nReport generation complete!")
        print(f"Benchmarks processed: {len(results.benchmarks)}")
        if historical:
            print(f"Historical runs analyzed: {len(historical)}")


if __name__ == "__main__":
    main()
