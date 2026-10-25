package fr.fpschess;

import com.github.bhlangonijr.chesslib.move.Move;

/** Résultat d'une tentative de coup. capture est null s'il n'y a pas de prise. */
public record MoveResult(boolean legal, String message, Move move, CaptureInfo capture) {

    public static MoveResult illegal(String message) {
        return new MoveResult(false, message, null, null);
    }

    public static MoveResult ok(Move move, CaptureInfo capture) {
        return new MoveResult(true, "OK", move, capture);
    }
}
