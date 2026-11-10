"""Draws a before/after comparison chart for one improvement step.

    python -X utf8 perf/compare.py --before <json ...> --after <json ...>
        --before-label "개선 전 (S3 직접)" --after-label "개선 후 (CloudFront)"
        --title "1단계: CloudFront 적용" --out docs/perf/step1/compare-step1.png

Left panel: prepare -> audio download done (pooled song median per format,
each run's median as dots). Right panel: audio download speed (pooled
per-song MB/s median, both formats). Prints the numbers it drew.

--panels picks the panels, in order, from download, speed and between
(default download,speed: the chart above). between (cycle 107, D-119) is
곡 사이 대기 (초): the pooled median of betweenSongs_ms per format, the
previous song's reveal -> this song audible, with the seconds saved, a dashed
line at the rule's minimum (reveal 5 s + start countdown 3 s) and, on the
after bars, how many songs were ready within the reveal card. --between is
an alias that appends between. Result files older than cycle 107 have no
such values and show "없음". Without --panels/--between the chart and the
printed lines are unchanged.

Needs matplotlib (the system Python). The numbers come from comparelib.py,
which perf/test_compare.py covers.
"""

import argparse
import sys
from datetime import datetime
from pathlib import Path

from comparelib import (DEFAULT_PANELS, between_summary, load, parse_panels, ratio,
                        ready_before_reveal_end, saving, summarize_group, wrap_label)
from perflib import EN_DASH, FORMATS
from report import FORMAT_NAMES, KST, plt, setup_font

BEFORE_COLOUR = "#9E9E9E"
AFTER_COLOUR = "#1E88E5"
DOT_COLOUR = "#212121"
FIGSIZE = (10, 520 / 120)  # inches; 1200 x 520 px at DPI
FIGSIZE_BETWEEN = (14, 520 / 120)  # 1680 x 520 px with the third panel
WIDTH_RATIOS = {"download": 1.5, "speed": 1, "between": 1.2}
# The rule's shortest gap between songs: reveal card 5 s + start countdown 3 s.
RULE_MIN_S = 8.0
RULE_MIN_LABEL = "규칙상 최소 (정답 공개 5초 + 시작 예고 3초)"
RULE_COLOUR = "#C62828"
SAVING_COLOUR = "#0D47A1"
DOTS_LABEL = "회차별 중앙값"
# Malgun Gothic has no U+2212 MINUS SIGN glyph, so the saving uses a plain hyphen.
MINUS = "-"
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
    parser.add_argument("--panels", default=",".join(DEFAULT_PANELS),
                        help="comma list of download, speed, between (default download,speed)")
    parser.add_argument("--between", action="store_true",
                        help="alias: append the between panel (곡 사이 대기, cycle 107)")
    # argparse already rejects bare positional arguments ("unrecognized arguments").
    args = parser.parse_args(argv)
    try:
        args.panels = parse_panels(args.panels, between=args.between)
    except ValueError as err:
        parser.error(str(err))
    return args


def _kst(stamp):
    return datetime.fromisoformat(stamp.replace("Z", "+00:00")).astimezone(KST)


def subtitle(before, after, between=None, dots=True):
    """Measurement time range and run/song counts, from the data.

    between: (before, after) between_summary dicts when the between panel is
    drawn (adds its song count); dots: whether run-median dots are drawn."""
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
        songs = f"모드별 {counts.pop()}곡"
    else:
        songs = ", ".join(f"{FORMAT_NAMES[fmt]} {before[fmt]['songs']}/{after[fmt]['songs']}곡"
                          for fmt in FORMATS)
    parts.append(f"{runs}, {songs}")
    if between is not None:
        values = {g[fmt]["values"] for g in between for fmt in FORMATS}
        if len(values) == 1:
            parts.append(f"곡 사이 대기는 2번째 곡부터 (모드별 {values.pop()}곡)")
        else:
            parts.append("곡 사이 대기는 2번째 곡부터")
    parts.append("막대: 전체 곡의 중앙값, 점: 회차별 중앙값" if dots else "막대: 전체 곡의 중앙값")
    return " · ".join(parts)


