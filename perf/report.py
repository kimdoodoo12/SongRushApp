"""Draws the perf report charts from run.py result JSONs (cycle 103, D-114).

    python -X utf8 perf/report.py --results perf/results/2026-11-10-baseline-1.json [more ...] --out docs/perf

Per result: timeline-<label>.png (per song, prepare -> ready, start and audio
done, with the server's 25 s prepare timeout), waterfall-<label>-<format>-
song<N>.png (the network requests of the format's typical song; skipped when
the result has no per-request timeline, i.e. was recorded before cycle 103)
and bytes-<label>.png (audio and cover bytes per song). Over all results:
runs.png (the p->audio done median of each run, chat and choice).

Needs matplotlib (the system Python; the data venv does not have it). The
chart data comes from reportlib.py, which the unit tests cover.
"""

import argparse
import json
import math
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import matplotlib

matplotlib.use("Agg")

import matplotlib.pyplot as plt  # noqa: E402 (after the backend is chosen)
from matplotlib import font_manager  # noqa: E402

from perflib import FORMATS  # noqa: E402
from reportlib import bytes_series, runs_summary, typical_song, waterfall_rows  # noqa: E402

PERF_DIR = Path(__file__).resolve().parent
REPO_DIR = PERF_DIR.parent
DEFAULT_OUT = REPO_DIR / "docs" / "perf"

FONT = "Malgun Gothic"
FIGSIZE = (10, 5)  # inches; 1200 x 600 at DPI
DPI = 120
PREPARE_TIMEOUT_S = 25
# Korea Standard Time; Korea has no daylight saving, so a fixed offset is exact
# (and needs no tzdata on Windows).
KST = timezone(timedelta(hours=9), "KST")
FORMAT_NAMES = {"chat": "채팅", "choice": "객관식"}
FORMAT_COLOURS = {"chat": "#2b7bb9", "choice": "#e07b39"}
TIMELINE_SERIES = (
    ("prepareToReady_ms", "준비 신호 → ready 전송", "#9ecae1"),
    ("prepareToStart_ms", "준비 신호 → 곡 시작", "#4292c6"),
    ("prepareToAudioDone_ms", "준비 신호 → 음원 다운로드 완료", "#d94801"),
)
KIND_COLOURS = {"audio": "#2b7bb9", "cover": "#e07b39", "redirect": "#9e9e9e"}
AUDIO_COLOUR = "#2b7bb9"
COVER_COLOUR = "#f2a65a"
TIMEOUT_COLOUR = "#c62828"


def log(text):
    print(text, file=sys.stderr, flush=True)


def setup_font():
    """Malgun Gothic for the Korean text, or matplotlib's default with a warning."""
    try:
        font_manager.findfont(font_manager.FontProperties(family=FONT), fallback_to_default=False)
    except ValueError:
        log(f"font {FONT} not found; Korean text may not render")
    else:
        plt.rcParams["font.family"] = FONT
    plt.rcParams["axes.unicode_minus"] = False
    plt.rcParams["axes.spines.top"] = False
    plt.rcParams["axes.spines.right"] = False


def safe_name(label):
    """The label as run.py puts it in file names."""
    return re.sub(r"[^A-Za-z0-9._-]+", "-", label).strip("-") or "run"


def seconds(ms):
    return None if ms is None else ms / 1000


