interface MetricTableProps {
  data: Array<{
    label: string;
    value: string | number;
    unit?: string;
    change?: number;
    status?: "good" | "warning" | "bad" | string;
  }>;
  title?: string;
}

export function MetricTable({ data, title }: MetricTableProps) {
  return (
    <div className="overflow-x-auto">
      {title && <h3 className="text-lg font-semibold text-white mb-3">{title}</h3>}
      <table className="w-full">
        <thead>
          <tr className="border-b border-slate-700">
            <th className="text-left py-2 px-3 text-slate-300 font-semibold">Metric</th>
            <th className="text-right py-2 px-3 text-slate-300 font-semibold">Value</th>
            <th className="text-right py-2 px-3 text-slate-300 font-semibold">Change</th>
          </tr>
        </thead>
        <tbody>
          {data.map((row, i) => (
            <tr key={i} className="border-b border-slate-700/50 hover:bg-slate-700/30">
              <td className="py-3 px-3 text-white">{row.label}</td>
              <td className="py-3 px-3 text-right text-white font-mono">
                {row.value}
                {row.unit && <span className="text-slate-400 ml-1">{row.unit}</span>}
              </td>
              <td className="py-3 px-3 text-right">
                {row.change !== undefined && (
                  <span className={`font-mono ${
                    row.status === "good" ? "text-green-400" :
                    row.status === "warning" ? "text-yellow-400" :
                    row.status === "bad" ? "text-red-400" :
                    "text-slate-400"
                  }`}>
                    {row.change > 0 ? "+" : ""}{row.change}%
                  </span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
