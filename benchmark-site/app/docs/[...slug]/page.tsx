import { notFound } from 'next/navigation'
import { MDXRemote } from 'next-mdx-remote/rsc'
import { Card, Heading, Text, Box, Flex, Badge } from '@radix-ui/themes'
import { ContentTypeBadge } from '@/components/docs/content-type-badge'
import { ProgressIndicator } from '@/components/docs/progress-indicator'
import { MDXComponents } from '@/lib/mdx-components'
import { DocFrontmatter } from '@/lib/content-schema'

interface PageProps {
  params: {
    slug: string[]
  }
}

// Mock content loading - in a real implementation, this would fetch from filesystem or CMS
async function loadContent(slug: string[]): Promise<{ frontmatter: DocFrontmatter; content: string }> {
  const path = slug.join('/')

  // Mock content for demonstration
  const contentMap: Record<string, { frontmatter: DocFrontmatter; content: string }> = {
    'tutorials/getting-started': {
      frontmatter: {
        title: 'Getting Started with JOTP',
        type: 'tutorial',
        description: 'Learn the fundamentals of JOTP and set up your first benchmark',
        difficulty: 'beginner',
        duration: '15 min',
        lastUpdated: '2024-01-15',
        seo: {
          title: 'JOTP Tutorial: Getting Started',
          description: 'Learn the fundamentals of JOTP and set up your first benchmark'
        }
      },
      content: `
# Getting Started with JOTP

Welcome to JOTP, the fault-tolerant Java 26 framework that brings Erlang/OTP patterns to Java.

## What is JOTP?

JOTP (Java OTP) is a modern Java 26 framework that implements Erlang/OTP design patterns with full type safety and Java ecosystem integration.

## Prerequisites

- Java 26 with --enable-preview
- Maven (mvnd recommended)
- Basic understanding of Java concurrency

## Installation

Add JOTP to your project:

\`\`\`xml
<dependency>
  <groupId>io.github.seanchatmangpt.jotp</groupId>
  <artifactId>jotp</artifactId>
  <version>1.0.0</version>
</dependency>
\`\`\`

## Your First Process

Let's create your first JOTP process:

\`\`\`java
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;

public class SimpleProcess {
    public static void main(String[] args) {
        // Create a process that counts messages
        ProcRef<Integer, String> counter = Proc.spawn(
            0, // initial state
            (state, msg) -> {
                // Message handler
                int newState = state + 1;
                return Pair.of(newState, "Count: " + newState);
            },
            null // args
        );

        // Send messages
        counter.ask("Increment");
        counter.ask("Increment");
    }
}
\`\`\`

## Next Steps

Continue with [First Benchmark Example](/docs/tutorials/first-benchmark) to see JOTP in action.
      `
    },
    'tutorials/first-benchmark': {
      frontmatter: {
        title: 'First Benchmark Example',
        type: 'tutorial',
        description: 'Write your first JOTP benchmark from scratch',
        difficulty: 'beginner',
        duration: '20 min',
        lastUpdated: '2024-01-15',
        seo: {
          title: 'JOTP Tutorial: First Benchmark',
          description: 'Write your first JOTP benchmark from scratch'
        }
      },
      content: `
# First Benchmark Example

Let's create a simple JOTP benchmark to test message throughput.

## What We're Building

We'll create a benchmark that measures how many messages a JOTP process can handle per second.

## Step 1: Create the Test Process

First, let's create a process that receives and acknowledges messages:

\`\`\`java
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import java.util.concurrent.atomic.AtomicInteger;

public class ThroughputProcess {
    private final AtomicInteger counter = new AtomicInteger(0);

    public static ProcRef<Void, String> create() {
        return Proc.spawn(
            null, // no state needed
            (state, msg) -> {
                // Just increment and acknowledge
                return Pair.of(state, "OK");
            },
            null
        );
    }
}
\`\`\`

## Step 2: Create the Benchmark

Now let's create the benchmark class:

\`\`\`java
import io.github.seanchatmangpt.jotp.benchmark.BenchmarkRunner;
import java.util.concurrent.TimeUnit;

public class ThroughputBenchmark {
    public static void main(String[] args) {
        // Create test process
        ProcRef<Void, String> process = ThroughputProcess.create();

        // Configure benchmark
        var config = new BenchmarkConfig.Builder()
            .name("Throughput Test")
            .warmupIterations(1000)
            .measurementIterations(10000)
            .timeUnit(TimeUnit.SECONDS)
            .build();

        // Run benchmark
        var results = BenchmarkRunner.run(config, () -> {
            for (int i = 0; i < 1000; i++) {
                process.ask("test");
            }
            return null;
        });

        // Print results
        System.out.println("Throughput: " + results.throughput() + " msg/sec");
    }
}
\`\`\`

## Running the Benchmark

Execute the benchmark:

\`\`\`bash
mvn exec:java -Dexec.mainClass="com.example.ThroughputBenchmark"
\`\`\`

You should see output like:

\`\`\`
Benchmark completed:
- Total operations: 10000000
- Duration: 2.5 seconds
- Throughput: 4000000.0 msg/sec
\`\`\`

## Next Steps

Try modifying the benchmark to test different message sizes or add supervision trees.
      `
    },
    'how-to-guides/write-benchmark': {
      frontmatter: {
        title: 'Writing Benchmark Tests',
        type: 'how-to',
        description: 'Step-by-step guide to creating effective benchmarks',
        difficulty: 'intermediate',
        duration: '30 min',
        lastUpdated: '2024-01-15',
        seo: {
          title: 'JOTP How-To: Write Benchmark Tests',
          description: 'Step-by-step guide to creating effective benchmarks'
        }
      },
      content: `
# Writing Benchmark Tests

This guide shows you how to write effective JOTP benchmarks that provide meaningful performance insights.

## Benchmark Structure

A good JOTP benchmark consists of:

1. **Test Setup** - Initialize processes and configuration
2. **Warmup Phase** - Allow JIT compilation and cache warmup
3. **Measurement Phase** - Collect actual performance data
4. **Analysis** - Calculate metrics and present results

## Step-by-Step Implementation

### 1. Define Your Test Case

Create a class that implements your specific benchmark scenario:

\`\`\`java
public class LatencyBenchmark {
    private ProcRef<TestState, Request> process;

    @Before
    public void setup() {
        // Initialize the process
        process = Proc.spawn(
            new TestState(),
            new RequestHandler(),
            null
        );
    }
}
\`\`\`

### 2. Implement the Benchmark Logic

\`\`\`java
import io.github.seanchatmangpt.jotp.benchmark.*;
import java.util.concurrent.TimeUnit;

public class LatencyBenchmark implements Benchmark {
    private static final int WARMUP_ITERATIONS = 1000;
    private static final int MEASUREMENT_ITERATIONS = 10000;

    @Override
    public BenchmarkResult run(BenchmarkConfig config) {
        // Warmup
        warmup();

        // Measurement
        long start = System.nanoTime();
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            process.send(new Request("test-" + i));
        }
        long duration = System.nanoTime() - start;

        // Calculate metrics
        double throughput = (double) MEASUREMENT_ITERATIONS /
                           (duration / TimeUnit.SECONDS.toNanos(1));

        return new BenchmarkResult.Builder()
            .throughput(throughput)
            .duration(duration, TimeUnit.NANOSECONDS)
            .iterations(MEASUREMENT_ITERATIONS)
            .build();
    }
}
\`\`\`

### 3. Configure and Run

\`\`\`java
var config = new BenchmarkConfig.Builder()
    .name("Latency Test")
    .warmupIterations(WARMUP_ITERATIONS)
    .measurementIterations(MEASUREMENT_ITERATIONS)
    .timeUnit(TimeUnit.MICROSECONDS)
    .build();

BenchmarkRunner runner = new BenchmarkRunner(config);
BenchmarkResult result = runner.run(new LatencyBenchmark());
\`\`\`

## Best Practices

### 1. Proper Warmup

Always include a warmup period to allow:
- JIT compilation
- Memory allocation
- Caching effects

### 2. Isolation

Ensure tests are isolated:
- Avoid shared state between runs
- Use fresh processes for each test
- Clean up resources properly

### 3. Metrics Collection

Collect meaningful metrics:
- Throughput (ops/sec)
- Latency (avg, min, max, percentiles)
- Memory usage
- CPU utilization

### 4. Reproducibility

Make benchmarks reproducible:
- Use fixed seeds for random data
- Control environment variables
- Document system configuration

## Advanced Techniques

### Multi-threaded Benchmarks

\`\`\`java
ExecutorService executor = Executors.newFixedThreadPool(threadCount);
for (int i = 0; i < threadCount; i++) {
    executor.submit(() -> {
        for (int j = 0; j < iterationsPerThread; j++) {
            process.send(new Request());
        }
    });
}
\`\`\`

### Supervision Integration

\`\`\`java
Supervisor supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    List.of(
        ChildSpec.builder(Proc::class)
            .restartType(RestartType.PERMANENT)
            .build()
    )
);
\`\`\`

## Common Pitfalls

1. **Ignoring warmup** - Results include compilation overhead
2. **Shared state** - Threads interfere with each other
3. **Garbage collection** - GC pauses skew results
4. **Insufficient samples** - Not enough iterations for statistical significance
      `
    }
  }

  if (!contentMap[path]) {
    notFound()
  }

  return contentMap[path]
}