def _label(ax, x, y, text, colour):
    ax.annotate(text, (x, y), xytext=(0, 4), textcoords="offset points",
                ha="center", va="bottom", fontsize=11, fontweight="bold", color=colour)


def _ratio_label(ax, x, y, factor, lift=4):
    """"약 N배" above a point; nothing when the ratio cannot be computed."""
    if factor is None:
        return
    ax.annotate(f"약 {factor:.1f}배", (x, y), xytext=(0, lift), textcoords="offset points",
                ha="center", va="bottom", fontsize=10, fontweight="bold", color="#0D47A1")


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
                           zorder=3, label=DOTS_LABEL if i == 0 and group is before else None)
            top = max([top, value or 0] + runs)
            _inner_label(ax, x, value)
            if group is after:
                # How many times shorter the wait got, over the after bar and its dots.
                _ratio_label(ax, x, max([value or 0] + runs),
                             ratio(before[fmt]["audio_done_s"], value), lift=6)
    ax.set_xticks(range(len(FORMATS)), [FORMAT_NAMES[f] for f in FORMATS], fontsize=11)
    ax.set_ylim(0, top * 1.08 if top else 1)
    ax.set_ylabel("초")
    ax.set_title("음원 다운로드 완료까지 (초)", fontsize=12, pad=10)
    ax.grid(axis="y", alpha=0.3)
    ax.set_axisbelow(True)


def speed_panel(ax, before, after, labels, wrap=False):
    """wrap: put long tick labels on two lines (off for the default chart, so
    its output stays as it was)."""
    values = [before["all"]["mb_per_s"], after["all"]["mb_per_s"]]
    colours = [BEFORE_COLOUR, AFTER_COLOUR]
    ax.bar([0, 1], [v or 0 for v in values], 0.55, color=colours)
    for x, v, c in zip((0, 1), values, ("#424242", AFTER_COLOUR)):
        _label(ax, x, v or 0, "없음" if v is None else f"{v:.2f} MB/s", c)
    # How many times faster; sits above the after bar's MB/s label.
    _ratio_label(ax, 1, values[1] or 0, ratio(values[1], values[0]), lift=22)
    ticks = [wrap_label(t) for t in labels] if wrap else list(labels)
    ax.set_xticks([0, 1], ticks, fontsize=10)
    ax.set_xlim(-0.6, 1.6)
    top = max(v or 0 for v in values)
    ax.set_ylim(0, top * 1.3 if top else 1)
    ax.set_ylabel("MB/s")
    ax.set_title("음원 받는 속도 (MB/s)", fontsize=12, pad=10)
    ax.grid(axis="y", alpha=0.3)
    ax.set_axisbelow(True)


def _saving_text(seconds):
    if seconds >= 0:
        return f"{MINUS}{seconds:.1f}초"
    return f"+{-seconds:.1f}초"


def between_panel(ax, before, after, labels, ready=None, compact=False):
    """곡 사이 대기: previous reveal -> this song audible, pooled median per format.

    ready: {fmt: (ready, total) or None} of the after group, drawn in its bars.
    compact: the panel shares the figure, so its bars are narrow and the ready
    text goes on three smaller lines."""
    top = RULE_MIN_S
    for i, fmt in enumerate(FORMATS):
        for group, offset, colour, label in ((before, -BAR_WIDTH / 2, BEFORE_COLOUR, labels[0]),
                                             (after, BAR_WIDTH / 2, AFTER_COLOUR, labels[1])):
            value = group[fmt]["between_s"]
            x = i + offset
            ax.bar(x, value or 0, BAR_WIDTH * 0.92, color=colour, label=label if i == 0 else None)
            top = max(top, value or 0)
            if group is before:
                _inner_label(ax, x, value)
                continue
            counts = (ready or {}).get(fmt)
            if value is not None and counts is not None:
                # Value and ready count stacked in the middle of the after bar.
                ax.text(x, value * 0.6, f"{value:.1f}초", ha="center", va="center",
                        fontsize=12, fontweight="bold", color="white")
                if compact:
                    text, size = f"정답 공개\n안에 준비\n{counts[0]}/{counts[1]}", 8
                else:
                    text, size = f"정답 공개 안에\n준비 {counts[0]}/{counts[1]}", 9.5
                ax.text(x, value * 0.3, text, ha="center", va="center", fontsize=size,
                        color="white", linespacing=1.4)
            else:
                _inner_label(ax, x, value)
            saved = saving(before[fmt]["between_s"], value)
            if saved is not None:
                ax.annotate(_saving_text(saved), (x, value), xytext=(0, 5),
                            textcoords="offset points", ha="center", va="bottom",
                            fontsize=11, fontweight="bold", color=SAVING_COLOUR)
    ax.axhline(RULE_MIN_S, color=RULE_COLOUR, linestyle="--", linewidth=1.3, zorder=2,
               label=RULE_MIN_LABEL)
    ax.set_xticks(range(len(FORMATS)), [FORMAT_NAMES[f] for f in FORMATS], fontsize=11)
    ax.set_xlim(-0.6, len(FORMATS) - 0.4)
    ax.set_ylim(0, top * 1.15)
    ax.set_ylabel("초")
    ax.set_title("곡 사이 대기 (초)", fontsize=12, pad=10)
    ax.grid(axis="y", alpha=0.3)
    ax.set_axisbelow(True)


