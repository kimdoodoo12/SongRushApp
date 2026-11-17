# Copies the keys the mobile screens need from the web's dictionaries
# (frontend/src/i18n/{ko,en,ja}.json) into the gen-l10n ARB files
# (lib/l10n/app_<lng>.arb), cycle 148. Key names: the web's dotted key in
# camelCase, dots and dashes dropped (lobby.songCount -> lobbySongCount,
# error.bad-nickname -> errorBadNickname); each key's @description in
# app_ko.arb names the web key. {{x}} becomes {x}. Texts the web has no key
# for (cycle 150: the sound offset setting; cycle 153: the choice room's
# chat placeholder) live in APP_KEYS below, in all three languages, so a
# rerun keeps them. Add keys to KEYS (or APP_KEYS),
# then, from mobile/:
#   python -I tool/sync_l10n.py ../frontend/src/i18n lib/l10n
#   flutter gen-l10n
import json, re, sys
from pathlib import Path

web = Path(sys.argv[1])
out = Path(sys.argv[2])
KEYS = (
    ["app.title", "app.credit", "app.reconnecting"]
    + [f"start.{k}" for k in ["nickname", "createRoom", "orCode", "roomCode", "join", "joinTitle", "confirm", "cancel"]]
    + ["theme.sunset", "mood.tag.sunset"]
    + [f"skin.sunset.{k}" for k in ["title", "label", "admit", "start", "seats", "ready", "empty"]]
    + [f"lobby.{k}" for k in ["copy", "waiting", "songCount", "leave", "seats", "hostSeat", "of", "settingsTitle",
                             "whoHost", "whoGuest", "whoPending", "shareHint", "copied", "copyFailed",
                             "songCountDown", "songCountUp"]]
    + [f"filter.{k}" for k in ["type", "tier", "from", "to", "fromAny", "toAny", "reversed", "all", "op", "ed",
                              "easy", "normal", "hard", "years"]]
    + [f"answer.{k}" for k in ["label", "chat", "choice", "hintChat", "hintChoice"]]
    + [f"mode.{k}" for k in ["label", "first", "ranked", "hintFirst", "hintRanked"]]
    + [f"seat.{k}" for k in ["list", "empty", "host", "me"]]
    + [f"chat.{k}" for k in ["label", "input", "placeholder", "send", "live", "lobbyJoined"]]
    + ["game.disconnected", "game.replaced"]
    # cycle 149: the game and game-over screens.
    + ["lobby.abandoned"]
    + [f"stage.{k}" for k in ["label", "timeLeft", "connecting", "waitingNext", "round", "score", "seconds",
                             "watching", "loading", "loadFailed", "hint", "skip", "skipCancel", "skipCount",
                             "pickCount", "sub", "soon", "soonSub", "choiceSoon", "choiceHint", "choiceSub",
                             "pickWrong"]]
    + [f"reveal.{k}" for k in ["heading", "noWinner", "winner", "ranking", "label.ko", "label.en", "label.ja",
                              "label.romaji", "solvers", "next", "nextFinal", "rankPlace", "rankPoints",
                              "stampHit", "stampPass", "coverAlt", "coverAltIdle"]]
    + [f"score.{k}" for k in ["board", "ranking", "points", "rank", "final"]]
    + [f"game.{k}" for k in ["leave", "leaveAsk", "leaveConfirm", "leaveCancel", "toLobby"]]
    + [f"chat.{k}" for k in ["correctLine", "wrongLine", "correctRanked", "gameStart", "songStart", "gameOver",
                            "lobbyBack"]]
    + [f"answer.{k}" for k in ["choiceN", "markCorrect", "markWrong"]]
    + [f"seat.{k}" for k in ["correct", "score"]]
    # cycle 150: reconnect and lifecycle.
    + ["stage.unlockSound", "game.reset"]
)

# The app's own texts (no web key), by ARB name: ko, en, ja, and the
# description. Placeholders are written {x}.
APP_KEYS = {
    "offsetOpen": ("싱크", "Sync", "同期", "the header button that opens the sound offset setting"),
    "offsetTitle": ("소리 싱크 조절", "Sound sync", "音ズレ調整", "the sound offset setting's title"),
    "offsetHint": (
        "내 소리가 다른 사람보다 늦게 들리면 − 쪽으로, 빨리 들리면 + 쪽으로 옮겨 주세요. 다음 곡부터 적용됩니다.",
        "If your sound comes later than everyone else's, move it toward −; if earlier, toward +. It applies from the next song.",
        "自分の音がほかの人より遅れて聞こえるなら − に、早く聞こえるなら + に動かしてください。次の曲から反映されます。",
        "the sound offset setting's explanation",
    ),
    "offsetValue": ("{ms}ms", "{ms}ms", "{ms}ms", "the sound offset in ms, signed"),
    "offsetEarlier": ("더 빠르게", "Earlier", "早く", "moves the offset 10 ms earlier"),
    "offsetLater": ("더 늦게", "Later", "遅く", "moves the offset 10 ms later"),
    "offsetReset": ("0으로", "Reset", "リセット", "sets the offset back to 0"),
    "offsetClose": ("닫기", "Close", "閉じる", "closes the sound offset setting"),
    # cycle 153: chat never scores in a choice room (D-135), so its input
    # does not ask for a title; the web has no such key.
    "chatTalkPlaceholder": (
        "채팅으로 이야기해요",
        "Chat with everyone",
        "チャットで話そう",
        "the chat input's placeholder in a choice room, where chat is only talk",
    ),
}


def lookup(d, key):
    for part in key.split("."):
        d = d[part]
    if not isinstance(d, str):
        raise SystemExit(f"{key} is not a string")
    return d


def arb_name(key):
    parts = re.split(r"[.\-]", key)
    return parts[0] + "".join(p[:1].upper() + p[1:] for p in parts[1:])


def convert(text):
    if re.search(r"(?<!\{)\{(?!\{)|(?<!\})\}(?!\})", text):
        raise SystemExit(f"stray brace in {text!r}")
    return re.sub(r"\{\{(\w+)\}\}", r"{\1}", text)


dicts = {lng: json.loads((web / f"{lng}.json").read_text(encoding="utf-8")) for lng in ("ko", "en", "ja")}
keys = KEYS + sorted(f"error.{k}" for k in dicts["ko"]["error"])
names = [arb_name(k) for k in keys]
assert len(set(names)) == len(names), "duplicate ARB names"
assert not set(names) & set(APP_KEYS), "an app key shadows a web key"
for lng, d in dicts.items():
    arb = {"@@locale": lng}
    if lng == "ko":
        arb["@@x-source"] = "frontend/src/i18n/{ko,en,ja}.json; each @key description names the web key"
    for key, name in zip(keys, names):
        text = lookup(d, key)
        arb[name] = convert(text)
        if lng == "ko":
            meta = {"description": f"web: {key}"}
            ph = re.findall(r"\{\{(\w+)\}\}", text)
            if ph:
                meta["placeholders"] = {p: {} for p in dict.fromkeys(ph)}
            arb["@" + name] = meta
    for name, (*texts, description) in APP_KEYS.items():
        text = dict(zip(("ko", "en", "ja"), texts))[lng]
        arb[name] = text
        if lng == "ko":
            meta = {"description": f"app only: {description}"}
            ph = re.findall(r"\{(\w+)\}", text)
            if ph:
                meta["placeholders"] = {p: {} for p in dict.fromkeys(ph)}
            arb["@" + name] = meta
    (out / f"app_{lng}.arb").write_text(json.dumps(arb, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(len(keys), "web keys,", len(APP_KEYS), "app keys")
