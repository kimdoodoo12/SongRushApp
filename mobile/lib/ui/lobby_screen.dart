import 'dart:developer' as developer;

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:songrush_core/songrush_core.dart';

import '../app/app_controller.dart';
import '../app/lobby_selection.dart';
import '../l10n/app_localizations.dart';
import 'chat_panel.dart';
import 'palette.dart';
import 'texts.dart';
import 'widgets.dart';

// The stepper's minus sign (U+2212) and a guest's count before any
// lobbySettings (U+2014), as LobbyScreen.jsx.
final String _minus = String.fromCharCode(0x2212);
final String _dash = String.fromCharCode(0x2014);

/// The lobby (LobbyScreen.jsx, the sunset mood), in one phone column: the
/// header with the code and the settings line, the marquee, the ticket
/// (code, share link with 복사, connected count), the settings card (the
/// host's controls, read-only for a guest), the start row (Play Song for
/// the host, the waiting line for a guest, 나가기), the server's error, the
/// eight seats, the chat and the footer.
class LobbyScreen extends StatelessWidget {
  const LobbyScreen({super.key, required this.controller});

  final AppController controller;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final c = controller;
    final state = c.session!;
    final code = state.roomCode!;
    final host = c.isHost;
    final shown = c.shownSelection;
    final online = state.players.where((p) => p.connected).length;
    final received = state.lobbySettings;
    final line = host
        ? settingsLine(t, c.selection.toOptions())
        : received == null
            ? ''
            : settingsLine(t, received.options);
    final error = state.error;
    return PageFrame(
      header: RoomHeader(code: code, line: line),
      children: [
        if (state.status == SessionStatus.reconnecting) ...[
          WarnText(t.appReconnecting),
          const SizedBox(height: 12),
        ],
        Marquee(t.skinSunsetTitle),
        const SizedBox(height: 22),
        _Ticket(link: c.shareLink(code), code: code, online: online),
        const SizedBox(height: 22),
        _SettingsCard(controller: c, shown: shown, host: host),
        const SizedBox(height: 22),
        Wrap(
          spacing: 14,
          runSpacing: 12,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            if (host)
              RoomButton(
                key: const ValueKey('start'),
                label: t.skinSunsetStart,
                kind: ButtonKind.go,
                onPressed: c.canStart ? c.startGame : null,
              )
            else
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const _Dots(),
                  const SizedBox(width: 10),
                  Flexible(child: Text(t.lobbyWaiting, style: Fonts.bodyStyle(color: Palette.dim))),
                ],
              ),
            RoomButton(key: const ValueKey('leave'), label: t.lobbyLeave, onPressed: c.leave),
          ],
        ),
        if (error != null) ...[
          const SizedBox(height: 12),
          WarnText(errorText(t, error)),
        ],
        const SizedBox(height: 34),
        _Seats(players: state.players, me: state.myName),
        const SizedBox(height: 24),
        ChatPanel(
          lines: c.chat,
          me: state.myName,
          roomCode: code,
          online: online,
          onSend: c.sendChat,
        ),
        Footer(language: c.language, onLanguage: c.setLanguage),
      ],
    );
  }
}

/// The ticket: the mood's tag and label, the code, the share link with 복사,
/// the hint or the copy's result, and the stub with the connected count.
class _Ticket extends StatefulWidget {
  const _Ticket({required this.link, required this.code, required this.online});

  final String link;
  final String code;
  final int online;

  @override
  State<_Ticket> createState() => _TicketState();
}

class _TicketState extends State<_Ticket> {
  // null: the hint; true or false: the last copy's result.
  bool? _copied;

