package fr.fpschess;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.File;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Enveloppe autour de chesslib. Toute la logique des règles est ici.
 *
 * Flux prévu pour le duel (étape 5) :
 *   1. tryMove(...) vérifie la légalité et renvoie une CaptureInfo SANS jouer le coup.
 *   2. Si capture != null : duel. Si l'attaquant gagne -> applyMove(move). Sinon -> removeLoser(...).
 *   3. Si pas de capture : applyMove(move) directement.
 */
public class ChessGame {

    private final Board board = new Board();
    private Side forcedWinner = null; // victoire décidée hors échecs classiques (roi battu en duel, abandon)
    private String forcedReason = "";

    public Board board() {
        return board;
    }

    public Side sideToMove() {
        return board.getSideToMove();
    }

    public void reset() {
        forcedWinner = null;
        board.loadFromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
    }

    // ---------- Validation d'un coup ----------

    /** Parse "e2e4" ou "e7e8q" et vérifie la légalité. Ne joue PAS le coup. */
    public MoveResult tryMove(String text) {
        text = text.trim().toLowerCase(Locale.ROOT);
        if (!text.matches("[a-h][1-8][a-h][1-8][qrbn]?")) {
            return MoveResult.illegal("Format invalide. Exemple : e2e4 ou e7e8q (promotion).");
        }
        Square from = Square.fromValue(text.substring(0, 2).toUpperCase(Locale.ROOT));
        Square to = Square.fromValue(text.substring(2, 4).toUpperCase(Locale.ROOT));
        return tryMove(from, to, text.length() == 5 ? text.charAt(4) : 'q');
    }

    public MoveResult tryMove(Square from, Square to, char promoChar) {
        Piece moving = board.getPiece(from);
        if (moving == Piece.NONE) {
            return MoveResult.illegal("Aucune pièce en " + from + ".");
        }
        if (moving.getPieceSide() != board.getSideToMove()) {
            return MoveResult.illegal("Ce n'est pas le tour de cette couleur.");
        }

        // Promotion automatique si un pion atteint la dernière rangée
        Piece promo = Piece.NONE;
        if (moving.getPieceType() == PieceType.PAWN && isLastRank(to, moving.getPieceSide())) {
            promo = promotionPiece(moving.getPieceSide(), promoChar);
        }

        Move move = new Move(from, to, promo);
        if (!board.legalMoves().contains(move)) {
            return MoveResult.illegal("Coup illégal : " + from + " -> " + to + ".");
        }

        return MoveResult.ok(move, detectCapture(moving, move));
    }

    /** Vrai si le coup est un pion légal qui atteint la dernière rangée : il faut alors choisir la pièce. */
    public boolean needsPromotionChoice(Square from, Square to) {
        Piece p = board.getPiece(from);
        if (p == Piece.NONE || p.getPieceType() != PieceType.PAWN || !isLastRank(to, p.getPieceSide())) {
            return false;
        }
        return tryMove(from, to, 'q').legal();
    }

    private CaptureInfo detectCapture(Piece moving, Move move) {
        Square from = move.getFrom();
        Square to = move.getTo();

        Piece target = board.getPiece(to);
        if (target != Piece.NONE) {
            return new CaptureInfo(moving, target, from, to, to);
        }

        // Prise en passant : un pion change de colonne vers une case vide
        if (moving.getPieceType() == PieceType.PAWN && from.getFile() != to.getFile()) {
            Square victimSq = Square.encode(from.getRank(), to.getFile());
            Piece victim = board.getPiece(victimSq);
            return new CaptureInfo(moving, victim, from, to, victimSq);
        }
        return null;
    }

    // ---------- Application d'un coup ----------

    /** Joue le coup (déjà validé). Gère roque, en passant et promotion tout seul. */
    public void applyMove(Move move) {
        board.doMove(move);
    }

    /**
     * Cas où l'ATTAQUANT perd son duel : le défenseur reste en place,
     * l'attaquant disparaît, et c'est à l'adversaire de jouer.
     */
    public void removeLoserAttacker(CaptureInfo capture) {
        Side attackerSide = capture.attacker().getPieceSide();
        board.unsetPiece(capture.attacker(), capture.from());
        board.setSideToMove(attackerSide.flip());
        fixCastlingRights();

        // Si la pièce retirée protégeait son propre roi (pièce clouée), ce roi est maintenant en échec.
        // Laisser jouer l'adversaire donnerait une position impossible : c'est donc au camp en échec
        // de rejouer pour s'en sortir (et s'il ne peut pas, c'est échec et mat).
        board.setSideToMove(attackerSide);
        if (!board.isKingAttacked()) {
            board.setSideToMove(attackerSide.flip());
        }
    }

    /** Un roi a perdu son duel : la partie est terminée. */
    public void forceWin(Side winner) {
        forceWin(winner, "Le roi est tombé en duel !");
    }

