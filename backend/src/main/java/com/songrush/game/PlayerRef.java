package com.songrush.game;

// A player in a room: a stable id and the display name other players see.
public record PlayerRef(String id, String name) {
}
