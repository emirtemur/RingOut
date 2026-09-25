package me.emirtemur.ringout.game;

public enum GameState {
    /** Players gather in the lobby; a lobby countdown may be running. */
    WAITING,
    /** The ring is being (re)built; nobody is teleported yet. */
    BUILDING,
    /** Players stand frozen on their slices. */
    COUNTDOWN,
    /** The fight is on. */
    ACTIVE,
    /** A winner was decided; everyone is sent back shortly. */
    ENDING
}
