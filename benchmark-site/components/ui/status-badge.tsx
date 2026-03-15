import { Badge } from "@radix-ui/themes"
import { CheckIcon, CrossIcon, InfoCircledIcon, ExclamationTriangleIcon } from "@radix-ui/react-icons"
import { cn } from "@/lib/utils"

interface StatusBadgeProps {
  status: "pass" | "fail" | "warning" | "info"
  className?: string
}

export function StatusBadge({ status, className }: StatusBadgeProps) {
  const config = {
    pass: {
      color: "green" as const,
      icon: <CheckIcon className="mr-1" width={12} height={12} />
    },
    fail: {
      color: "red" as const,
      icon: <CrossIcon className="mr-1" width={12} height={12} />
    },
    warning: {
      color: "amber" as const,
      icon: <ExclamationTriangleIcon className="mr-1" width={12} height={12} />
    },
    info: {
      color: "blue" as const,
      icon: <InfoCircledIcon className="mr-1" width={12} height={12} />
    }
  }

  const { color, icon } = config[status]

  return (
    <Badge color={color} className={className}>
      {icon}
      {status.toUpperCase()}
    </Badge>
  )
}
