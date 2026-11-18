/// No page here: never a reload.
bool pageWasReloaded() => false;

/// No Screen Wake Lock here.
void Function(bool on)? screenWakeLock() => null;

/// No page to leave here: the PC version link is not shown.
void Function(String path)? pageNavigator() => null;
