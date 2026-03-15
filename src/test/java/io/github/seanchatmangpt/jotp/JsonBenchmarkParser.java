package io.github.seanchatmangpt.jotp.benchmark.report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses JMH JSON results into BenchmarkReport objects.
 *
 * <p>This class provides a simple JSON parser for JMH result files without requiring external
 * dependencies like Jackson or Gson.
 */
final class JsonBenchmarkParser {

    private JsonBenchmarkParser() {
        // Utility class
    }

    /**
     * Parses JMH JSON content into a list of BenchmarkResult objects.
     *
     * @param jsonContent JSON string content
     * @return list of parsed benchmark results
     * @throws IllegalArgumentException if JSON is invalid
     */
    static List<BenchmarkReport.BenchmarkResult> parse(String jsonContent) {
        List<BenchmarkReport.BenchmarkResult> results = new ArrayList<>();

        // JMH produces an array of benchmark objects
        if (jsonContent.trim().startsWith("[")) {
            List<Map<String, Object>> benchmarks = parseArray(jsonContent);
            for (Map<String, Object> benchmark : benchmarks) {
                parseBenchmark(benchmark).ifPresent(results::add);
            }
        } else {
            // Single object or wrapped in a property
            Map<String, Object> root = parseObject(jsonContent);
            Object benchmarksObj = root.get("benchmarks");
            if (benchmarksObj instanceof List<?> benchmarksList) {
                for (Object item : benchmarksList) {
                    if (item instanceof Map<?, ?> benchmark) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> b = (Map<String, Object>) benchmark;
                        parseBenchmark(b).ifPresent(results::add);
                    }
                }
            }
        }