def draw(before, after, labels, title, out, panels=DEFAULT_PANELS, between=None, ready=None):
    """panels: names from comparelib.PANELS, in order. between: (before, after)
    between_summary dicts and ready: the after group's {fmt: (ready, total)},
    both needed when "between" is in panels."""
    setup_font()
    figsize = FIGSIZE_BETWEEN if len(panels) >= 3 else FIGSIZE
    fig, axes = plt.subplots(1, len(panels), figsize=figsize, dpi=DPI, squeeze=False,
                             gridspec_kw={"width_ratios": [WIDTH_RATIOS[p] for p in panels]})
    for ax, panel in zip(axes[0], panels):
        if panel == "download":
            time_panel(ax, before, after, labels)
        elif panel == "speed":
            speed_panel(ax, before, after, labels, wrap=tuple(panels) != DEFAULT_PANELS)
        else:
            between_panel(ax, between[0], between[1], labels, ready, compact=len(panels) > 1)
    fig.suptitle(title, fontsize=15, fontweight="bold", y=0.97)
    fig.text(0.5, 0.875,
             subtitle(before, after, between if "between" in panels else None,
                      dots="download" in panels),
             ha="center", fontsize=9.5, color="#616161")
    # One legend entry per name over every panel: before, after, the dots,
    # then the rule line.
    entries = {}
    for ax in axes[0]:
        for handle, name in zip(*ax.get_legend_handles_labels()):
            entries.setdefault(name, handle)
    rank = {labels[0]: 0, labels[1]: 1, DOTS_LABEL: 2, RULE_MIN_LABEL: 3}
    names = sorted(entries, key=lambda n: rank.get(n, 4))
    handles = [entries[n] for n in names]
    if len(names) > 3:
        # Four entries do not fit one line at this width: wrap the long ones.
        names = [wrap_label(n, 16) for n in names]
    fig.legend(handles, names, loc="lower center", bbox_to_anchor=(0.5, 0.0), ncol=len(names),
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


def report_between(name, group, ready=None):
    line = f"{name} between_s: " + " ".join(f"{key}={group[key]['between_s']}"
                                            for key in (*FORMATS, "all"))
    if ready is not None:
        line += " readyBeforeRevealEnd: " + " ".join(
            f"{fmt}={ready[fmt][0]}/{ready[fmt][1]}" if ready[fmt] else f"{fmt}=None"
            for fmt in FORMATS)
    print(line)


def main(argv=None):
    args = parse_args(argv)
    before_results = load(args.before)
    after_results = load(args.after)
    before = summarize_group(before_results)
    after = summarize_group(after_results)
    report("before", before)
    report("after", after)
    between = ready = None
    if "between" in args.panels:
        between = (between_summary(before_results), between_summary(after_results))
        ready = {fmt: ready_before_reveal_end(after_results, fmt) for fmt in FORMATS}
        report_between("before", between[0])
        report_between("after", between[1], ready)
    draw(before, after, (args.before_label, args.after_label), args.title, args.out,
         panels=args.panels, between=between, ready=ready)
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
