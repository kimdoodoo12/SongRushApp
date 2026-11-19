import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../app/language.dart';
import '../l10n/app_localizations.dart';
import 'palette.dart';

// The room stylesheet's shared pieces (room.css), phone-first: .btn,
// .btn.primary(.go), .opts, .ol, .oh, .warn, .seg, .marquee, and the page
// frame with the footer (language switcher, credit line).

/// The widest the content gets (phone-first; wider screens centre it).
const double contentMaxWidth = 480;

enum ButtonKind { plain, primary, go }

/// `.btn`, `.btn.primary` and `.btn.primary.go`.
class RoomButton extends StatelessWidget {
  const RoomButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.kind = ButtonKind.plain,
    this.compact = false,
    this.autofocus = false,
    this.focusNode,
  });

  final String label;

  /// Null disables the button.
  final VoidCallback? onPressed;
  final ButtonKind kind;

  /// The share row's and the stepper's smaller padding.
  final bool compact;

  final bool autofocus;
  final FocusNode? focusNode;

  @override
  Widget build(BuildContext context) {
    final primary = kind != ButtonKind.plain;
    final padding = switch (kind) {
      ButtonKind.go => const EdgeInsets.symmetric(horizontal: 40, vertical: 16),
      ButtonKind.primary => const EdgeInsets.symmetric(horizontal: 26, vertical: 14),
      ButtonKind.plain =>
        compact ? const EdgeInsets.symmetric(horizontal: 14, vertical: 6) : const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
    };
    final size = switch (kind) {
      ButtonKind.go => 19.0,
      ButtonKind.primary => 16.5,
      ButtonKind.plain => compact ? 13.5 : 15.0,
    };
    final style = ButtonStyle(
      padding: WidgetStatePropertyAll(padding),
      minimumSize: const WidgetStatePropertyAll(Size(0, 0)),
      tapTargetSize: MaterialTapTargetSize.shrinkWrap,
      shape: WidgetStatePropertyAll(RoundedRectangleBorder(borderRadius: BorderRadius.circular(Palette.radius))),
      side: WidgetStateProperty.resolveWith(
        (states) => BorderSide(color: primary ? Palette.a1 : Palette.line),
      ),
      backgroundColor: WidgetStatePropertyAll(primary ? Palette.a1 : Palette.panelAlpha(0.8)),
      foregroundColor: WidgetStatePropertyAll(primary ? Palette.bg : Palette.ink),
      overlayColor: WidgetStatePropertyAll(Palette.a2.withValues(alpha: 0.18)),
      textStyle: WidgetStatePropertyAll(
        Fonts.bodyStyle(size: size, weight: primary ? FontWeight.w700 : FontWeight.w500),
      ),
    );
    // .btn.go:disabled and the stepper's disabled buttons fade.
    return Opacity(
      opacity: onPressed == null ? 0.5 : 1,
      child: TextButton(
        onPressed: onPressed,
        autofocus: autofocus,
        focusNode: focusNode,
        style: style,
        child: Text(label),
      ),
    );
  }
}

/// `.opts`: the card of the start screen, the dialog and the settings.
class Panel extends StatelessWidget {
  const Panel({super.key, required this.child, this.padding = const EdgeInsets.fromLTRB(20, 18, 20, 18)});

  final Widget child;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) => Container(
        padding: padding,
        decoration: BoxDecoration(
          color: Palette.panelAlpha(0.85),
          border: Border.all(color: Palette.line),
          borderRadius: BorderRadius.circular(Palette.radius),
        ),
        child: child,
      );
}

/// `.ol`: a control's small mono label.
class OptLabel extends StatelessWidget {
  const OptLabel(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Text(text, style: Fonts.monoStyle(size: 11));
}

/// `.oh`: a small dim line.
class OptHint extends StatelessWidget {
  const OptHint(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Text(text, style: Fonts.bodyStyle(size: 12, color: Palette.dim));
}

/// `p.warn`.
class WarnText extends StatelessWidget {
  const WarnText(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Semantics(
        liveRegion: true,
        child: Text(text, style: Fonts.bodyStyle(size: 13.5, color: Palette.a3)),
      );
}

/// `.marquee`: the screen's big title.
class Marquee extends StatelessWidget {
  const Marquee(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Semantics(
        header: true,
        child: Text(text, style: Fonts.displayStyle(size: 30, height: 1.15)),
      );
}

/// One `.seg` group: toggle buttons with the current value pressed; a null
/// value presses none. Disabled (a guest) fades them.
class SegGroup extends StatelessWidget {
  const SegGroup({
    super.key,
    required this.label,
    required this.options,
    required this.value,
    required this.onPick,
    this.enabled = true,
    this.hint,
  });

  final String label;

  /// (value, label) pairs.
  final List<(String, String)> options;
  final String? value;
  final ValueChanged<String> onPick;
  final bool enabled;
  final String? hint;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        OptLabel(label),
        const SizedBox(height: 6),
        Semantics(
          label: label,
          container: true,
          child: Container(
            padding: const EdgeInsets.all(3),
            decoration: BoxDecoration(
              color: Palette.panel,
              border: Border.all(color: Palette.line),
              borderRadius: BorderRadius.circular(Palette.radius + 6),
            ),
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  for (final (v, text) in options)
                    Padding(
                      padding: const EdgeInsets.only(right: 2),
                      child: _SegButton(
                        text: text,
                        pressed: v == value,
                        onTap: enabled ? () => onPick(v) : null,
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
        if (hint != null) ...[const SizedBox(height: 6), OptHint(hint!)],
      ],
    );
  }
}

class _SegButton extends StatelessWidget {
  const _SegButton({super.key, required this.text, required this.pressed, required this.onTap});