  Future<void> _copy() async {
    try {
      await Clipboard.setData(ClipboardData(text: widget.link));
      if (mounted) setState(() => _copied = true);
    } catch (e) {
      // A refusing clipboard: the fallback text; the link stays on screen.
      developer.log('clipboard write failed: $e', name: 'songrush.app');
      if (mounted) setState(() => _copied = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final sub = switch (_copied) {
      true => t.lobbyCopied,
      false => t.lobbyCopyFailed,
      null => t.lobbyShareHint,
    };
    return Container(
      decoration: BoxDecoration(
        color: Palette.panel,
        border: Border.all(color: Palette.line),
        borderRadius: BorderRadius.circular(Palette.radius + 4),
      ),
      clipBehavior: Clip.antiAlias,
      child: IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(18, 18, 14, 18),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('${t.themeSunset} · ${t.moodTagSunset}', style: Fonts.bodyStyle(size: 12.5, color: Palette.a2)),
                    Text(t.skinSunsetLabel, style: Fonts.monoStyle(size: 11, letterSpacingEm: 0.16)),
                    Text(
                      widget.code,
                      key: const ValueKey('ticket-code'),
                      style: Fonts.displayStyle(size: 44, height: 1.05).copyWith(letterSpacing: 44 * 0.14),
                    ),
                    const SizedBox(height: 8),
                    Container(
                      padding: const EdgeInsets.fromLTRB(12, 6, 6, 6),
                      decoration: BoxDecoration(
                        color: Palette.bg,
                        border: Border.all(color: Palette.line),
                        borderRadius: BorderRadius.circular(Palette.radius + 2),
                      ),
                      child: Row(
                        children: [
                          Expanded(
                            child: Text(
                              widget.link,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: Fonts.monoStyle(size: 12, letterSpacingEm: 0),
                            ),
                          ),
                          const SizedBox(width: 8),
                          RoomButton(key: const ValueKey('copy'), label: t.lobbyCopy, compact: true, onPressed: _copy),
                        ],
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      sub,
                      key: const ValueKey('ticket-sub'),
                      style: Fonts.bodyStyle(size: 12.5, color: _copied == true ? Palette.ok : Palette.dim),
                    ),
                  ],
                ),
              ),
            ),
            Container(
              width: 84,
              decoration: const BoxDecoration(border: Border(left: BorderSide(color: Palette.line, width: 2))),
              padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 6),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text('${widget.online}', style: Fonts.displayStyle(size: 38, color: Palette.a1, height: 1)),
                  Text(t.lobbyOf(seatCount), style: Fonts.bodyStyle(size: 12.5, color: Palette.dim)),
                  const SizedBox(height: 10),
                  RotatedBox(
                    quarterTurns: 1,
                    child: Text(t.skinSunsetAdmit, style: Fonts.monoStyle(size: 10, letterSpacingEm: 0.3)),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The settings card: 게임 설정, who may change it, 곡 수 and the filters;
/// a guest's controls are disabled and show the host's received selection.
class _SettingsCard extends StatelessWidget {
  const _SettingsCard({required this.controller, required this.shown, required this.host});

  final AppController controller;
  final LobbySelection shown;
  final bool host;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final c = controller;
    final who = host
        ? t.lobbyWhoHost
        : c.session?.lobbySettings == null
            ? t.lobbyWhoPending
            : t.lobbyWhoGuest;
    void set(LobbySelection next) => c.setSelection(next);
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.baseline,
            textBaseline: TextBaseline.alphabetic,
            children: [
              Flexible(child: Text(t.lobbySettingsTitle, style: Fonts.displayStyle(size: 21))),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  who,
                  key: const ValueKey('who'),
                  textAlign: TextAlign.end,
                  style: Fonts.bodyStyle(size: 12.5, color: Palette.dim),
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          SongCountStepper(
            count: shown.songCount,
            enabled: host,
            onChanged: (n) => set(c.selection.copyWith(songCount: n)),
          ),
          const SizedBox(height: 16),
          SegGroup(
            label: t.filterType,
            options: [for (final v in typeOptions) (v, typeLabel(t, v))],
            value: shown.type,
            enabled: host,
            onPick: (v) => set(c.selection.copyWith(type: v)),
          ),
          const SizedBox(height: 16),
          SegGroup(
            label: t.filterTier,
            options: [for (final v in tierOptions) (v, tierLabel(t, v))],
            value: shown.tier,
            enabled: host,
            onPick: (v) => set(c.selection.copyWith(tier: v)),
          ),
          const SizedBox(height: 16),
          OptLabel(t.filterYears),
          const SizedBox(height: 6),
          Row(
            children: [
              Flexible(
                child: _YearSelect(
                  key: const ValueKey('year-from'),
                  label: t.filterFrom,
                  anyLabel: t.filterFromAny,
                  years: yearFromOptions,
                  value: shown.yearFrom,
                  enabled: host,
                  onChanged: (y) => set(c.selection.copyWith(yearFrom: y)),
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8),
                child: Text('~', style: Fonts.bodyStyle(color: Palette.dim)),
              ),
              Flexible(
                child: _YearSelect(
                  key: const ValueKey('year-to'),
                  label: t.filterTo,
                  anyLabel: t.filterToAny,
                  years: yearToOptions,
                  value: shown.yearTo,
                  enabled: host,
                  onChanged: (y) => set(c.selection.copyWith(yearTo: y)),
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          SegGroup(
            label: t.answerLabel,
            options: [for (final v in answerModeOptions) (v, answerModeLabel(t, v))],
            value: shown.answerMode,
            enabled: host,
            hint: answerHint(t, shown),
            onPick: (v) => set(c.selection.copyWith(answerMode: v)),
          ),
          const SizedBox(height: 16),
          SegGroup(
            label: t.modeLabel,
            options: [for (final v in gameModeOptions) (v, gameModeLabel(t, v))],
            value: shown.gameMode,
            enabled: host,
            hint: modeHint(t, shown),
            onPick: (v) => set(c.selection.copyWith(gameMode: v)),
          ),
          if (host && c.selection.isReversed) ...[
            const SizedBox(height: 12),
            WarnText(t.filterReversed),
          ],
        ],
      ),
    );
  }
}

/// The 곡 수 stepper (LobbyScreen.jsx Stepper): -, the count, +, within
/// 5-100 (D-137). The host can also type the count: the text is a draft
/// until the field loses focus or Enter, which rounds and clamps it to
/// 5-100; a draft that is not a number reverts. A guest sees the count
/// read-only, a dash before any lobbySettings.
class SongCountStepper extends StatefulWidget {
  const SongCountStepper({super.key, required this.count, required this.enabled, required this.onChanged});

  final int? count;
  final bool enabled;
  final ValueChanged<int> onChanged;

  @override
  State<SongCountStepper> createState() => _SongCountStepperState();
}

class _SongCountStepperState extends State<SongCountStepper> {
  final TextEditingController _text = TextEditingController();
  final FocusNode _focus = FocusNode();

  @override
  void initState() {
    super.initState();
    _text.text = _shown;
    _focus.addListener(() {
      if (!_focus.hasFocus) _commit();
    });
  }

  @override
  void didUpdateWidget(SongCountStepper old) {
    super.didUpdateWidget(old);
    // A count changed elsewhere (the buttons, a lobbySettings) replaces the
    // field's text unless the host is typing.
    if (!_focus.hasFocus && _text.text != _shown) _text.text = _shown;
  }

  @override
  void dispose() {
    _text.dispose();
    _focus.dispose();
    super.dispose();
  }

  String get _shown => widget.count == null ? _dash : '${widget.count}';

  void _step(int by) {
    final count = widget.count;
    if (count == null) return;
    final next = clampSongCount(count + by);
    _text.text = '$next';
    if (next != count) widget.onChanged(next);
  }

  void _commit() {
    final typed = num.tryParse(_text.text.trim());
    final count = widget.count;
    if (typed == null || !typed.isFinite) {
      _text.text = _shown;
      return;
    }
    final next = clampSongCount(typed.round());
    _text.text = '$next';
    if (next != count) widget.onChanged(next);
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final count = widget.count;
    final canDown = widget.enabled && count != null && count > minSongs;
    final canUp = widget.enabled && count != null && count < maxSongs;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        OptLabel(t.lobbySongCount),
        const SizedBox(height: 6),
        Row(
          children: [
            Semantics(
              label: t.lobbySongCountDown,
              child: _StepButton(key: const ValueKey('count-down'), text: _minus, onTap: canDown ? () => _step(-1) : null),
            ),
            const SizedBox(width: 8),
            SizedBox(
              width: 72,
              child: widget.enabled
                  ? TextField(
                      key: const ValueKey('count'),
                      controller: _text,
                      focusNode: _focus,
                      textAlign: TextAlign.center,
                      keyboardType: TextInputType.number,
                      textInputAction: TextInputAction.done,
                      inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(3)],
                      style: Fonts.displayStyle(size: 22),
                      onSubmitted: (_) => _commit(),
                    )
                  : Semantics(
                      label: t.lobbySongCount,
                      child: Text(
                        _shown,
                        key: const ValueKey('count-shown'),
                        textAlign: TextAlign.center,
                        style: Fonts.displayStyle(size: 26),
                      ),
                    ),
            ),
            const SizedBox(width: 8),
            Semantics(
              label: t.lobbySongCountUp,
              child: _StepButton(key: const ValueKey('count-up'), text: '+', onTap: canUp ? () => _step(1) : null),
            ),
          ],
        ),
      ],
    );
  }
}

class _StepButton extends StatelessWidget {
  const _StepButton({super.key, required this.text, required this.onTap});

