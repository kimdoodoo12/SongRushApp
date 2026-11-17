import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:songrush_core/songrush_core.dart';
import 'package:songrush_mobile/app/app_controller.dart';

import 'support/harness.dart';

const credits = {
  'ko': '데이터 출처: AnimeThemes, AniList, anime-offline-database, AniDB, Wikidata',
  'en': 'Data sources: AnimeThemes, AniList, anime-offline-database, AniDB, Wikidata',
  'ja': 'データ提供: AnimeThemes, AniList, anime-offline-database, AniDB, Wikidata',
};

void main() {
  testWidgets('create opens the nickname dialog; confirm unlocks sound and sends create', (tester) async {
    final h = Harness();
    await h.pumpApp(tester);
    expect(find.text('SongRush'), findsWidgets);
    expect(find.byKey(const ValueKey('nickname')), findsNothing);

    await tester.tap(find.byKey(const ValueKey('create')));
    await h.settle(tester);
    expect(find.byKey(const ValueKey('nickname')), findsOneWidget);
    expect(find.text('닉네임'), findsWidgets);
    expect(tester.widget<Text>(find.byKey(const ValueKey('nick-sub'))).data, '방 만들기');
    // A blank name keeps 확인 disabled.
    expect(isEnabled(tester, const ValueKey('nick-confirm')), isFalse);
    await tester.enterText(find.byKey(const ValueKey('nickname')), '   ');
    await tester.pump();
    expect(isEnabled(tester, const ValueKey('nick-confirm')), isFalse);

    await tester.enterText(find.byKey(const ValueKey('nickname')), '  Mina ');
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('nick-confirm')));
    await h.settle(tester);
    expect(h.unlocks, 1);
    final create = h.sent.single as CreateRequest;
    expect(create.nickname, 'Mina');
    expect(h.prefs.read(nicknameKey), 'Mina');
    // Connecting: 확인 stays disabled.
    expect(isEnabled(tester, const ValueKey('nick-confirm')), isFalse);

    await h.admit(tester);
    expect(find.byKey(const ValueKey('nickname')), findsNothing);
    expect(tester.widget<Text>(find.byKey(const ValueKey('ticket-code'))).data, 'K7XQM');
    await h.tearDown(tester);
  });

  testWidgets('join takes a 5-character code, upper-cased, and sends join', (tester) async {
    final h = Harness(prefs: {nicknameKey: 'Aki'});
    await h.pumpApp(tester);
    expect(isEnabled(tester, const ValueKey('join')), isFalse);
    await tester.enterText(find.byKey(const ValueKey('code')), 'k7xq');
    await tester.pump();
    expect(isEnabled(tester, const ValueKey('join')), isFalse);
    await tester.enterText(find.byKey(const ValueKey('code')), 'k7xqm');
    await tester.pump();
    expect(find.text('K7XQM'), findsOneWidget);
    expect(isEnabled(tester, const ValueKey('join')), isTrue);

    await tester.tap(find.byKey(const ValueKey('join')));
    await h.settle(tester);
    expect(tester.widget<Text>(find.byKey(const ValueKey('nick-sub'))).data, 'K7XQM 방 참가');
    // The last nickname is prefilled.
    expect(find.text('Aki'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('nick-confirm')));
    await h.settle(tester);
    final join = h.sent.single as JoinRequest;
    expect(join.code, 'K7XQM');
    expect(join.nickname, 'Aki');
    await h.tearDown(tester);
  });

  testWidgets('a /r/<CODE> link prefills the code and opens the join dialog', (tester) async {
    final h = Harness(initialCode: 'QWERT');
    await h.pumpApp(tester);
    expect(tester.widget<Text>(find.byKey(const ValueKey('nick-sub'))).data, 'QWERT 방 참가');
    await tester.tap(find.byKey(const ValueKey('nick-cancel')));
    await h.settle(tester);
    expect(find.byKey(const ValueKey('nickname')), findsNothing);
    expect(find.text('QWERT'), findsOneWidget);
    expect(h.sockets.sockets, isEmpty);
    await h.tearDown(tester);
  });

  testWidgets('a refused join shows the error in the dialog; cancel drops it', (tester) async {
    final h = Harness(prefs: {nicknameKey: 'Mina'});
    await h.pumpApp(tester);
    h.controller.openJoinDialog('ABCDE');
    await h.settle(tester);
    await tester.tap(find.byKey(const ValueKey('nick-confirm')));
    await h.settle(tester);
    h.receive({'type': 'error', 'code': 'room-not-found'});
    await h.settle(tester);
    expect(find.text('방을 찾을 수 없습니다.'), findsOneWidget);
    expect(isEnabled(tester, const ValueKey('nick-confirm')), isTrue);

    await tester.sendKeyEvent(LogicalKeyboardKey.escape);
    await h.settle(tester);
    expect(find.byKey(const ValueKey('nickname')), findsNothing);
    expect(find.text('방을 찾을 수 없습니다.'), findsNothing);
    await h.tearDown(tester);
  });

  testWidgets('a socket that closes before joined says disconnected', (tester) async {
    final h = Harness(prefs: {nicknameKey: 'Mina'});
    await h.pumpApp(tester);
    await tester.tap(find.byKey(const ValueKey('create')));
    await h.settle(tester);
    await tester.tap(find.byKey(const ValueKey('nick-confirm')));
    await h.settle(tester);
    h.socket.drop();
    await h.settle(tester);
    expect(find.text('서버와 연결이 끊어졌습니다.'), findsOneWidget);
    await h.tearDown(tester);
  });

  for (final MapEntry(key: lng, value: credit) in credits.entries) {
    testWidgets('the credit line in $lng, from the device language', (tester) async {
      final h = Harness(deviceLocales: [Locale(lng, lng == 'en' ? 'US' : null)]);
      await h.pumpApp(tester);
      expect(h.controller.language, lng);
      expect(tester.widget<Text>(find.byKey(const ValueKey('credit'))).data, credit);
      await h.tearDown(tester);
    });
  }

  testWidgets('an unsupported device language falls back to English', (tester) async {
    final h = Harness(deviceLocales: const [Locale('fr', 'FR')]);
    await h.pumpApp(tester);
    expect(tester.widget<Text>(find.byKey(const ValueKey('credit'))).data, credits['en']);
    await h.tearDown(tester);
  });

  testWidgets('the switcher changes the language and stores it', (tester) async {
    final h = Harness();
    await h.pumpApp(tester);
    expect(find.text('방 만들기'), findsOneWidget);

    await tapVisible(tester, find.byKey(const ValueKey('lang-ja')));
    await h.settle(tester);
    expect(find.text('ルームを作る'), findsOneWidget);
    expect(tester.widget<Text>(find.byKey(const ValueKey('credit'))).data, credits['ja']);
    expect(h.prefs.read('songrush.lang'), 'ja');

    await tapVisible(tester, find.byKey(const ValueKey('lang-en')));
    await h.settle(tester);
    expect(find.text('Create room'), findsOneWidget);
    await h.tearDown(tester);
  });

  testWidgets('a stored language wins over the device language', (tester) async {
    final h = Harness(prefs: {'songrush.lang': 'en'});
    await h.pumpApp(tester);
    expect(find.text('Create room'), findsOneWidget);
    await h.tearDown(tester);
  });
  testWidgets('the start screen and the dialog fit a 360 px phone in every language', (tester) async {
    final h = Harness(initialCode: 'K7XQM');
    await h.pumpApp(tester);
    tester.view.physicalSize = const Size(360, 640) * 3;
    for (final lng in ['en', 'ja', 'ko']) {
      h.controller.setLanguage(lng);
      await h.settle(tester);
      expect(find.byKey(const ValueKey('nick-confirm')), findsOneWidget);
    }
    await h.tearDown(tester);
  });
}
