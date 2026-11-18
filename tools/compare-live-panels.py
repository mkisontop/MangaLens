"""Compare independently verified Android screenshots on the same emulator in both install orders."""
import csv
import json
from pathlib import Path
import statistics
import sys


SCENES = {"contest": "live-contest.json", "outfit": "live-panel.json"}
ORDERS = ("baseline-first", "candidate-first")


def read_panel(root, order, arm, scene, index):
    directory = root / ("live-published-page-" + order) / arm
    result = json.loads((directory / "live" / SCENES[scene]).read_text())
    requests = [json.loads(row) for row in (directory / "model-requests.jsonl").read_text().splitlines()]
    pages = [item for item in requests if item["images"]]
    assert len(pages) == len(SCENES) + int(arm == "candidate")
    page = pages[index]
    assert page["completed"] and page["ocr_hints"] == 0
    assert result["art_pixels_changed"] == 0 and result["art_pixels_checked"] > 80000
    assert result["display_ocr"] and result["first_display_ocr"] and result["image_transcription"]
    assert 0 < result["first_visible_ms"] <= result["complete_visible_ms"]
    return {
        "first_visible_ms": result["first_visible_ms"],
        "complete_visible_ms": result["complete_visible_ms"],
        "model_ms": page["elapsed_ms"],
        "upload_bytes": page["image_bytes"],
        "upload_pixels": sum(w * h for w, h in page["image_dimensions"]),
        "english": result["english"],
        "image_transcription": result["image_transcription"],
        "checked_art_pixels": result["art_pixels_checked"],
    }


def dense_pages(root):
    values = {"baseline": [], "candidate": []}
    by_order = {}
    for order in ORDERS:
        by_order[order] = {}
        for arm in values:
            path = root / ("live-published-page-" + order) / arm / "anchored.csv"
            with path.open() as source:
                rows = list(csv.DictReader(source))
            assert len(rows) == 7 and len({r["script"] for r in rows}) == 3
            assert all(int(r["lines"]) == 12 and int(r["source_pixels_remaining"]) < 50 and
                       int(r["art_max_error"]) <= 4 for r in rows)
            warmed = [int(r["full_draw_ms"]) for r in rows if int(r["trial"]) > 0]
            assert len(warmed) == 6
            values[arm].extend(warmed)
            by_order[order][arm] = statistics.median(warmed)
    return {"median_full_draw_ms": {arm: statistics.median(v) for arm, v in values.items()},
            "median_by_order": by_order, "warmed_draws_per_build": len(values["baseline"])}


def compare(root):
    pairs = []
    for order in ORDERS:
        for index, scene in enumerate(SCENES):
            pairs.append({"order": order, "scene": scene,
                          **{arm: read_panel(root, order, arm, scene, index) for arm in ("baseline", "candidate")}})
    metrics = ("first_visible_ms", "complete_visible_ms", "model_ms", "upload_bytes", "upload_pixels")
    ratio = lambda pair, name: pair["candidate"][name] / pair["baseline"][name]
    summary = {metric: {"baseline_median": statistics.median(p["baseline"][metric] for p in pairs),
                        "candidate_median": statistics.median(p["candidate"][metric] for p in pairs),
                        "median_paired_ratio": statistics.median(ratio(p, metric) for p in pairs)}
               for metric in metrics}
    dense = dense_pages(root)
    holdouts = {}
    for order in ORDERS:
        directory = root / ("live-published-page-" + order) / "candidate" / "live"
        held = json.loads((directory / "live-potion.json").read_text())
        assert held["first_display_ocr"] and held["display_ocr"] and held["art_pixels_changed"] == 0
        holdouts[order] = held
    projection = {}
    for order in ORDERS:
        directory = root / ("live-published-page-" + order) / "candidate"
        screen = json.loads((directory / "projection/projection-japanese.json").read_text())
        observed = [json.loads(row) for row in (directory / "projection-requests.jsonl").read_text().splitlines()]
        assert any(r["images"] > 0 and r["completed"] for r in observed), "Capture must reach the real vision model"
        assert screen["art_pixels_changed"] == 0 and screen["residual_ink_pixels"] < .02 * screen["source_ink_pixels"]
        projection[order] = screen
    result = {"pairs": pairs, "summary": summary, "anchored_dense_pages": dense,
              "additional_potion_panel": holdouts, "live_japanese_projection": projection}
    (root / "comparison.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)
    assert all(order["candidate"] < order["baseline"] for order in dense["median_by_order"].values())
    assert all(p["candidate"]["upload_pixels"] < p["baseline"]["upload_pixels"] for p in pairs)
    assert all(statistics.mean(ratio(p, "first_visible_ms") for p in pairs if p["order"] == order) < 1
               for order in ORDERS), "Both installation orders must show the first correct line sooner"
    for metric in ("first_visible_ms", "complete_visible_ms"):
        assert summary[metric]["median_paired_ratio"] <= .95, f"Expected at least 5% paired reduction in {metric}"


if __name__ == "__main__":
    compare(Path(sys.argv[1]))