    public void forceWin(Side winner, String reason) {
        forcedWinner = winner;
        forcedReason = reason;
    }

    /** Après le retrait d'une pièce, supprime les droits de roque devenus impossibles. */
    private void fixCastlingRights() {
        String[] f = board.getFen().split(" ");
        StringBuilder c = new StringBuilder();
        if (f[2].contains("K") && board.getPiece(Square.E1) == Piece.WHITE_KING && board.getPiece(Square.H1) == Piece.WHITE_ROOK) c.append('K');
        if (f[2].contains("Q") && board.getPiece(Square.E1) == Piece.WHITE_KING && board.getPiece(Square.A1) == Piece.WHITE_ROOK) c.append('Q');
        if (f[2].contains("k") && board.getPiece(Square.E8) == Piece.BLACK_KING && board.getPiece(Square.H8) == Piece.BLACK_ROOK) c.append('k');
        if (f[2].contains("q") && board.getPiece(Square.E8) == Piece.BLACK_KING && board.getPiece(Square.A8) == Piece.BLACK_ROOK) c.append('q');
        f[2] = c.length() == 0 ? "-" : c.toString();
        f[3] = "-"; // plus de prise en passant possible
        board.loadFromFen(String.join(" ", f));
    }

    // ---------- État de la partie ----------

    public List<String> legalMovesFrom(Square from) {
        List<String> result = new ArrayList<>();
        for (Move m : board.legalMoves()) {
            if (m.getFrom() == from) {
                result.add(m.toString());
            }
        }
        return result;
    }

    public Side forcedWinner() { return forcedWinner; }

    public String forcedReason() { return forcedReason; }

    public boolean isMate() { return forcedWinner == null && board.isMated(); }

    public boolean isStalemate() { return forcedWinner == null && board.isStaleMate(); }

    public boolean isDrawn() {
        return forcedWinner == null && !board.isMated() && !board.isStaleMate() && board.isDraw();
    }

    public boolean inCheck() {
        try {
            return board.isKingAttacked();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isOver() {
        return forcedWinner != null || board.isMated() || board.isStaleMate() || board.isDraw();
    }

    public String statusText() {
        if (forcedWinner != null) {
            return forcedReason + " Victoire des " + (forcedWinner == Side.WHITE ? "Blancs" : "Noirs") + ".";
        }
        if (board.isMated()) {
            Side winner = board.getSideToMove().flip();
            return "Échec et mat ! Victoire des " + (winner == Side.WHITE ? "Blancs" : "Noirs") + ".";
        }
        if (board.isStaleMate()) return "Pat : partie nulle.";
        if (board.isDraw()) return "Partie nulle (matériel insuffisant, répétition ou règle des 50 coups).";
        String turn = board.getSideToMove() == Side.WHITE ? "Blancs" : "Noirs";
        return "Trait aux " + turn + (board.isKingAttacked() ? " (ÉCHEC !)" : "") + ".";
    }

    // ---------- Utilitaires ----------

    private static boolean isLastRank(Square sq, Side side) {
        String s = sq.toString();
        return side == Side.WHITE ? s.endsWith("8") : s.endsWith("1");
    }

    private static Piece promotionPiece(Side side, char c) {
        boolean w = side == Side.WHITE;
        return switch (c) {
            case 'r' -> w ? Piece.WHITE_ROOK : Piece.BLACK_ROOK;
            case 'b' -> w ? Piece.WHITE_BISHOP : Piece.BLACK_BISHOP;
            case 'n' -> w ? Piece.WHITE_KNIGHT : Piece.BLACK_KNIGHT;
            default -> w ? Piece.WHITE_QUEEN : Piece.BLACK_QUEEN;
        };
    }

    /** Affichage texte du plateau pour tester dans le chat. */
    public String asciiBoard() {
        StringBuilder sb = new StringBuilder();
        for (int rank = 8; rank >= 1; rank--) {
            sb.append(rank).append("  ");
            for (File f : new File[]{File.FILE_A, File.FILE_B, File.FILE_C, File.FILE_D,
                    File.FILE_E, File.FILE_F, File.FILE_G, File.FILE_H}) {
                Square sq = Square.valueOf("" + f.getNotation().toUpperCase(Locale.ROOT) + rank);
                Piece p = board.getPiece(sq);
                sb.append(p == Piece.NONE ? "." : symbol(p)).append(' ');
            }
            sb.append('\n');
        }
        sb.append("   a b c d e f g h");
        return sb.toString();
    }

    private static String symbol(Piece p) {
        String letter = switch (p.getPieceType()) {
            case PAWN -> "p";
            case KNIGHT -> "n";
            case BISHOP -> "b";
            case ROOK -> "r";
            case QUEEN -> "q";
            case KING -> "k";
            default -> "?";
        };
        return p.getPieceSide() == Side.WHITE ? letter.toUpperCase(Locale.ROOT) : letter;
    }
}