  final String text;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => Opacity(
        opacity: onTap == null ? 0.55 : 1,
        child: Material(
          color: Palette.panelAlpha(0.8),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(Palette.radius),
            side: const BorderSide(color: Palette.line),
          ),
          child: InkWell(
            onTap: onTap,
            customBorder: RoundedRectangleBorder(borderRadius: BorderRadius.circular(Palette.radius)),
            child: SizedBox(
              width: 36,
              height: 36,
              child: Center(child: Text(text, style: Fonts.bodyStyle(size: 17.5))),
            ),
          ),
        ),
      );
}

/// One `.years` select: any year, or one of [years].
class _YearSelect extends StatelessWidget {
  const _YearSelect({
    super.key,
    required this.label,
    required this.anyLabel,
    required this.years,
    required this.value,
    required this.enabled,
    required this.onChanged,
  });

  final String label;
  final String anyLabel;
  final List<int> years;
  final int? value;
  final bool enabled;
  final ValueChanged<int?> onChanged;

  @override
  Widget build(BuildContext context) {
    // A received year outside the list still shows.
    final all = value == null || years.contains(value) ? years : [...years, value!];
    return Semantics(
      label: label,
      child: Opacity(
        opacity: enabled ? 1 : 0.55,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 10),
          decoration: BoxDecoration(
            color: Palette.bg,
            border: Border.all(color: Palette.line),
            borderRadius: BorderRadius.circular(Palette.radius),
          ),
          child: DropdownButtonHideUnderline(
            child: DropdownButton<int?>(
              value: value,
              isExpanded: true,
              isDense: true,
              dropdownColor: Palette.panel2,
              borderRadius: BorderRadius.circular(Palette.radius),
              style: Fonts.bodyStyle(size: 14),
              padding: const EdgeInsets.symmetric(vertical: 7),
              items: [
                DropdownMenuItem<int?>(value: null, child: Text(anyLabel, overflow: TextOverflow.ellipsis)),
                for (final y in all) DropdownMenuItem<int?>(value: y, child: Text('$y')),
              ],
              onChanged: enabled ? onChanged : null,
            ),
          ),
        ),
      ),
    );
  }
}

