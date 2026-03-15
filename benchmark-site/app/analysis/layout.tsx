import Link from "next/link";
import { Box, Container, Flex, Grid, Heading, Text } from "@radix-ui/themes";
import { AnalysisNav } from "@/components/analysis/analysis-nav";

export default function AnalysisLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <Box minH="100vh" className="bg-gradient-to-br from-slate-900 via-slate-800 to-slate-900">
      <Container size="4" px="4" py="8">
        <Box mb="8">
          <Link href="/" className="text-blue-400 hover:text-blue-300 mb-4 inline-block">
            ← Back to Home
          </Link>
          <Heading size="8" className="text-white">Benchmark Analysis</Heading>
        </Box>

        <Grid columns={{ initial: "1", lg: "4" }} gap="6">
          <Box className="lg:col-span-1">
            <AnalysisNav />
          </Box>

          <Box className="lg:col-span-3">
            {children}
          </Box>
        </Grid>
      </Container>
    </Box>
  );
}
