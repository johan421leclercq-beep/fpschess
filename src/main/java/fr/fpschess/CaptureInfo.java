package fr.fpschess;

import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Square;

/**
 * Décrit une prise : c'est ce qui déclenchera le duel.
 * capturedSquare est différent de "to" uniquement pour la prise en passant.
 */
public record CaptureInfo(Piece attacker, Piece defender, Square from, Square to, Square capturedSquare) {}