export default async function DocsPage({ params }: PageProps) {
  const { slug } = params
  const content = await loadContent(slug)

  return (
    <Box p="8">
      <ProgressIndicator contentId={slug.join('/')} />

      <Box maxWidth="800px" mx="auto">
        <Box mb="8">
          <Card>
            <Flex justify="between" align="start" gap="4" p="6" pb="4">
              <Flex gap="4" align="center">
                <Heading size="8">
                  {content.frontmatter.title}
                </Heading>
                <ContentTypeBadge type={content.frontmatter.type} />
                {content.frontmatter.difficulty && (
                  <Badge
                    color={
                      content.frontmatter.difficulty === 'beginner' ? 'green' :
                      content.frontmatter.difficulty === 'intermediate' ? 'yellow' :
                      'red'
                    }
                  >
                    {content.frontmatter.difficulty}
                  </Badge>
                )}
              </Flex>
              {content.frontmatter.duration && (
                <Text color="gray">
                  ⏱ {content.frontmatter.duration}
                </Text>
              )}
            </Flex>
            <Box px="6" pb="4">
              <Text color="gray" size="4">
                {content.frontmatter.description}
              </Text>
            </Box>
          </Card>
        </Box>

        <Card>
          <Box p="8">
            <MDXRemote source={content.content} components={MDXComponents} />
          </Box>
        </Card>
      </Box>
    </Box>
  )
}