  final String text;
  final bool pressed;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final child = Container(
      padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 5),
      decoration: BoxDecoration(
        color: pressed ? Palette.a1 : null,
        borderRadius: BorderRadius.circular(Palette.radius),
      ),
      child: Text(
        text,
        style: Fonts.bodyStyle(
          size: 12.5,
          color: pressed ? Palette.bg : Palette.dim,
          weight: pressed ? FontWeight.w700 : FontWeight.w400,
        ),
      ),
    );
    return Semantics(
      button: true,
      toggled: pressed,
      enabled: onTap != null,
      child: Opacity(
        opacity: onTap == null ? 0.55 : 1,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(Palette.radius),
          child: child,
        ),
      ),
    );
  }
}

/// The language switcher (LanguageSwitcher.jsx): each language's own name,
/// the current one pressed.
class LanguageSwitcher extends StatelessWidget {
  const LanguageSwitcher({super.key, required this.current, required this.onPick});

  final String current;
  final ValueChanged<String> onPick;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: 'Language',
      container: true,
      child: Wrap(
        alignment: WrapAlignment.center,
        spacing: 8,
        runSpacing: 8,
        children: [
          for (final code in languages)
            _SegButton(
              key: ValueKey('lang-$code'),
              text: languageNames[code]!,
              pressed: code == current,
              onTap: () => onPick(code),
            ),
        ],
      ),
    );
  }
}

/// The footer: the language switcher, the PC version link when
/// [onPcVersion] is given (the web build on the start screen and the lobby,
/// cycle 154, D-140), and the credit line (D-023).
class Footer extends StatelessWidget {
  const Footer({super.key, required this.language, required this.onLanguage, this.onPcVersion});

  final String language;
  final ValueChanged<String> onLanguage;

  /// Null hides the PC version link.
  final VoidCallback? onPcVersion;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: 32, bottom: 16),
      child: Column(
        children: [
          LanguageSwitcher(current: language, onPick: onLanguage),
          if (onPcVersion != null) ...[
            const SizedBox(height: 8),
            RoomButton(key: const ValueKey('pc-version'), label: t.appPcVersion, compact: true, onPressed: onPcVersion),
          ],
          const SizedBox(height: 8),
          Text(
            t.appCredit,
            key: const ValueKey('credit'),
            textAlign: TextAlign.center,
            style: Fonts.bodyStyle(size: 12, color: Palette.dim),
          ),
        ],
      ),
    );
  }
}

/// The page: the bg colour, a scroll view of the phone-wide column.
class PageFrame extends StatelessWidget {
  const PageFrame({super.key, this.header, required this.children});

  final Widget? header;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Palette.bg,
      body: SafeArea(
        child: Column(
          children: [
            ?header,
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 20),
                child: Center(
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: contentMaxWidth),
                    child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The header (room.css `header`): the logo, then the room code and the
/// settings line when in a room, and [trailing] at the end (the game's
/// 나가기, `.hd-right`).
class RoomHeader extends StatelessWidget {
  const RoomHeader({super.key, this.code, this.line = '', this.trailing});

  final String? code;
  final String line;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: Palette.bg.withValues(alpha: 0.78),
        border: const Border(bottom: BorderSide(color: Palette.line)),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Row(
        children: [
          Text('SongRush', style: Fonts.displayStyle(size: 23)),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                if (code != null)
                  Text(code!, style: Fonts.monoStyle(size: 15, color: Palette.ink, semiBold: true, letterSpacingEm: 0.18)),
                if (line.isNotEmpty)
                  Text(
                    line,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Fonts.bodyStyle(size: 12.5, color: Palette.dim),
                  ),
              ],
            ),
          ),
          ?trailing,
        ],
      ),
    );
  }
}

/// The leave confirmation (LeaveDialog.jsx, D-138): [ask] (the game's
/// 게임을 나갈까요? when null) with 취소 (focused on open; also Escape or a
/// tap outside the card) and 나가기, over a modal barrier. The lobby's
/// 방에서 나갈까요? and the start screen's SongRush를 나갈까요? (a back,
/// cycle 164) use it too.
class LeaveDialog extends StatelessWidget {
  const LeaveDialog({super.key, required this.onConfirm, required this.onCancel, this.ask});

  final VoidCallback onConfirm;
  final VoidCallback onCancel;
  final String? ask;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final ask = this.ask ?? t.gameLeaveAsk;
    return CallbackShortcuts(
      bindings: {const SingleActivator(LogicalKeyboardKey.escape): onCancel},
      child: FocusScope(
        autofocus: true,
        child: Stack(
          children: [
            ModalBarrier(
              key: const ValueKey('leave-barrier'),
              color: const Color(0x99000000),
              onDismiss: onCancel,
              semanticsLabel: t.gameLeaveCancel,
            ),
            Center(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 384),
                  child: Material(
                    key: const ValueKey('leave-dialog'),
                    color: Palette.panel,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(Palette.radius),
                      side: const BorderSide(color: Palette.line),
                    ),
                    child: Semantics(
                      scopesRoute: true,
                      namesRoute: true,
                      explicitChildNodes: true,
                      label: ask,
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(22, 20, 22, 18),
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            Semantics(header: true, child: Text(ask, style: Fonts.displayStyle(size: 21))),
                            const SizedBox(height: 18),
                            Row(
                              mainAxisAlignment: MainAxisAlignment.end,
                              children: [
                                RoomButton(
                                  key: const ValueKey('leave-cancel'),
                                  label: t.gameLeaveCancel,
                                  autofocus: true,
                                  onPressed: onCancel,
                                ),
                                const SizedBox(width: 8),
                                RoomButton(
                                  key: const ValueKey('leave-confirm'),
                                  label: t.gameLeaveConfirm,
                                  kind: ButtonKind.primary,
                                  onPressed: onConfirm,
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
