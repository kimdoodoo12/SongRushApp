package com.songrush;

import org.springframework.boot.context.properties.ConfigurationProperties;

// songrush.game.prefetch-on-reveal (env SONGRUSH_GAME_PREFETCHONREVEAL): send the next song's prepare
// when a reveal starts, so clients download during the reveal (D-119, cycle 107). Absent or false
// keeps the prepare after the reveal pause.
@ConfigurationProperties(prefix = "songrush.game")
public record GameProperties(boolean prefetchOnReveal) {
}
