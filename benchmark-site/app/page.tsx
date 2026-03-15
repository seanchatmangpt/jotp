import Link from 'next/link'
import { Box, Container, Grid, Card, Heading, Text, Flex } from "@radix-ui/themes"

export default function Home() {
  return (
    <Box minH="100vh" p="6">
      <Container size="4">
        <Heading size="8" mb="6">JOTP Benchmark Monitor</Heading>

        <Grid columns={{ initial: "1", sm: "2", lg: "3" }} gap="6">
          <Link href="/monitoring" className="no-underline">
            <Card className="cursor-pointer transition-shadow hover:shadow-lg">
              <Flex direction="column" gap="4" p="4">
                <Heading size="5">Monitoring Dashboard</Heading>
                <Text color="gray">Real-time benchmark execution and metrics</Text>
              </Flex>
            </Card>
          </Link>

          <Link href="/monitoring/live" className="no-underline">
            <Card className="cursor-pointer transition-shadow hover:shadow-lg">
              <Flex direction="column" gap="4" p="4">
                <Heading size="5">Live Benchmarks</Heading>
                <Text color="gray">Watch benchmarks run in real-time</Text>
              </Flex>
            </Card>
          </Link>

          <Link href="/monitoring/history" className="no-underline">
            <Card className="cursor-pointer transition-shadow hover:shadow-lg">
              <Flex direction="column" gap="4" p="4">
                <Heading size="5">Historical Data</Heading>
                <Text color="gray">Browse past benchmark results</Text>
              </Flex>
            </Card>
          </Link>

          <Link href="/chat" className="no-underline">
            <Card className="cursor-pointer transition-shadow hover:shadow-lg">
              <Flex direction="column" gap="4" p="4">
                <Heading size="5">AI Chat</Heading>
                <Text color="gray">Groq-powered assistant for JOTP questions</Text>
              </Flex>
            </Card>
          </Link>
        </Grid>
      </Container>
    </Box>
  )
}
