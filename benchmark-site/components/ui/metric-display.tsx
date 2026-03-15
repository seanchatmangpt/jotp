import { TrendingUpIcon, TrendingDownIcon, MinusIcon } from "@radix-ui/react-icons"
import { Text, Flex } from "@radix-ui/themes"
import { cn } from "@/lib/utils"

interface MetricDisplayProps {
  label: string
  value: string | number
  unit?: string
  trend?: "up" | "down" | "neutral"
  trendValue?: string
  description?: string
  className?: string
  showTrendIcon?: boolean
}

export function MetricDisplay({
  label,
  value,
  unit,
  trend = "neutral",
  trendValue,
  description,
  className,
  showTrendIcon = true
}: MetricDisplayProps) {
  const getTrendIcon = () => {
    switch (trend) {
      case "up":
        return <TrendingUpIcon width={16} height={16} />
      case "down":
        return <TrendingDownIcon width={16} height={16} />
      case "neutral":
        return <MinusIcon width={16} height={16} />
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

  return (
    <Flex direction="column" gap="1" className={className}>
      <Flex justify="between" align="center">
        <Text size="2" color="gray">{label}</Text>
        {showTrendIcon && trendValue && getTrendIcon()}
      </Flex>
      <Flex align="baseline" gap="2">
        <Text size="7" weight="bold">{value}</Text>
        {unit && <Text>{unit}</Text>}
      </Flex>
      {trendValue && (
        <Flex gap="1" align="center">
          <Text size="2" weight="medium" color={getTrendColor()}>
            {trendValue}
          </Text>
          <Text color="gray">vs previous</Text>
        </Flex>
      )}
      {description && (
        <Text color="gray">{description}</Text>
      )}
    </Flex>
  )
}
