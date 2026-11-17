/// No page here: never a reload.
bool pageWasReloaded() => false;

/// No Screen Wake Lock here.
void Function(bool on)? screenWakeLock() => null;
