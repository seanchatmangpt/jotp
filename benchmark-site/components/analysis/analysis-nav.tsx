"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const analyses = [
  { slug: "throughput", title: "Throughput" },
  { slug: "hot-path", title: "Hot Path" },
  { slug: "capacity", title: "Capacity" },
  { slug: "precision", title: "Precision" },
  { slug: "comparison", title: "Comparison" },
  { slug: "regression", title: "Regression" }
];

export function AnalysisNav() {
  const pathname = usePathname();
  const currentSlug = pathname.split("/").pop() || "";

  return (
    <nav className="bg-slate-800/50 border border-slate-700 rounded-lg p-4">
      <h3 className="text-lg font-semibold text-white mb-3">Analysis Types</h3>
      <ul className="space-y-2">
        {analyses.map((analysis) => (
          <li key={analysis.slug}>
            <Link
              href={`/analysis/${analysis.slug}`}
              className={`block px-3 py-2 rounded transition-colors ${
                currentSlug === analysis.slug
                  ? "bg-blue-600 text-white"
                  : "text-slate-300 hover:bg-slate-700 hover:text-white"
              }`}
            >
              {analysis.title}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  );
}
