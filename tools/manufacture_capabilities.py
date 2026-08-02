#!/usr/bin/env python3
"""Independent replay/validation rail for the ggen-owned JOTP capability estate.

The canonical production projection remains `ggen sync run`. This script independently
parses the same RDF, executes the same SPARQL, renders the same Tera-compatible templates,
and emits a local replay receipt. It never edits ontology or templates.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import tomllib
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from jinja2 import Environment, StrictUndefined
from rdflib import Graph

ALLOWED_STANDINGS = {
    "UNKNOWN",
    "PARTIAL_ALIVE",
    "ALIVE",
    "BLOCKED",
    "BUILD_BROKEN",
    "UNSUPPORTED",
}
REQUIRED_LEGACY_OBSERVATIONS = {
    "otp-core-15",
    "supervisor-strategies",
    "typed-refusal",
    "excluded-families",
    "generation-ownership",
}


class Refusal(RuntimeError):
    def __init__(self, code: str, detail: str, exit_code: int) -> None:
        super().__init__(f"{code}:{detail}")
        self.code = code
        self.detail = detail
        self.exit_code = exit_code


@dataclass(frozen=True)
class Projection:
    name: str
    query: Path
    template: Path
    output: Path


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def load_config(root: Path, config_path: Path) -> tuple[dict[str, Any], list[Projection]]:
    data = tomllib.loads(config_path.read_text(encoding="utf-8"))
    rules = data.get("generation", {}).get("rules", [])
    projections: list[Projection] = []
    owners: dict[Path, str] = {}
    for raw in rules:
        output = Path(raw["output_file"])
        if output in owners:
            raise Refusal(
                "DUPLICATE_OUTPUT_OWNER_REFUSED",
                f"{output}:{owners[output]}:{raw['name']}",
                43,
            )
        owners[output] = raw["name"]
        projections.append(
            Projection(
                name=raw["name"],
                query=root / raw["query"]["file"],
                template=root / raw["template"]["file"],
                output=output,
            )
        )
    if not projections:
        raise Refusal("EMPTY_GENERATION_GRAPH_REFUSED", str(config_path), 44)
    return data, projections


def query_rows(graph: Graph, query_path: Path) -> list[dict[str, Any]]:
    result = graph.query(query_path.read_text(encoding="utf-8"))
    rows: list[dict[str, Any]] = []
    for binding in result.bindings:
        row: dict[str, Any] = {}
        for key, value in binding.items():
            python_value = value.toPython()
            row[str(key)] = python_value
        rows.append(row)
    return rows


def validate_rows(rows: list[dict[str, Any]]) -> None:
    if not rows:
        raise Refusal("EMPTY_CAPABILITY_ESTATE_REFUSED", "query returned zero rows", 45)

    ids = [str(row["id"]) for row in rows]
    if len(ids) != len(set(ids)):
        raise Refusal("DUPLICATE_CAPABILITY_REFUSED", "capability id collision", 46)

    ordinals = [int(row["ordinal"]) for row in rows]
    if ordinals != list(range(1, len(rows) + 1)):
        raise Refusal("NON_CONTIGUOUS_ORDINAL_REFUSED", repr(ordinals), 47)

    for row in rows:
        standing = str(row["standing"])
        if standing not in ALLOWED_STANDINGS:
            raise Refusal(
                "ONTOLOGY_STANDING_REFUSED",
                f"{row['id']}:{standing}",
                42,
            )
        for required in (
            "javaSymbol",
            "domain",
            "evidence",
            "sourcePath",
            "testPath",
            "legacyContract",
            "reason",
        ):
            if not str(row[required]).strip():
                raise Refusal("UNBOUNDED_CAPABILITY_REFUSED", f"{row['id']}:{required}", 48)

    core_ids = {
        "proc",
        "supervisor",
        "state-machine",
        "proc-ref",
        "proc-monitor",
        "proc-link",
        "proc-registry",
        "proc-timer",
        "proc-sys",
        "proc-lib",
        "crash-recovery",
        "parallel",
        "event-manager",
        "result",
        "exit-signal",
    }
    present = {str(row["id"]) for row in rows if str(row["domain"]) == "core"}
    if present != core_ids:
        raise Refusal("CORE_15_CLOSURE_REFUSED", repr(sorted(core_ids ^ present)), 49)

    alive = [str(row["id"]) for row in rows if str(row["standing"]) == "ALIVE"]
    if alive != ["capability-admission"]:
        raise Refusal("UNEXECUTED_ALIVE_CLAIM_REFUSED", repr(alive), 50)

    gaps = [
        str(row["id"])
        for row in rows
        if str(row["standing"]) in {"BLOCKED", "BUILD_BROKEN", "UNSUPPORTED"}
    ]
    expected_gaps = [
        "failover-state-recovery",
        "messaging-system",
        "message-patterns",
        "connection-pool",
    ]
    if gaps != expected_gaps:
        raise Refusal("GAP_TOPOLOGY_DRIFT_REFUSED", repr(gaps), 51)


def validate_legacy(legacy_path: Path) -> dict[str, Any]:
    data = json.loads(legacy_path.read_text(encoding="utf-8"))
    observed = {entry["id"] for entry in data.get("observations", [])}
    missing = REQUIRED_LEGACY_OBSERVATIONS - observed
    if missing:
        raise Refusal("LEGACY_CONTRACT_CLOSURE_REFUSED", repr(sorted(missing)), 52)
    if data["subject"]["base"] != "41d2716af7b7bec272b2df5e36d0781ddb7a2df5":
        raise Refusal("LEGACY_SUBJECT_IDENTITY_REFUSED", data["subject"]["base"], 53)
    return data


def render(root: Path, output_root: Path, config_path: Path, ontology_path: Path, legacy_path: Path) -> dict[str, Any]:
    _, projections = load_config(root, config_path)
    graph = Graph()
    graph.parse(ontology_path, format="turtle")
    validate_legacy(legacy_path)

    env = Environment(undefined=StrictUndefined, autoescape=False, keep_trailing_newline=True)
    output_hashes: dict[str, str] = {}
    row_count: int | None = None
    for projection in projections:
        rows = query_rows(graph, projection.query)
        validate_rows(rows)
        if row_count is None:
            row_count = len(rows)
        elif row_count != len(rows):
            raise Refusal("QUERY_CARDINALITY_DRIFT_REFUSED", projection.name, 54)
        template = env.from_string(projection.template.read_text(encoding="utf-8"))
        rendered = template.render(results=rows)
        target = output_root / projection.output
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(rendered, encoding="utf-8", newline="\n")
        output_hashes[str(projection.output)] = sha256_file(target)

    return {
        "schema": "jotp-local-replay-receipt/v1",
        "subject": {
            "repository": "seanchatmangpt/jotp",
            "base": "41d2716af7b7bec272b2df5e36d0781ddb7a2df5",
            "capabilityCount": row_count,
        },
        "inputs": {
            "ontology": {
                "path": str(ontology_path.relative_to(root)),
                "sha256": sha256_file(ontology_path),
            },
            "config": {
                "path": str(config_path.relative_to(root)),
                "sha256": sha256_file(config_path),
            },
            "legacy": {
                "path": str(legacy_path.relative_to(root)),
                "sha256": sha256_file(legacy_path),
                "ggenLegacyBase": "70e599a599fedb7c62c965377cc2f80df1fa01ec",
            },
            "ggenBase": "8351af4c5bbbf60bd99ab8417752a1762c6ea4e3",
        },
        "outputs": output_hashes,
        "standing": "ALIVE",
        "scope": "capability-admission projection only",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output-root", type=Path)
    parser.add_argument("--config", type=Path)
    parser.add_argument("--ontology", type=Path)
    parser.add_argument("--legacy", type=Path)
    parser.add_argument("--receipt", type=Path)
    args = parser.parse_args()

    root = args.root.resolve()
    output_root = (args.output_root or root).resolve()
    config = (args.config or root / "ggen.toml").resolve()
    ontology = (args.ontology or root / "ontology/jotp-capabilities.ttl").resolve()
    legacy = (args.legacy or root / "legacy/jotp-observable-contracts.json").resolve()

    try:
        receipt = render(root, output_root, config, ontology, legacy)
    except Refusal as refusal:
        print(f"{refusal.code}:{refusal.detail}", file=sys.stderr)
        return refusal.exit_code
    except Exception as error:
        print(f"MANUFACTURE_BROKEN:{type(error).__name__}:{error}", file=sys.stderr)
        return 70

    if args.receipt:
        args.receipt.parent.mkdir(parents=True, exist_ok=True)
        args.receipt.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(receipt, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