def kst(stamp):
    """run.py's UTC timestamp ("...Z") as "YYYY-MM-DD HH:MM" Korean time; "" if missing or unreadable."""
    if not stamp:
        return ""
    try:
        utc = datetime.strptime(stamp, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError:
        log(f"unreadable timestamp {stamp!r}; shown without a time")
        return ""
    return utc.astimezone(KST).strftime("%Y-%m-%d %H:%M")


def run_title(result):
    when = kst(result.get("timestamp"))
    return f"{result['label']} ({when} 한국 시간)" if when else result["label"]


def display_label(label):
    """A waterfall row label from reportlib, in Korean for the chart."""
    return (label.replace("audio redirect", "음원 리다이렉트")
            .replace("cover redirect", "표지 리다이렉트")
            .replace("audio", "음원")
            .replace("cover", "표지"))


def timeout_line(ax, horizontal=True):
    if horizontal:
        ax.axhline(PREPARE_TIMEOUT_S, color=TIMEOUT_COLOUR, linestyle="--", linewidth=1.2,
                   label=f"서버 준비 제한 {PREPARE_TIMEOUT_S}초")
    else:
        ax.axvline(PREPARE_TIMEOUT_S, color=TIMEOUT_COLOUR, linestyle="--", linewidth=1.2,
                   label=f"서버 준비 제한 {PREPARE_TIMEOUT_S}초")


def save(fig, path):
    fig.savefig(path, dpi=DPI)
    plt.close(fig)
    print(path.as_posix())


def formats_in(songs):
    return [fmt for fmt in FORMATS if any(s.get("format") == fmt for s in songs)]


def timeline_chart(result, out_dir):
    songs = result.get("songs") or []
    fmts = formats_in(songs)
    if not fmts:
        log(f"{result['label']}: no songs; timeline skipped")
        return
    fig, axes = plt.subplots(1, len(fmts), figsize=FIGSIZE, sharey=True, squeeze=False)
    width = 0.26
    top = PREPARE_TIMEOUT_S
    for ax, fmt in zip(axes[0], fmts):
        rows = sorted((s for s in songs if s.get("format") == fmt), key=lambda s: s["index"])
        xs = [s["index"] for s in rows]
        for k, (key, name, colour) in enumerate(TIMELINE_SERIES):
            offset = (k - 1) * width
            for x, s in zip(xs, rows):
                value = seconds(s.get(key))
                if value is None:
                    ax.text(x + offset, 0.3, "없음", ha="center", va="bottom", fontsize=8,
                            color="#757575")
                    continue
                top = max(top, value)
                ax.bar(x + offset, value, width, color=colour)
                if key == "prepareToAudioDone_ms":
                    ax.annotate(f"{value:.1f}", (x + offset, value), xytext=(0, 2),
                                textcoords="offset points", ha="center", va="bottom", fontsize=8)
        timeout_line(ax)
        ax.set_title(f"{FORMAT_NAMES[fmt]} 모드")
        ax.set_xticks(xs)
        ax.set_xlabel("곡 번호")
        ax.grid(axis="y", alpha=0.3)
    axes[0][0].set_ylabel("준비 신호(prepare)부터 걸린 시간 (초)")
    axes[0][0].set_ylim(0, top * 1.15)
    handles = [plt.Rectangle((0, 0), 1, 1, color=c) for _, _, c in TIMELINE_SERIES]
    labels = [n for _, n, _ in TIMELINE_SERIES]
    line = axes[0][0].get_lines()[0]
    fig.legend(handles + [line], labels + [line.get_label()], loc="lower center", ncol=4,
               fontsize=9, frameon=False)
    fig.suptitle(f"곡별 준비 시간 - {run_title(result)}")
    fig.tight_layout(rect=(0, 0.07, 1, 1))
    save(fig, out_dir / f"timeline-{safe_name(result['label'])}.png")


def row_colour(label):
    if "redirect" in label:
        return KIND_COLOURS["redirect"]
    return KIND_COLOURS["audio"] if label.startswith("audio") else KIND_COLOURS["cover"]


def waterfall_chart(result, fmt, out_dir):
    song = typical_song(result.get("songs") or [], fmt)
    if song is None:
        log(f"{result['label']} {fmt}: no song with an audio done time; waterfall skipped")
        return
    rows = waterfall_rows(song)
    if not rows:
        log(f"{result['label']} {fmt}: song {song['index']} has no per-request timeline "
            f"(recorded before cycle 103); waterfall skipped")
        return
    height = max(FIGSIZE[1], 1.6 + 0.42 * len(rows))
    fig, ax = plt.subplots(figsize=(FIGSIZE[0], height))
    ys = list(range(len(rows)))
    right = PREPARE_TIMEOUT_S
    for y, r in zip(ys, rows):
        start = r["start_ms"] / 1000
        colour = row_colour(r["label"])
        size = "크기 없음" if r["kb"] is None else f"{r['kb']:,.0f} KB"
        if r["end_ms"] is None:
            ax.plot(start, y, marker="o", color=colour)
            text, end = f"{size}, 끝 시각 없음", start
        else:
            end = r["end_ms"] / 1000
            ax.barh(y, end - start, left=start, height=0.6, color=colour)
            text = f"{size}, {end - start:.1f}초"
        right = max(right, end)
        ax.annotate(text, (end, y), xytext=(4, 0), textcoords="offset points", va="center", fontsize=8)
    ax.axvline(0, color="#424242", linewidth=1)
    start_s = seconds(song.get("prepareToStart_ms"))
    if start_s is not None:
        ax.axvline(start_s, color="#2e7d32", linestyle=":", linewidth=1.5, label=f"곡 시작 {start_s:.1f}초")
    timeout_line(ax, horizontal=False)
    ax.set_yticks(ys)
    ax.set_yticklabels([display_label(r["label"]) for r in rows])
    ax.invert_yaxis()
    ax.set_xlim(min(0, min(r["start_ms"] for r in rows) / 1000) - 0.3, right * 1.18)
    ax.set_xlabel("준비 신호(prepare)부터의 시각 (초)")
    ax.grid(axis="x", alpha=0.3)
    ax.legend(loc="lower right", fontsize=9)
    done = seconds(song.get("prepareToAudioDone_ms"))
    ax.set_title(f"네트워크 워터폴 - {result['label']} {FORMAT_NAMES[fmt]} {song['index']}번째 곡 "
                 f"(음원 완료 {done:.1f}초, 중앙값에 가장 가까운 곡)", fontsize=11)
    fig.tight_layout()
    save(fig, out_dir / f"waterfall-{safe_name(result['label'])}-{fmt}-song{song['index']}.png")


def bytes_chart(result, out_dir):
    songs = result.get("songs") or []
    fmts = formats_in(songs)
    if not fmts:
        log(f"{result['label']}: no songs; bytes chart skipped")
        return
    fig, axes = plt.subplots(1, len(fmts), figsize=FIGSIZE, sharey=True, squeeze=False)
    top = 0
    for ax, fmt in zip(axes[0], fmts):
        series = bytes_series(songs, fmt)
        xs = [i for i, _, _ in series]
        audio = [a / 1000 for _, a, _ in series]
        covers = [c / 1000 for _, _, c in series]
        ax.bar(xs, audio, 0.6, color=AUDIO_COLOUR, label="음원")
        ax.bar(xs, covers, 0.6, bottom=audio, color=COVER_COLOUR, label="표지 이미지")
        for x, a, c in zip(xs, audio, covers):
            top = max(top, a + c)
            ax.annotate(f"{a + c:.2f}", (x, a + c), xytext=(0, 2), textcoords="offset points",
                        ha="center", va="bottom", fontsize=8)
        ax.set_title(f"{FORMAT_NAMES[fmt]} 모드")
        ax.set_xticks(xs)
        ax.set_xlabel("곡 번호")
        ax.grid(axis="y", alpha=0.3)
    axes[0][0].set_ylabel("곡마다 받은 양 (MB)")
    axes[0][0].set_ylim(0, max(top, 0.1) * 1.15)
    handles, labels = axes[0][0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="lower center", ncol=2, fontsize=9, frameon=False)
    fig.suptitle(f"곡별 전송량 (음원 + 표지) - {run_title(result)}")
    fig.tight_layout(rect=(0, 0.06, 1, 1))
    save(fig, out_dir / f"bytes-{safe_name(result['label'])}.png")


def run_values(results, fmt):
    """Each run's p->audio done median of fmt in seconds, NaN where missing."""
    values = []
    for r in results:
        entry = (r.get("medians") or {}).get(fmt) or {}
        v = seconds(entry.get("prepareToAudioDone_ms"))
        values.append(math.nan if v is None else v)
    return values


def runs_chart(results, out_dir):
    summary = runs_summary(results)
    if not any(summary[fmt]["runs"] for fmt in FORMATS):
        log("no run has a p->audio done median; runs chart skipped")
        return
    fig, ax = plt.subplots(figsize=FIGSIZE)
    xs = list(range(len(results)))
    top = PREPARE_TIMEOUT_S
    values = {fmt: run_values(results, fmt) for fmt in FORMATS}
    for fmt in FORMATS:
        s = summary[fmt]
        if not s["runs"]:
            continue
        colour = FORMAT_COLOURS[fmt]
        lo, hi, mid = s["min"] / 1000, s["max"] / 1000, s["median"] / 1000
        top = max(top, hi)
        ax.axhspan(lo, hi, color=colour, alpha=0.08)
        ax.axhline(mid, color=colour, linestyle=":", linewidth=1)
        ax.plot(xs, values[fmt], marker="o", color=colour, linewidth=1.5,
                label=f"{FORMAT_NAMES[fmt]}: 중앙값 {mid:.1f}초 (범위 {lo:.1f}~{hi:.1f}초)")
    # At each run the higher of the two values is labelled above its point and
    # the lower one below, so the two labels never overlap; a lone value goes above.
    for i, x in enumerate(xs):
        pair = {fmt: values[fmt][i] for fmt in FORMATS if not math.isnan(values[fmt][i])}
        for fmt, v in pair.items():
            other = [w for f, w in pair.items() if f != fmt]
            above = not other or v >= other[0]
            if other and v == other[0]:
                above = fmt == "choice"
            ax.annotate(f"{v:.1f}", (x, v), xytext=(0, 8 if above else -9), textcoords="offset points",
                        ha="center", va="bottom" if above else "top", fontsize=9,
                        color=FORMAT_COLOURS[fmt], fontweight="bold")
    timeout_line(ax)
    ax.set_xticks(xs)
    ax.set_xticklabels([f"{r['label']}\n{kst(r.get('timestamp'))}" for r in results], fontsize=9)
    ax.set_xlim(-0.5, len(results) - 0.5)
    ax.set_ylim(0, top * 1.15)
    ax.set_xlabel("실행 (한국 시간)")
    ax.set_ylabel("준비 신호 → 음원 다운로드 완료, 곡 중앙값 (초)")
    ax.grid(axis="y", alpha=0.3)
    # The legend sits above the plot area, between the title and the axes.
    ax.legend(loc="lower center", bbox_to_anchor=(0.5, 1.01), ncol=3, fontsize=9, frameon=False)
    fig.suptitle("실행별 음원 준비 시간 (색 띠: 실행 간 최소~최대, 점선: 실행들의 중앙값)")
    fig.tight_layout(rect=(0, 0, 1, 0.95))
    save(fig, out_dir / "runs.png")


def load_result(path):
    try:
        result = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError) as err:
        raise SystemExit(f"cannot read {path}: {err}")
    if not isinstance(result, dict) or "label" not in result:
        raise SystemExit(f"{path} is not a perf result (no label)")
    return result


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Draw the perf report charts (timeline, waterfall, bytes, runs) "
                    "from perf/run.py result JSONs.")
    parser.add_argument("--results", nargs="+", required=True,
                        help="result JSONs, in the order runs.png shows them")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT,
                        help="folder for the PNGs (default docs/perf)")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    results = [load_result(p) for p in args.results]
    labels = [r["label"] for r in results]
    for label in sorted({x for x in labels if labels.count(x) > 1}):
        log(f"label {label} appears more than once; its per-run charts are overwritten by the later file")
    setup_font()
    args.out.mkdir(parents=True, exist_ok=True)
    for result in results:
        timeline_chart(result, args.out)
        for fmt in FORMATS:
            waterfall_chart(result, fmt, args.out)
        bytes_chart(result, args.out)
    runs_chart(results, args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
