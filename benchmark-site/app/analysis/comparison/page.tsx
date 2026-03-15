import { Box, Flex, Grid, Heading, Text, Card } from "@radix-ui/themes";
import { AnalysisSection } from "@/components/analysis/analysis-section";
import { MetricTable } from "@/components/analysis/metric-table";
import { BenchmarkChart } from "@/components/analysis/benchmark-chart";

const throughputComparison = [
  { run: "Run 1 (v0.4.0)", jotp: 238000, erlang: 245000, akka: 187000 },
  { run: "Run 2 (v0.4.0)", jotp: 241000, erlang: 247000, akka: 189000 },
  { run: "Run 3 (v0.4.0)", jotp: 239000, erlang: 244000, akka: 186000 },
  { run: "Run 1 (v0.3.0)", jotp: 225000, erlang: 243000, akka: 185000 },
  { run: "Run 2 (v0.3.0)", jotp: 227000, erlang: 245000, akka: 188000 },
];

const versionComparison = [
  { label: "JOTP v0.4.0", value: "239.3", unit: "K ops/sec", change: 5.9, status: "good" },
  { label: "JOTP v0.3.0", value: "226.0", unit: "K ops/sec", change: 0, status: "good" },
  { label: "Erlang/OTP 27", value: "245.3", unit: "K ops/sec", change: -1.2, status: "good" },
  { label: "Akka 2.8", value: "187.3", unit: "K ops/sec", change: 0.5, status: "warning" },
] as Array<{ label: string; value: string | number; unit?: string; change?: number; status: "good" | "warning" | "bad" }>;

const featureComparison = [
  { label: "Virtual Thread Support", jotp: "Yes", erlang: "N/A", akka: "Partial" },
  { label: "Supervisor Trees", jotp: "Yes", erlang: "Yes", akka: "Yes" },
  { label: "Hot Code Reload", jotp: "No", erlang: "Yes", akka: "No" },
  { label: "Type Safety", jotp: "Compile-time", erlang: "Runtime", akka: "Compile-time" },
  { label: "Distributed by Default", jotp: "No", erlang: "Yes", akka: "No" },
];

export default function ComparisonAnalysis() {
  return (
    <Flex direction="column" gap="6">
      <AnalysisSection
        title="Cross-Run Comparison"
        description="Performance across different versions and benchmark runs"
      >
        <BenchmarkChart
          data={throughputComparison}
          title="Throughput Comparison Across Runs (ops/sec)"
          dataKey="jotp"
          xAxisKey="run"
          color="#3b82f6"
        />
      </AnalysisSection>

      <AnalysisSection title="Version Performance Summary">
        <MetricTable data={versionComparison} />
      </AnalysisSection>

      <AnalysisSection title="Feature Comparison Matrix">
        <Box className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-700">
                <th className="text-left py-2 px-3 text-slate-300">Feature</th>
                <th className="text-center py-2 px-3 text-slate-300">JOTP</th>
                <th className="text-center py-2 px-3 text-slate-300">Erlang/OTP</th>
                <th className="text-center py-2 px-3 text-slate-300">Akka</th>
              </tr>
            </thead>
            <tbody>
              {featureComparison.map((row, i) => (
                <tr key={i} className="border-b border-slate-700/50">
                  <td className="py-2 px-3 text-white">{row.label}</td>
                  <td className="py-2 px-3 text-center text-slate-300">{row.jotp}</td>
                  <td className="py-2 px-3 text-center text-slate-300">{row.erlang}</td>
                  <td className="py-2 px-3 text-center text-slate-300">{row.akka}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Box>
      </AnalysisSection>

      <AnalysisSection title="Competitive Analysis">
        <Flex direction="column" gap="4" className="text-slate-300">
          <Box>
            <Heading size="4" className="text-white" mb="2">JOTP vs Erlang/OTP</Heading>
            <Flex direction="column" gap="1" asChild>
              <ul className="list-disc list-inside text-sm">
                <li><strong className="text-white">Throughput:</strong> 2.5% gap (239K vs 245K ops/sec)</li>
                <li><strong className="text-white">Type Safety:</strong> Compile-time sealed types vs runtime pattern matching</li>
                <li><strong className="text-white">Ecosystem:</strong> 12M Java developers vs 0.5M Erlang developers</li>
                <li><strong className="text-white">Trade-off:</strong> Hot code reload not supported in JOTP</li>
              </ul>
            </Flex>
          </Box>

          <Box>
            <Heading size="4" className="text-white" mb="2">JOTP vs Akka</Heading>
            <Flex direction="column" gap="1" asChild>
              <ul className="list-disc list-inside text-sm">
                <li><strong className="text-white">Throughput:</strong> 27.7% faster (239K vs 187K ops/sec)</li>
                <li><strong className="text-white">API Complexity:</strong> 5 core primitives vs Akka&apos;s extensive actor API</li>
                <li><strong className="text-white">Supervision:</strong> Built-in supervisor trees vs Akka&apos;s supervision strategies</li>
                <li><strong className="text-white">Learning Curve:</strong> Lower due to fewer primitives</li>
              </ul>
            </Flex>
          </Box>

          <Box>
            <Heading size="4" className="text-white" mb="2">Decision Matrix</Heading>
            <Text size="2">
              Choose <strong className="text-blue-400">JOTP</strong> when: Java ecosystem required, type safety critical, team knows Java
            </Text>
            <Text size="2">
              Choose <strong className="text-blue-400">Erlang/OTP</strong> when: Hot code reload required, telecom domain, distributed systems priority
            </Text>
            <Text size="2">
              Choose <strong className="text-blue-400">Akka</strong> when: Existing Scala codebase, advanced routing patterns required
            </Text>
          </Box>
        </Flex>
      </AnalysisSection>

      <AnalysisSection title="Performance Evolution">
        <Card variant="surface" className="bg-slate-700/30 border-slate-600">
          <Flex direction="column" gap="3" p="4">
            <Heading size="4" className="text-white">Version History</Heading>
            <Flex direction="column" gap="3">
              <Flex align="start" gap="3">
                <Text size="2" className="text-green-400 font-mono">v0.4.0</Text>
                <Box className="flex-1">
                  <Text size="2" className="text-slate-300">+5.9% throughput improvement from v0.3.0</Text>
                  <Text size="1" color="gray">Virtual thread optimization, lock-free registry</Text>
                </Box>
              </Flex>
              <Flex align="start" gap="3">
                <Text size="2" className="text-blue-400 font-mono">v0.3.0</Text>
                <Box className="flex-1">
                  <Text size="2" className="text-slate-300">Baseline measurement: 226K ops/sec</Text>
                  <Text size="1" color="gray">Initial supervisor tree implementation</Text>
                </Box>
              </Flex>
            </Flex>
          </Flex>
        </Card>
      </AnalysisSection>
    </Flex>
  );
}
