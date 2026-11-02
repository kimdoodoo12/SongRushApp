"""Settings shared by the data tools."""

from songrush_data import __version__

# CLAUDE.md external source rules: every request identifies as SongRushBot.
USER_AGENT = f"SongRushBot/{__version__} (+https://github.com/kimdoodoo12/SongRushApp)"
