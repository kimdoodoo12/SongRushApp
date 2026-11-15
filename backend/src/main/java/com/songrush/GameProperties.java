package com.songrush;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// songrush.game.prefetch-on-reveal (env SONGRUSH_GAME_PREFETCHONREVEAL): send the next song's prepare
// when a reveal starts, so clients download during the reveal (D-119, cycle 107). Absent or false
// keeps the prepare after the reveal pause.
// songrush.game.play-to-limit (env SONGRUSH_GAME_PLAYTOLIMIT): a song decided by answers keeps its
// reveal up until its deadline, so the music plays on, instead of for the reveal pause (D-129,
// cycle 126). On by default (cycle 132); false keeps every reveal at the reveal pause.
@ConfigurationProperties(prefix = "songrush.game")
public record GameProperties(boolean prefetchOnReveal, @DefaultValue("true") boolean playToLimit) {
}
