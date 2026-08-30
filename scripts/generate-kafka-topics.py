#!/usr/bin/env python3
"""Derive the kafka-topic-init topic map from the topics services actually use.

`kafka-topic-init` in docker-compose.yml is the authoritative topic provisioner
for local compose *and* for alpha (deploy-backend.sh runs it in both the full and
the config-only deploy mode). Its topic list used to be hand-written, and it had
drifted to 14 entries against ~35 topics the code configures. Everything absent
from it is created implicitly by the broker on first write, at Kafka defaults:
1 partition, cleanup.policy=delete, retention.ms = 7 days.

For `*.events.v1` and `*.commands.v1` that happens to match the intended config,
and `*.manifest.v1` drifts 3d -> 7d, which is longer and harmless. DLQs are the
case with real consequence: they are intended to hold poison messages for 30 days
so a human can investigate, and thirty of them were silently running at 7
(#1578, #1579).

Hand-maintaining the list is what produced that gap, so this script derives it:

  consumed  every @KafkaListener(topics = ...) default across pos-*/src/main/java,
            with ${property} placeholders resolved against the module's
            application.yml when the annotation carries no inline default.
  produced  every *topic property default in pos-*/src/main/resources/application.yml,
            every DomainTopics.events/commands/manifest("domain") call, and every
            *_TOPIC = "..." constant.
  dlq       "<topic>.dlq" for every consumed topic. A consumed topic implies a DLQ:
            the twelve KafkaErrorHandlingConfig classes all route poison messages
            with `record.topic() + ".dlq"` (ADR-0044 section 4), so the DLQ set is
            not independently declared anywhere and cannot be maintained by hand
            without repeating exactly this mistake.

Retention follows the suffix: .dlq 30d, .manifest.v1 3d, everything else 7d.

Usage:
  scripts/generate-kafka-topics.py            print the generated block
  scripts/generate-kafka-topics.py --apply    rewrite the block in docker-compose.yml
  scripts/generate-kafka-topics.py --check    exit 1 with a diff if the committed block is stale
  scripts/generate-kafka-topics.py --list     print "<topic> <retention-ms>" lines

scripts/check-kafka-topic-drift.sh runs this in CI and fails when the committed
block is stale.
"""

from __future__ import annotations

import argparse
import difflib
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
COMPOSE = ROOT / "docker-compose.yml"

BEGIN_MARKER = "# BEGIN GENERATED TOPICS (scripts/generate-kafka-topics.py)"
END_MARKER = "# END GENERATED TOPICS"

RETENTION_DLQ = 2_592_000_000  # 30d — poison messages must outlive an investigation
RETENTION_MANIFEST = 259_200_000  # 3d — reconciliation snapshots are re-emitted
RETENTION_DEFAULT = 604_800_000  # 7d — events and commands

# Topics that exist only as a Java string constant with no property behind them,
# and that the rules above cannot classify. Keep this list empty where possible.
#
# payment.settlement-config.v1 does not follow the {domain}.{kind}.v1 convention
# (ADR-0044 section 3) and is described in pos-invoice as a compacted config feed.
# It is provisioned here with the same delete retention the broker already gives
# it, so this changes nothing about it; switching it to cleanup.policy=compact is
# deliberately out of scope (#1579 non-goals).
#
# workorder-events is a pre-ADR-0044 topic name, still consumed by
# pos-customer's WorkorderEventHandler with pos.customer.kafka.enabled=true on
# alpha. Nothing publishes to it, so the listener looks dead, but the topic is
# derived from a live listener and is provisioned rather than quietly dropped.
NON_CONFORMING_NOTE = {
    "payment.settlement-config.v1": "compacted config feed, not {domain}.{kind}.v1",
    "sender.outcomes.v1": "externally owned feed, consumed only",
    "workorder-events": "pre-ADR-0044 name; consumed by pos-customer, published by nothing",
}

