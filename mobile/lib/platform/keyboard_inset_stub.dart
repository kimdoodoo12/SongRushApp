import 'package:flutter/foundation.dart';

/// No browser here: Flutter's own view insets apply.
ValueListenable<double>? browserKeyboardInset() => null;

/// No page to record the applied inset on.
void reportAppliedKeyboardInset(double inset) {}
