/// Song count limits of one game; mirror the backend's 5..100 (D-137).
const int minSongs = 5;
const int maxSongs = 100;

/// Default song count picked in the lobby (D-047).
const int defaultSongs = 10;

/// Clamps [n] into [minSongs]..[maxSongs].
int clampSongCount(int n) => n.clamp(minSongs, maxSongs);