/// The eight seats in roster order (Seats.jsx with the lobby skin): the
/// initial on the name colour, the name with (방장) and (나), then 방장 or
/// the skin's ready word; an empty seat reads the skin's empty word.
class _Seats extends StatelessWidget {
  const _Seats({required this.players, required this.me});

  final List<Player> players;
  final String? me;

  Widget _seatAt(AppLocalizations t, int i) =>
      i < players.length ? _Seat(player: players[i], me: me) : _EmptySeat(text: t.skinSunsetEmpty);

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          t.lobbySeats(t.skinSunsetSeats, players.length).toUpperCase(),
          style: Fonts.monoStyle(size: 12, semiBold: true, letterSpacingEm: 0.14),
        ),
        const SizedBox(height: 10),
        Semantics(
          label: t.seatList,
          container: true,
          child: Column(
            children: [
              for (var row = 0; row < seatCount; row += 2) ...[
                if (row > 0) const SizedBox(height: 8),
                IntrinsicHeight(
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Expanded(child: _seatAt(t, row)),
                      const SizedBox(width: 8),
                      Expanded(child: _seatAt(t, row + 1)),
                    ],
                  ),
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }
}

class _Seat extends StatelessWidget {
  const _Seat({required this.player, required this.me});

  final Player player;
  final String? me;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    var name = player.name;
    if (player.host) name += ' ${t.seatHost}';
    if (player.name == me) name += ' ${t.seatMe}';
    final initial = player.name.isEmpty ? '' : String.fromCharCode(player.name.runes.first);
    return Opacity(
      opacity: player.connected ? 1 : 0.5,
      child: Container(
        key: ValueKey('seat-${player.name}'),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: Palette.panelAlpha(0.8),
          border: Border.all(color: Palette.line),
          borderRadius: BorderRadius.circular(Palette.radius),
        ),
        child: Row(
          children: [
            Container(
              width: 30,
              height: 30,
              decoration: BoxDecoration(color: nameColour(player.name), shape: BoxShape.circle),
              alignment: Alignment.center,
              child: ExcludeSemantics(
                child: Text(
                  initial,
                  style: Fonts.bodyStyle(size: 12.5, color: const Color(0xFF1A1020), weight: FontWeight.w700),
                ),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Fonts.bodyStyle(size: 13.5, weight: FontWeight.w700).copyWith(height: 1.3),
                  ),
                  Text(
                    player.host ? t.lobbyHostSeat : t.skinSunsetReady,
                    style: Fonts.monoStyle(size: 11, letterSpacingEm: 0),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _EmptySeat extends StatelessWidget {
  const _EmptySeat({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) => Container(
        alignment: Alignment.centerLeft,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 14),
        decoration: BoxDecoration(
          border: Border.all(color: Palette.line),
          borderRadius: BorderRadius.circular(Palette.radius),
        ),
        child: Text(text, style: Fonts.bodyStyle(size: 13, color: Palette.dim)),
      );
}

/// The waiting line's three dots (`.dots`), pulsing.
class _Dots extends StatefulWidget {
  const _Dots();

  @override
  State<_Dots> createState() => _DotsState();
}

class _DotsState extends State<_Dots> with SingleTickerProviderStateMixin {
  late final AnimationController _anim =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 1200))..repeat();

  @override
  void dispose() {
    _anim.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ExcludeSemantics(
      child: AnimatedBuilder(
        animation: _anim,
        builder: (context, _) => Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (var i = 0; i < 3; i++)
              Padding(
                padding: const EdgeInsets.only(right: 4),
                child: Opacity(
                  opacity: (_anim.value - i * 0.2) % 1.0 < 0.4 ? 1 : 0.3,
                  child: Container(
                    width: 6,
                    height: 6,
                    decoration: const BoxDecoration(color: Palette.a1, shape: BoxShape.circle),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
