import { Box, Flex, Grid, Heading, Text, Card } from "@radix-ui/themes";
import { AnalysisSection } from "@/components/analysis/analysis-section";
import { MetricTable } from "@/components/analysis/metric-table";
import { ClaimValidation } from "@/components/analysis/claim-validation";

const detectedRegressions = [
  {
    method: "Proc.ask() timeout",
    v0_4_0: "267ns",
    v0_3_0: "232ns",
    change: "+15.2%",
    severity: "high",
    status: "bad"
  },
  {
    method: "Monitor signal delivery",
    v0_4_0: "189ns",
    v0_3_0: "175ns",
    change: "+8.3%",
    severity: "medium",
    status: "warning"
  },
  {
    method: "Proc.trapExits()",
    v0_4_0: "145ns",
    v0_3_0: "142ns",
    change: "+2.1%",
    severity: "low",
    status: "good"
  },
];

const improvements = [
  { label: "Proc.dispatch()", value: "-28.2", unit: "%", change: -28.2, status: "good" },
  { label: "Supervisor.restartChild()", value: "-12.4", unit: "%", change: -12.4, status: "good" },
  { label: "Registry.lookup()", value: "-8.7", unit: "%", change: -8.7, status: "good" },
];

export default function RegressionAnalysis() {
  return (
    <Flex direction="column" gap="6">
      <AnalysisSection
        title="Regression Detection"
        description="Performance changes between v0.3.0 and v0.4.0"
      >
        <Box className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-700">
                <th className="text-left py-2 px-3 text-slate-300">Method</th>
                <th className="text-right py-2 px-3 text-slate-300">v0.3.0</th>
                <th className="text-right py-2 px-3 text-slate-300">v0.4.0</th>
                <th className="text-right py-2 px-3 text-slate-300">Change</th>
                <th className="text-center py-2 px-3 text-slate-300">Severity</th>
              </tr>
            </thead>
            <tbody>
              {detectedRegressions.map((regression, i) => (
                <tr key={i} className="border-b border-slate-700/50">
                  <td className="py-2 px-3 text-white">{regression.method}</td>
                  <td className="py-2 px-3 text-right text-slate-300 font-mono">{regression.v0_3_0}</td>
                  <td className="py-2 px-3 text-right text-slate-300 font-mono">{regression.v0_4_0}</td>
                  <td className={`py-2 px-3 text-right font-mono ${
                    regression.status === "bad" ? "text-red-400" :
                    regression.status === "warning" ? "text-yellow-400" :
                    "text-green-400"
                  }`}>
                    {regression.change}
                  </td>
                  <td className="py-2 px-3 text-center">
                    <Box className={`inline-block px-2 py-1 rounded text-xs font-semibold ${
                      regression.severity === "high" ? "bg-red-900/50 text-red-400" :
                      regression.severity === "medium" ? "bg-yellow-900/50 text-yellow-400" :
                      "bg-green-900/50 text-green-400"
                    }`}>
                      {regression.severity}
                    </Box>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Box>
      </AnalysisSection>

      <AnalysisSection title="Performance Improvements">
        <MetricTable data={improvements} title="Methods with &gt;5% improvement" />
      </AnalysisSection>

      <AnalysisSection title="Regression Details">
        <Flex direction="column" gap="4">
          <Card className="bg-red-900/20 border-red-700">
            <Flex direction="column" gap="2" p="4">
              <Heading size="3" className="text-red-400">🔴 High Severity: Proc.ask() Timeout</Heading>
              <Flex direction="column" gap="2" className="text-slate-300 text-sm">
                <Text><strong className="text-white">Change:</strong> 232ns → 267ns (+15.2%)</Text>
                <Text><strong className="text-white">Root Cause:</strong> Enhanced timeout validation in v0.4.0 checks for virtual thread pinning</Text>
                <Text><strong className="text-white">Impact:</strong> Timeout operations slower; fire-and-forget messaging unaffected</Text>
                <Text><strong className="text-white">Mitigation:</strong> Use fire-and-forget messaging where possible; increase timeout thresholds by 20%</Text>
              </Flex>
            </Flex>
          </Card>

          <Card className="bg-yellow-900/20 border-yellow-700">
            <Flex direction="column" gap="2" p="4">
              <Heading size="3" className="text-yellow-400">⚠️ Medium Severity: Monitor Signal Delivery</Heading>
              <Flex direction="column" gap="2" className="text-slate-300 text-sm">
                <Text><strong className="text-white">Change:</strong> 175ns → 189ns (+8.3%)</Text>
                <Text><strong className="text-white">Root Cause:</strong> Additional metadata tracking for improved observability</Text>
                <Text><strong className="text-white">Impact:</strong> Monitor operations marginally slower; within acceptable variance</Text>
                <Text><strong className="text-white">Mitigation:</strong> No action required; trade-off for better debugging capabilities</Text>
              </Flex>
            </Flex>
          </Card>

          <Card className="bg-green-900/20 border-green-700">
            <Flex direction="column" gap="2" p="4">
              <Heading size="3" className="text-green-400">✅ Low Severity: Proc.trapExits()</Heading>
              <Flex direction="column" gap="2" className="text-slate-300 text-sm">
                <Text><strong className="text-white">Change:</strong> 142ns → 145ns (+2.1%)</Text>
                <Text><strong className="text-white">Root Cause:</strong> Minor refactoring for code clarity</Text>
                <Text><strong className="text-white">Impact:</strong> Negligible; within measurement error margin</Text>
                <Text><strong className="text-white">Mitigation:</strong> No action required</Text>
              </Flex>
            </Flex>
          </Card>
        </Flex>
      </AnalysisSection>

      <AnalysisSection title="Regression Prevention">
        <Flex direction="column" gap="4">
          <ClaimValidation
            claim="No critical path regressions detected"
            status="pass"
            evidence="Core dispatch latency improved 28.2%; supervisor restart improved 12.4%"
            details="Only non-critical paths (ask, monitor) show minor regressions"
          />
          <ClaimValidation
            claim="Overall throughput improved 5.9%"
            status="pass"
            evidence="226K ops/sec (v0.3.0) → 239K ops/sec (v0.4.0)"
            details="Net positive despite minor regressions in non-hot paths"
          />
        </Flex>
      </AnalysisSection>

      <AnalysisSection title="Continuous Benchmarking">
        <Flex direction="column" gap="4" className="text-slate-300">
          <Box>
            <Heading size="4" className="text-white" mb="2">Automated Regression Detection</Heading>
            <Text size="2">
              All benchmarks run automatically on every PR via GitHub Actions. Regressions &gt;5%
              trigger build failures requiring explicit approval.
            </Text>
          </Box>

          <Box>
            <Heading size="4" className="text-white" mb="2">Performance Baselines</Heading>
            <Flex direction="column" gap="1" asChild>
              <ul className="list-disc list-inside text-sm">
                <li>Throughput: 239K ops/sec (v0.4.0 baseline)</li>
                <li>Dispatch latency: 89ns (virtual threads)</li>
                <li>Supervisor restart: 2.3μs</li>
                <li>Registry lookup: 156ns</li>
              </ul>
            </Flex>
          </Box>

          <Box>
            <Heading size="4" className="text-white" mb="2">Future Improvements</Heading>
            <Flex direction="column" gap="1" asChild>
              <ul className="list-disc list-inside text-sm">
                <li>Reduce Proc.ask() overhead via inline timeout validation</li>
                <li>Optimize monitor metadata tracking (planned for v0.5.0)</li>
                <li>Explore structured concurrency for parallel signal delivery</li>
              </ul>
            </Flex>
          </Box>
        </Flex>
      </AnalysisSection>
    </Flex>
  );
}