# A @KafkaListener annotation spans several lines, so match across them.
LISTENER_TOPICS = re.compile(r"@KafkaListener\((?:[^()]|\([^()]*\))*?topics\s*=\s*\"([^\"]+)\"", re.S)
# The domain argument is a literal in most call sites and a constant in a few
# (DomainTopics.events(ORDER_DOMAIN)). Both forms are matched; a constant is
# resolved against the same file, because the names are not globally unique —
# OWNER is "supplier" in one pos-catalog class and "inventory" in another.
DOMAIN_TOPICS_CALL = re.compile(
    r"DomainTopics\.(events|commands|manifest)\(\s*(?:\"([a-z][a-z0-9-]*)\"|([A-Z][A-Z0-9_]*))\s*\)"
)
STRING_CONSTANT = re.compile(r"\b([A-Z][A-Z0-9_]*)\s*=\s*\"([^\"]*)\"")
TOPIC_CONSTANT = re.compile(r"[A-Z][A-Z0-9_]*_TOPIC\s*=\s*\"([^\"]+)\"")
YAML_TOPIC_LINE = re.compile(r"^\s*[a-z0-9-]*topic:\s*(\S+)\s*(?:#.*)?$")
PLACEHOLDER = re.compile(r"^\$\{([^:}]+)(?::(.*))?\}$")
TOPIC_NAME = re.compile(r"^[a-z][a-z0-9.-]*$")


def module_dirs() -> list[pathlib.Path]:
    return sorted(p for p in ROOT.glob("pos-*") if p.is_dir())


def yaml_topic_properties(module: pathlib.Path) -> tuple[set[str], dict[str, str]]:
    """Topic values in a module's application.yml, plus a leaf-key -> value index.

    The index resolves @KafkaListener placeholders that carry no inline default:
    the property's default lives in the yaml instead. Keys are the last segment of
    the property path, which is unique within a module's kafka block.
    """
    topics: set[str] = set()
    by_leaf_key: dict[str, str] = {}
    config = module / "src" / "main" / "resources" / "application.yml"
    if not config.is_file():
        return topics, by_leaf_key
    for line in config.read_text(encoding="utf-8").splitlines():
        match = YAML_TOPIC_LINE.match(line)
        if not match:
            continue
        key = line.split(":", 1)[0].strip()
        value = resolve(match.group(1))
        if value is None:
            continue
        topics.add(value)
        by_leaf_key[key] = value
    return topics, by_leaf_key


def resolve(raw: str, by_leaf_key: dict[str, str] | None = None) -> str | None:
    """Resolve a config value to a literal topic name, or None if it is not one."""
    value = raw.strip().strip("'\"")
    placeholder = PLACEHOLDER.match(value)
    if placeholder:
        default = placeholder.group(2)
        if default:
            value = default
        elif by_leaf_key is not None:
            leaf = placeholder.group(1).rsplit(".", 1)[-1]
            resolved = by_leaf_key.get(leaf)
            if resolved is None:
                return None
            value = resolved
        else:
            return None
    return value if TOPIC_NAME.match(value) else None


def collect() -> tuple[set[str], set[str]]:
    """Return (consumed, produced) topic-name sets.

    A topic this cannot resolve is an error, never a silent omission: quietly
    dropping one reproduces exactly the gap this script exists to close.
    """
    consumed: set[str] = set()
    produced: set[str] = set()
    unresolved: list[str] = []

    for module in module_dirs():
        yaml_topics, by_leaf_key = yaml_topic_properties(module)
        produced |= yaml_topics

        sources = module / "src" / "main" / "java"
        if not sources.is_dir():
            continue
        for path in sorted(sources.rglob("*.java")):
            text = path.read_text(encoding="utf-8")
            where = path.relative_to(ROOT)

            for raw in LISTENER_TOPICS.findall(text):
                topic = resolve(raw, by_leaf_key)
                if topic:
                    consumed.add(topic)
                else:
                    unresolved.append(f"{where}: @KafkaListener topics = \"{raw}\"")

            constants = dict(STRING_CONSTANT.findall(text))
            for kind, literal, constant in DOMAIN_TOPICS_CALL.findall(text):
                domain = literal or constants.get(constant)
                if domain and TOPIC_NAME.match(domain):
                    produced.add(f"{domain}.{kind}.v1")
                else:
                    unresolved.append(f"{where}: DomainTopics.{kind}({constant})")

            for raw in TOPIC_CONSTANT.findall(text):
                topic = resolve(raw)
                if topic:
                    produced.add(topic)

    if unresolved:
        sys.exit(
            "ERROR: could not resolve these topic references to a literal name.\n"
            "Provisioning them is not optional — resolve them here, or the topics\n"
            "they name land on broker defaults with nothing to notice:\n  "
            + "\n  ".join(dict.fromkeys(unresolved))
        )

    return consumed, produced


