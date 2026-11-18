"""Compare independently verified Android screenshots on the same emulator in both install orders."""
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
    assert len(pages) == len(SCENES)
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
    result = {"pairs": pairs, "summary": summary}
    (root / "comparison.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)
    assert all(p["candidate"]["upload_pixels"] < p["baseline"]["upload_pixels"] for p in pairs)
    assert all(statistics.mean(ratio(p, "first_visible_ms") for p in pairs if p["order"] == order) < 1
               for order in ORDERS), "Both installation orders must show the first correct line sooner"
    for metric in ("first_visible_ms", "complete_visible_ms"):
        assert summary[metric]["median_paired_ratio"] <= .95, f"Expected at least 5% paired reduction in {metric}"


if __name__ == "__main__":
    compare(Path(sys.argv[1]))
