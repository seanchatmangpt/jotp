import { ReactNode } from "react";

interface AnalysisSectionProps {
  title: string;
  description?: string;
  children: ReactNode;
  className?: string;
}

export function AnalysisSection({
  title,
  description,
  children,
  className = ""
}: AnalysisSectionProps) {
  return (
    <section className={`bg-slate-800/30 border border-slate-700 rounded-lg p-6 ${className}`}>
      <div className="mb-4">
        <h2 className="text-2xl font-bold text-white mb-2">{title}</h2>
        {description && (
          <p className="text-slate-400">{description}</p>
        )}
      </div>
      {children}
    </section>
  );
}