def retention_for(topic: str) -> int:
    if topic.endswith(".dlq"):
        return RETENTION_DLQ
    if topic.endswith(".manifest.v1"):
        return RETENTION_MANIFEST
    return RETENTION_DEFAULT


def topic_map() -> list[tuple[str, int]]:
    consumed, produced = collect()
    if not consumed or not produced:
        sys.exit("ERROR: derived no topics — the extraction patterns no longer match the source")
    # Every consumed topic implies a DLQ; see the module docstring.
    topics = consumed | produced | {f"{topic}.dlq" for topic in consumed}
    return [(topic, retention_for(topic)) for topic in sorted(topics)]


def render(entries: list[tuple[str, int]], indent: str) -> str:
    dlq = sum(1 for topic, _ in entries if topic.endswith(".dlq"))
    lines = [
        f"{indent}{BEGIN_MARKER}",
        f"{indent}# {len(entries)} topics ({dlq} DLQ). Regenerate with",
        f"{indent}# scripts/generate-kafka-topics.py --apply after adding a listener or a",
        f"{indent}# *-topic property; CI fails on a stale block.",
        f"{indent}#",
        f"{indent}# Retention by suffix: .dlq 30d, .manifest.v1 3d, everything else 7d.",
    ]
    # Notes live here rather than beside their entry: the list is read back with
    # word splitting, where a trailing "# ..." would be data, not a comment.
    noted = [(topic, NON_CONFORMING_NOTE[topic]) for topic, _ in entries if topic in NON_CONFORMING_NOTE]
    if noted:
        lines.append(f"{indent}#")
        lines.append(f"{indent}# Names that do not follow ADR-0044 section 3:")
        lines.extend(f"{indent}#   {topic} — {note}" for topic, note in noted)
    lines.append(f'{indent}TOPICS="')
    width = max(len(topic) for topic, _ in entries)
    for topic, retention in entries:
        lines.append(f"{indent}{topic.ljust(width)} {retention}")
    lines.append(f'{indent}"')
    lines.append(f"{indent}{END_MARKER}")
    return "\n".join(lines) + "\n"


def splice(text: str, entries: list[tuple[str, int]]) -> str:
    begin = re.search(rf"^([ \t]*){re.escape(BEGIN_MARKER)}$", text, re.M)
    end = re.search(rf"^[ \t]*{re.escape(END_MARKER)}$", text, re.M)
    if not begin or not end or end.start() < begin.start():
        sys.exit(f"ERROR: generated-topics markers not found in {COMPOSE.relative_to(ROOT)}")
    return text[: begin.start()] + render(entries, begin.group(1)) + text[end.end() + 1 :]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--apply", action="store_true", help="rewrite the block in docker-compose.yml")
    group.add_argument("--check", action="store_true", help="exit 1 with a diff if the committed block is stale")
    group.add_argument("--list", action="store_true", help="print '<topic> <retention-ms>' lines")
    args = parser.parse_args()

    entries = topic_map()

    if args.list:
        for topic, retention in entries:
            print(topic, retention)
        return 0

    text = COMPOSE.read_text(encoding="utf-8")
    updated = splice(text, entries)

    if args.check:
        if updated == text:
            print(f"PASS: kafka-topic-init provisions all {len(entries)} topics services use")
            return 0
        name = str(COMPOSE.relative_to(ROOT))
        diff = difflib.unified_diff(
            text.splitlines(keepends=True),
            updated.splitlines(keepends=True),
            fromfile=f"{name} (committed)",
            tofile=f"{name} (derived)",
        )
        print("ERROR: the kafka-topic-init topic list does not match the topics services use")
        sys.stdout.writelines(diff)
        print("\n  Regenerate with: scripts/generate-kafka-topics.py --apply")
        return 1

    if args.apply:
        if updated != text:
            COMPOSE.write_text(updated, encoding="utf-8")
            print(f"Updated {COMPOSE.relative_to(ROOT)}: {len(entries)} topics")
        else:
            print(f"{COMPOSE.relative_to(ROOT)} already up to date: {len(entries)} topics")
        return 0

    sys.stdout.write(render(entries, "        "))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