        return results;
    }

    private static Optional<BenchmarkReport.BenchmarkResult> parseBenchmark(
            Map<String, Object> data) {
        try {
            String name = getString(data, "benchmark");
            String mode = getString(data, "mode", "Throughput");
            int threads = getInt(data, "threads", 1);
            int forks = getInt(data, "forks", 1);

            Map<String, Object> primaryMetric = getMap(data, "primaryMetric");
            double score = getDouble(primaryMetric, "score", 0.0);
            double scoreError = getDouble(primaryMetric, "scoreError", 0.0);
            String scoreUnit = getString(primaryMetric, "scoreUnit", "ops/ms");

            // Parse percentiles from rawData
            Map<String, Double> percentiles = parsePercentiles(primaryMetric);

            // Extract JVM info
            String jvmVersion = getString(data, "vmVersion", "Unknown");
            String vmName = getString(data, "vmName", "Unknown");

            return Optional.of(
                    new BenchmarkReport.BenchmarkResult(
                            name,
                            mode,
                            threads,
                            forks,
                            score,
                            scoreError,
                            1.0, // normalized unit
                            percentiles,
                            jvmVersion,
                            vmName));
        } catch (Exception e) {
            System.err.println("Warning: Failed to parse benchmark entry: " + e.getMessage());
            return Optional.empty();
        }
    }

    private static Map<String, Double> parsePercentiles(Map<String, Object> primaryMetric) {
        Map<String, Double> percentiles = new HashMap<>();

        Object rawDataObj = primaryMetric.get("rawData");
        if (!(rawDataObj instanceof List<?> rawData) || rawData.isEmpty()) {
            return percentiles;
        }

        // Collect all data points across forks
        List<Double> allData = new ArrayList<>();
        for (Object forkObj : rawData) {
            if (forkObj instanceof List<?> forkData) {
                for (Object value : forkData) {
                    if (value instanceof Number num) {
                        allData.add(num.doubleValue());
                    }
                }
            }
        }

        if (allData.isEmpty()) {
            return percentiles;
        }

        // Sort and calculate percentiles
        allData.sort(Double::compareTo);

        percentiles.put("50.0", calculatePercentile(allData, 50.0));
        percentiles.put("90.0", calculatePercentile(allData, 90.0));
        percentiles.put("95.0", calculatePercentile(allData, 95.0));
        percentiles.put("99.0", calculatePercentile(allData, 99.0));
        percentiles.put("99.9", calculatePercentile(allData, 99.9));

        return percentiles;
    }

    private static double calculatePercentile(List<Double> sortedData, double percentile) {
        if (sortedData.isEmpty()) {
            return 0.0;
        }

        double index = (percentile / 100.0) * (sortedData.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);

        if (lower == upper) {
            return sortedData.get(lower);
        }

        double weight = index - lower;
        return sortedData.get(lower) * (1.0 - weight) + sortedData.get(upper) * weight;
    }

    // Simple JSON parsing methods

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(String json) {
        // Remove outer braces
        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) {
            throw new IllegalArgumentException("Invalid JSON object");
        }

        json = json.substring(1, json.length() - 1).trim();
        Map<String, Object> map = new HashMap<>();

        if (json.isEmpty()) {
            return map;
        }

        int pos = 0;
        while (pos < json.length()) {
            // Skip whitespace
            while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
                pos++;
            }

            if (pos >= json.length()) {
                break;
            }

            // Parse key
            int keyStart = json.indexOf('"', pos);
            if (keyStart == -1) {
                break;
            }
            int keyEnd = json.indexOf('"', keyStart + 1);
            if (keyEnd == -1) {
                break;
            }

            String key = json.substring(keyStart + 1, keyEnd);
            pos = keyEnd + 1;

            // Skip to colon
            while (pos < json.length() && json.charAt(pos) != ':') {
                pos++;
            }
            pos++; // skip colon

            // Parse value
            ValueResult value = parseValue(json, pos);
            map.put(key, value.value);
            pos = value.nextPos;

            // Skip comma
            while (pos < json.length()
                    && (json.charAt(pos) == ',' || Character.isWhitespace(json.charAt(pos)))) {
                pos++;
            }
        }

        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseArray(String json) {
        json = json.trim();
        if (!json.startsWith("[") || !json.endsWith("]")) {
            throw new IllegalArgumentException("Invalid JSON array");
        }

        json = json.substring(1, json.length() - 1).trim();
        List<Map<String, Object>> list = new ArrayList<>();

        if (json.isEmpty()) {
            return list;
        }

        int pos = 0;
        int depth = 0;
        int start = 0;

        while (pos < json.length()) {
            char c = json.charAt(pos);

            if (c == '{' && depth == 0) {
                start = pos;
                depth++;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    String objStr = json.substring(start, pos + 1);
                    list.add(parseObject(objStr));
                }
            }

            pos++;
        }

        return list;
    }

    private static ValueResult parseValue(String json, int pos) {
        while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
            pos++;
        }

        if (pos >= json.length()) {
            return new ValueResult(null, pos);
        }

        char c = json.charAt(pos);

        if (c == '"') {
            // String
            int end = json.indexOf('"', pos + 1);
            if (end == -1) {
                return new ValueResult("", pos);
            }
            return new ValueResult(json.substring(pos + 1, end), end + 1);
        } else if (c == '{') {
            // Object
            int depth = 1;
            int end = pos + 1;
            while (end < json.length() && depth > 0) {
                char ec = json.charAt(end);
                if (ec == '{') depth++;
                else if (ec == '}') depth--;
                end++;
            }
            return new ValueResult(parseObject(json.substring(pos, end)), end);
        } else if (c == '[') {
            // Array
            int depth = 1;
            int end = pos + 1;
            while (end < json.length() && depth > 0) {
                char ec = json.charAt(end);
                if (ec == '[') depth++;
                else if (ec == ']') depth--;
                end++;
            }
            return new ValueResult(parseList(json.substring(pos, end)), end);
        } else {
            // Number or boolean
            int end = pos;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
                end++;
            }
            String valueStr = json.substring(pos, end).trim();
            Object value = parsePrimitive(valueStr);
            return new ValueResult(value, end);
        }
    }

    private static List<Object> parseList(String json) {
        json = json.trim();
        if (!json.startsWith("[") || !json.endsWith("]")) {
            throw new IllegalArgumentException("Invalid JSON array");
        }

        json = json.substring(1, json.length() - 1).trim();
        List<Object> list = new ArrayList<>();

        if (json.isEmpty()) {
            return list;
        }

        int pos = 0;
        while (pos < json.length()) {
            ValueResult result = parseValue(json, pos);
            list.add(result.value);
            pos = result.nextPos;

            // Skip comma
            while (pos < json.length()
                    && (json.charAt(pos) == ',' || Character.isWhitespace(json.charAt(pos)))) {
                pos++;
            }
        }

        return list;
    }

    private static Object parsePrimitive(String value) {
        value = value.trim();
        if (value.equals("true")) {
            return true;
        } else if (value.equals("false")) {
            return false;
        } else if (value.equals("null")) {
            return null;
        } else {
            try {
                if (value.contains(".")) {
                    return Double.parseDouble(value);
                } else {
                    return Long.parseLong(value);
                }
            } catch (NumberFormatException e) {
                return value;
            }
        }
    }

    private static String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        String value = getString(map, key);
        return value != null ? value : defaultValue;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number num) {
            return num.intValue();
        }
        return defaultValue;
    }

    private static double getDouble(Map<String, Object> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number num) {
            return num.doubleValue();
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getMap(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private static record ValueResult(Object value, int nextPos) {}
}
