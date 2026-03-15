import { Card, Badge, Heading, Text, Flex, Box } from "@radix-ui/themes"
import { TrendingUp, TrendingDown, Minus } from "@radix-ui/react-icons"
import { cn } from "@/lib/utils"

interface BenchmarkCardProps {
  title: string
  description?: string
  value: string | number
  unit?: string
  trend?: "up" | "down" | "neutral"
  trendValue?: string
  status?: "pass" | "fail" | "warning"
  footer?: React.ReactNode
  className?: string
}

export function BenchmarkCard({
  title,
  description,
  value,
  unit,
  trend = "neutral",
  trendValue,
  status,
  footer,
  className
}: BenchmarkCardProps) {
  const getTrendIcon = () => {
    switch (trend) {
      case "up":
        return <TrendingUp width={16} height={16} color="green" />
      case "down":
        return <TrendingDown width={16} height={16} color="red" />
      case "neutral":
        return <Minus width={16} height={16} color="gray" />
    }
  }

  const getTrendColor = () => {
    switch (trend) {
      case "up":
        return "green"
      case "down":
        return "red"
      case "neutral":
        return "gray"
    }
  }

  const getStatusBadge = () => {
    if (!status) return null

    const colorMap = {
      pass: "green" as const,
      fail: "red" as const,
      warning: "amber" as const
    }

    return (
      <Badge color={colorMap[status]}>
        {status.toUpperCase()}
      </Badge>
    )
  }

  return (
    <Card className={cn("jotp-card-gradient", className)}>
      <Flex direction="column" gap="4" p="4">
        <Flex justify="between" align="start">
          <Flex direction="column" gap="1">
            <Heading size="3">{title}</Heading>
            {description && (
              <Text size="2" color="gray">{description}</Text>
            )}
          </Flex>
          {getStatusBadge()}
        </Flex>
        <Flex align="baseline" gap="2">
          <Text size="7" weight="bold">{value}</Text>
          {unit && <Text>{unit}</Text>}
        </Flex>
        {trendValue && (
          <Flex gap="1" align="center">
            {getTrendIcon()}
            <Text size="2" weight="medium" color={getTrendColor()}>
              {trendValue}
            </Text>
            <Text color="gray">vs previous</Text>
          </Flex>
        )}
        {footer && <Flex mt="4">{footer}</Flex>}
      </Flex>
    </Card>
  )
}
