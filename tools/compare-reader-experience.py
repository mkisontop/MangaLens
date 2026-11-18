"""Compare two app builds measured on one Android screen with one test APK."""
import csv
import json
import statistics
import sys
from pathlib import Path


def read(root, build, name):
    with (root / build / name).open(encoding="utf-8") as rows:
        return list(csv.DictReader(rows))


def median(rows, field):
    return statistics.median(float(row[field]) for row in rows)


def require(condition, reason):
    if not condition:
        raise SystemExit(reason)


def main(root):
    result = {}
    for build in ("baseline", "candidate"):
        turns = read(root, build, "metrics.csv")
        dense = read(root, build, "buffered.csv")
        require([int(r["page"]) for r in turns] == list(range(6)), "Missing page turns: " + build)
        require([int(r["trial"]) for r in dense] == list(range(7)), "Missing dense pages: " + build)
        require({r["script"] for r in dense} == {"KO", "JA", "ZH"}, "Missing script: " + build)
        require(all(float(r["remaining_source_pct"]) < 10 and int(r["guard_error"]) <= 15 for r in turns),
                "Unreplaced source or changed artwork on page turns: " + build)
        require(all(int(r["source_pixels_remaining"]) < 50 and int(r["art_max_error"]) <= 4 and int(r["lines"]) == 12 for r in dense),
                "Unreplaced source, missing line, or changed artwork on dense pages: " + build)
        # The first page populates the text cache; the first dense draw warms fonts/JIT.
        turns, dense = turns[1:], dense[1:]
        result[build] = {
            "first_visible_ms": median(turns, "first_visible_ms"),
            "all_visible_ms": median(turns, "all_visible_ms"),
            "buffered_first_draw_ms": median(dense, "first_draw_ms"),
            "buffered_full_draw_ms": median(dense, "full_draw_ms"),
            "buffered_layouts": median(dense, "partial_layouts"),
            "max_source_ink_remaining_pct": max(float(r["remaining_source_pct"]) for r in turns),
            "pages_with_hit_before_provider_reply": sum(
                int(r["first_visible_ms"]) < int(r["provider_start_ms"]) + 1800 for r in turns
            ),
        }
    print(json.dumps(result, indent=2), flush=True)
    (root / "comparison.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    before, after = result["baseline"], result["candidate"]
    require(after["first_visible_ms"] + 450 < before["first_visible_ms"],
            "Cached lettering did not appear at least 450 ms sooner")
    require(after["pages_with_hit_before_provider_reply"] >= 4, "Cache waited for the provider on multiple turns")
    require(after["all_visible_ms"] <= before["all_visible_ms"] * 1.15 + 120,
            "Complete pages regressed on identical delayed responses")
    require(after["buffered_layouts"] <= 2 and before["buffered_layouts"] == 12,
            "A buffered twelve-line reply still triggered redundant page layouts")
    require(after["buffered_first_draw_ms"] <= before["buffered_first_draw_ms"] * 1.20 + 18,
            "The first buffered line appeared later")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
