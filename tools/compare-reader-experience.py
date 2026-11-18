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
        startup = read(root, build, "startup.csv")
        require(len(startup) == 1, "Missing stationary startup result: " + build)
        turns = read(root, build, "metrics.csv")
        dense = read(root, build, "buffered.csv")
        scrolls = read(root, build, "scroll.csv")
        uploads = read(root, build, "uploads.csv")
        erasures = read(root, build, "erasure.csv")
        published = read(root, build, "published.csv")
        artwork_scrolls = read(root, build, "published-scroll.csv")
        require({int(r["expected"]) for r in artwork_scrolls} == {-183, -121, -53, -17, -3, -1, 0, 2, 19, 78, 123, 181},
                "Missing real artwork scrolls: " + build)
        require({r["scene"] for r in published} == {"contest", "podium", "device"} and len(published) == 3,
                "Missing published Korean panels: " + build)
        require(all(r["english_read_back"] == "true" and r["original_marker_visible"] == "false" and int(r["art_pixels_checked"]) > 80000
                    and int(r["art_pixels_changed"]) == 0 for r in published),
                "The reader could not read a published panel or its art was altered: " + build)
        require({(r["paper"], int(r["trial"])) for r in erasures} ==
                {(paper, i) for paper in ("white", "colour", "gradient") for i in range(12)},
                "Missing reference images or erasure timings: " + build)
        require(all(int(r["pixels_changed_outside_mask"]) == 0 and int(r["max_channel_error"]) <= 16
                    and float(r["mean_channel_error"]) < 0.6 for r in erasures),
                "Erasure changed surrounding art or left lettering behind: " + build)
        require(len(scrolls) == 40 and len({(r["case"], r["trial"]) for r in scrolls}) == 40,
                "Missing registration cases: " + build)
        require({int(r["scroll_px"]) for r in uploads} == {121, -121} and len(uploads) == 2,
                "Missing Gemini uploads: " + build)
        require(all(int(r["final_lines"]) == 2 for r in uploads), "Scroll lost a translated line: " + build)
        require(all(r["observed"] == "null" or r["observed"] == r["expected"] for r in scrolls),
                "Lettering was moved to an unverified offset: " + build)
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
            "startup_translated_without_scrolling": startup[0]["translated"] == "true",
            "startup_observation_ms": int(startup[0]["elapsed_ms"]),
            "first_visible_ms": median(turns, "first_visible_ms"),
            "all_visible_ms": median(turns, "all_visible_ms"),
            "buffered_first_draw_ms": median(dense, "first_draw_ms"),
            "buffered_full_draw_ms": median(dense, "full_draw_ms"),
            "buffered_layouts": median(dense, "partial_layouts"),
            "erasure_ms": {paper: median([r for r in erasures if r["paper"] == paper and int(r["trial"]) >= 4], "ms")
                           for paper in ("white", "colour", "gradient")},
            "erased_image_crc": {paper: sorted({r["crc"] for r in erasures if r["paper"] == paper})
                                 for paper in ("white", "colour", "gradient")},
            "published_panels_read_back": len(published),
            "published_art_pixels_checked": sum(int(r["art_pixels_checked"]) for r in published),
            "published_art_pixels_changed": sum(int(r["art_pixels_changed"]) for r in published),
            "published_scrolls_correct": sum(r["expected"] == r["observed"] for r in artwork_scrolls),
            "scroll_cases_correct": sum(r["observed"] == r["expected"] for r in scrolls),
            "scroll_registration_ms": median([r for r in scrolls if int(r["trial"]) > 0], "ms"),
            "gemini_image_bytes": median(uploads, "jpeg_bytes"),
            "gemini_total_image_bytes": median(uploads, "total_jpeg_bytes"),
            "gemini_encoded_height": median(uploads, "encoded_height"),
            "scrolls_using_only_strip": sum(r["strip"] == "true" for r in uploads),
            "max_source_ink_remaining_pct": max(float(r["remaining_source_pct"]) for r in turns),
            "pages_with_hit_before_provider_reply": sum(
                int(r["first_visible_ms"]) < int(r["provider_start_ms"]) + 1800 for r in turns
            ),
        }
    print(json.dumps(result, indent=2), flush=True)
    (root / "comparison.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    before, after = result["baseline"], result["candidate"]
    require(after["startup_translated_without_scrolling"], "The controls left the first page unread")
    require(after["erased_image_crc"] == before["erased_image_crc"],
            "The optimized clean pages differ from the reference build")
    require(after["first_visible_ms"] + 450 < before["first_visible_ms"],
            "Cached lettering did not appear at least 450 ms sooner")
    require(after["pages_with_hit_before_provider_reply"] >= 4, "Cache waited for the provider on multiple turns")
    require(after["all_visible_ms"] <= before["all_visible_ms"] * 1.15 + 120,
            "Complete pages regressed on identical delayed responses")
    require(after["buffered_layouts"] <= 2 and before["buffered_layouts"] == 12,
            "A buffered twelve-line reply still triggered redundant page layouts")
    require(after["scroll_cases_correct"] == 40, "Incorrect scroll registration")
    require(after["published_scrolls_correct"] == 12, "Incorrect registration on the published artwork")
    require(after["scrolls_using_only_strip"] == 2 and after["gemini_image_bytes"] < before["gemini_image_bytes"] * .8,
            "The app reread the full frame where only a new strip was visible")
    require(after["buffered_first_draw_ms"] <= before["buffered_first_draw_ms"] * 1.20 + 18,
            "The first buffered line appeared later")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
