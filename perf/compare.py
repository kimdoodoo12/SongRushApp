"""Draws a before/after comparison chart for one improvement step.

    python -X utf8 perf/compare.py --before <json ...> --after <json ...>
        --before-label "개선 전 (S3 직접)" --after-label "개선 후 (CloudFront)"
        --title "1단계: CloudFront 적용" --out docs/perf/step1/compare-step1.png

Left panel: prepare -> audio download done (pooled song median per format,
each run's median as dots). Right panel: audio download speed (pooled
per-song MB/s median, both formats). Prints the numbers it drew.

Needs matplotlib (the system Python). The numbers come from comparelib.py,
which perf/test_compare.py covers.
"""

import argparse
import sys
from datetime import datetime
from pathlib import Path

from comparelib import load, summarize_group
from perflib import EN_DASH, FORMATS
from report import FORMAT_NAMES, KST, plt, setup_font

BEFORE_COLOUR = "#9E9E9E"
AFTER_COLOUR = "#1E88E5"
DOT_COLOUR = "#212121"
FIGSIZE = (10, 520 / 120)  # inches; 1200 x 520 px at DPI
DPI = 120
BAR_WIDTH = 0.36


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Draw a before/after comparison chart from perf/run.py result JSONs.")
    parser.add_argument("--before", nargs="+", required=True, help="result JSONs before the change")
    parser.add_argument("--after", nargs="+", required=True, help="result JSONs after the change")
    parser.add_argument("--before-label", required=True)
    parser.add_argument("--after-label", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--out", type=Path, required=True, help="PNG path to write")
    # argparse already rejects bare positional arguments ("unrecognized arguments").
    return parser.parse_args(argv)


def _kst(stamp):
    return datetime.fromisoformat(stamp.replace("Z", "+00:00")).astimezone(KST)


def subtitle(before, after):
    """Measurement time range and run/song counts, from the data."""
    parts = []
    stamps = [t for g in (before, after) if g["times"] for t in g["times"]]
    if stamps:
        times = sorted(_kst(t) for t in stamps)
        first, last = times[0], times[-1]
        if first.date() == last.date():
            span = f"{first:%Y-%m-%d %H:%M}{EN_DASH}{last:%H:%M}"
        else:
            span = f"{first:%Y-%m-%d %H:%M}{EN_DASH}{last:%Y-%m-%d %H:%M}"
        parts.append(f"측정 {span} (한국 시간)")
    if before["runs"] == after["runs"]:
        runs = f"각 {before['runs']}회"
    else:
        runs = f"개선 전 {before['runs']}회, 개선 후 {after['runs']}회"
    counts = {g[fmt]["songs"] for g in (before, after) for fmt in FORMATS}
    if len(counts) == 1:
        songs = f"형식별 {counts.pop()}곡"
    else:
        songs = ", ".join(f"{FORMAT_NAMES[fmt]} {before[fmt]['songs']}/{after[fmt]['songs']}곡"
                          for fmt in FORMATS)
    parts.append(f"{runs}, {songs}")
    parts.append("막대: 전체 곡의 중앙값, 점: 회차별 중앙값")
    return " · ".join(parts)


def _label(ax, x, y, text, colour):
    ax.annotate(text, (x, y), xytext=(0, 4), textcoords="offset points",
                ha="center", va="bottom", fontsize=11, fontweight="bold", color=colour)


def _inner_label(ax, x, value):
    """The value in white at the middle of its bar (the run dots sit near the top)."""
    if value is None:
        ax.text(x, 0, "없음", ha="center", va="bottom", fontsize=10, color="#424242")
        return
    ax.text(x, value / 2, f"{value:.1f}초", ha="center", va="center", fontsize=12,
            fontweight="bold", color="white")


def time_panel(ax, before, after, labels):
    top = 0
    for i, fmt in enumerate(FORMATS):
        for group, offset, colour, label in ((before, -BAR_WIDTH / 2, BEFORE_COLOUR, labels[0]),
                                             (after, BAR_WIDTH / 2, AFTER_COLOUR, labels[1])):
            s = group[fmt]
            x = i + offset
            value = s["audio_done_s"]
            ax.bar(x, value or 0, BAR_WIDTH * 0.92, color=colour, label=label if i == 0 else None)
            runs = s["run_medians_s"]
            if runs:
                n = len(runs)
                xs = [x + (k - (n - 1) / 2) * 0.07 for k in range(n)]
                ax.scatter(xs, runs, s=22, color="white", edgecolors=DOT_COLOUR, linewidths=1.1,
                           zorder=3, label="회차별 중앙값" if i == 0 and group is before else None)
            top = max([top, value or 0] + runs)
            _inner_label(ax, x, value)
    ax.set_xticks(range(len(FORMATS)), [FORMAT_NAMES[f] for f in FORMATS], fontsize=11)
    ax.set_ylim(0, top * 1.08 if top else 1)
    ax.set_ylabel("초")
    ax.set_title("음원 다운로드 완료까지 (초)", fontsize=12, pad=10)
    ax.grid(axis="y", alpha=0.3)
    ax.set_axisbelow(True)


def speed_panel(ax, before, after, labels):
    values = [before["all"]["mb_per_s"], after["all"]["mb_per_s"]]
    colours = [BEFORE_COLOUR, AFTER_COLOUR]
    ax.bar([0, 1], [v or 0 for v in values], 0.55, color=colours)
    for x, v, c in zip((0, 1), values, ("#424242", AFTER_COLOUR)):
        _label(ax, x, v or 0, "없음" if v is None else f"{v:.2f} MB/s", c)
    ax.set_xticks([0, 1], [labels[0], labels[1]], fontsize=10)
    ax.set_xlim(-0.6, 1.6)
    top = max(v or 0 for v in values)
    ax.set_ylim(0, top * 1.22 if top else 1)
    ax.set_ylabel("MB/s")
    ax.set_title("음원 받는 속도 (MB/s)", fontsize=12, pad=10)
    ax.grid(axis="y", alpha=0.3)
    ax.set_axisbelow(True)


def draw(before, after, labels, title, out):
    setup_font()
    fig, (left, right) = plt.subplots(1, 2, figsize=FIGSIZE, dpi=DPI,
                                      gridspec_kw={"width_ratios": [1.5, 1]})
    time_panel(left, before, after, labels)
    speed_panel(right, before, after, labels)
    fig.suptitle(title, fontsize=15, fontweight="bold", y=0.97)
    fig.text(0.5, 0.875, subtitle(before, after), ha="center", fontsize=9.5, color="#616161")
    handles, names = left.get_legend_handles_labels()
    # Before, after, then the dot marker.
    order = sorted(range(len(names)), key=lambda k: names[k] == "회차별 중앙값")
    handles, names = [handles[k] for k in order], [names[k] for k in order]
    fig.legend(handles, names, loc="lower center", bbox_to_anchor=(0.5, 0.0), ncol=3,
               fontsize=10, frameon=False)
    fig.tight_layout(rect=(0, 0.07, 1, 0.87))
    out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out, dpi=DPI)
    plt.close(fig)


def report(name, group):
    for key in (*FORMATS, "all"):
        s = group[key]
        print(f"{name} {key}: audio_done_s={s['audio_done_s']} run_medians_s={s['run_medians_s']} "
              f"min/max={s['run_min_s']}/{s['run_max_s']} mb_per_s={s['mb_per_s']} "
              f"readyBeforeStart={s['ready']}/{s['songs']}")
    print(f"{name} runs={group['runs']} times={group['times']}")


def main(argv=None):
    args = parse_args(argv)
    before = summarize_group(load(args.before))
    after = summarize_group(load(args.after))
    report("before", before)
    report("after", after)
    draw(before, after, (args.before_label, args.after_label), args.title, args.out)
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
