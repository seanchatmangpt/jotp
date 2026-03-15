interface ClaimValidationProps {
  claim: string;
  status: "pass" | "fail" | "partial";
  evidence: string;
  details?: string;
}

export function ClaimValidation({ claim, status, evidence, details }: ClaimValidationProps) {
  const statusConfig = {
    pass: {
      icon: "✓",
      bgColor: "bg-green-900/30",
      borderColor: "border-green-700",
      textColor: "text-green-400",
      label: "VALIDATED"
    },
    fail: {
      icon: "✗",
      bgColor: "bg-red-900/30",
      borderColor: "border-red-700",
      textColor: "text-red-400",
      label: "REJECTED"
    },
    partial: {
      icon: "~",
      bgColor: "bg-yellow-900/30",
      borderColor: "border-yellow-700",
      textColor: "text-yellow-400",
      label: "PARTIAL"
    }
  };

  const config = statusConfig[status];

  return (
    <div className={`${config.bgColor} border ${config.borderColor} rounded-lg p-4`}>
      <div className="flex items-start gap-3">
        <span className={`text-2xl ${config.textColor}`}>{config.icon}</span>
        <div className="flex-1">
          <div className="flex items-center gap-2 mb-2">
            <span className={`text-xs font-bold ${config.textColor} px-2 py-1 rounded`}>
              {config.label}
            </span>
            <h4 className="text-white font-semibold">{claim}</h4>
          </div>
          <p className="text-slate-300 text-sm mb-2">{evidence}</p>
          {details && (
            <p className="text-slate-400 text-xs">{details}</p>
          )}
        </div>
      </div>
    </div>
  );
}
