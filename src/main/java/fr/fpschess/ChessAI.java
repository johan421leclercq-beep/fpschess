package fr.fpschess;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Petite IA d'échecs visant environ 1000 à 1100 Elo (estimation, pas une mesure officielle).
 *
 * Elle regarde 2 coups d'avance (son coup et ta réponse) en terminant les échanges de pièces en
 * cours : elle ne laisse donc pas traîner une pièce qui peut être prise, et prend ce qui est gratuit.
 * En revanche elle ne voit pas les combinaisons plus longues (fourchettes, clouages, mats en 2) et
 * fait de temps en temps une petite imprécision, comme un joueur humain.
 *
 * Réglages pour changer la difficulté : DEPTH (1 = plus faible, 3 = nettement plus fort),
 * MISTAKE_CHANCE (plus haut = plus d'erreurs) et MISTAKE_MARGIN.
 *
 * Une instance par recherche, utilisée par un seul fil : elle ne partage rien avec le jeu
 * (elle travaille sur une copie du plateau).
 */
public final class ChessAI {

    // ---------- Réglages ----------
    public static final int DEPTH = 2;
    private static final long TIME_LIMIT_MS = 3000;   // sécurité : la recherche s'arrête après ce temps
    private static final double MISTAKE_CHANCE = 0.08; // 8 % des coups sont une petite imprécision
    private static final int GOOD_MARGIN = 20;         // écart accepté (en centièmes de pion) pour varier les coups
    private static final int MISTAKE_MARGIN = 150;     // écart accepté lors d'une imprécision (1,5 pion)
    private static final int QUIESCE_DEPTH = 3;        // plies d'échanges suivis au bout de la recherche

    private static final int MATE = 100000;
    private static final int INF = 1000000;

    private final int depthLimit;
    private final double mistakeChance;
    private final Random random = new Random();

    public ChessAI() {
        this(DEPTH, MISTAKE_CHANCE);
    }

    /** Pour les tests et le réglage de la difficulté. */
    public ChessAI(int depthLimit, double mistakeChance) {
        this.depthLimit = depthLimit;
        this.mistakeChance = mistakeChance;
    }
    private long deadline;
    private boolean stopped;
    private int nodes;

    private record Scored(Move move, int score) {}

    // ---------- Case par case ----------

    private static final Square[] SQ;
    private static final int[] FILE_OF;
    private static final int[] RANK_OF;

    static {
        List<Square> list = new ArrayList<>();
        for (Square s : Square.values()) {
            if (s != Square.NONE) list.add(s);
        }
        SQ = list.toArray(new Square[0]);
        FILE_OF = new int[SQ.length];
        RANK_OF = new int[SQ.length];
        for (int i = 0; i < SQ.length; i++) {
            FILE_OF[i] = SQ[i].getFile().ordinal();
            RANK_OF[i] = SQ[i].getRank().ordinal();
        }
    }

    // ---------- Valeurs et tables de placement (vue des Blancs, rangée 8 en haut) ----------

    private static int value(PieceType t) {
        return switch (t) {
            case PAWN -> 100;
            case KNIGHT -> 320;
            case BISHOP -> 330;
            case ROOK -> 500;
            case QUEEN -> 900;
            default -> 0;
        };
    }

    private static final int[] PAWN_T = {
          0,  0,  0,  0,  0,  0,  0,  0,
         50, 50, 50, 50, 50, 50, 50, 50,
         10, 10, 20, 30, 30, 20, 10, 10,
          5,  5, 10, 25, 25, 10,  5,  5,
          0,  0,  0, 20, 20,  0,  0,  0,
          5, -5,-10,  0,  0,-10, -5,  5,
          5, 10, 10,-20,-20, 10, 10,  5,
          0,  0,  0,  0,  0,  0,  0,  0};
    private static final int[] KNIGHT_T = {
        -50,-40,-30,-30,-30,-30,-40,-50,
        -40,-20,  0,  0,  0,  0,-20,-40,
        -30,  0, 10, 15, 15, 10,  0,-30,
        -30,  5, 15, 20, 20, 15,  5,-30,
        -30,  0, 15, 20, 20, 15,  0,-30,
        -30,  5, 10, 15, 15, 10,  5,-30,
        -40,-20,  0,  5,  5,  0,-20,-40,
        -50,-40,-30,-30,-30,-30,-40,-50};
    private static final int[] BISHOP_T = {
        -20,-10,-10,-10,-10,-10,-10,-20,
        -10,  0,  0,  0,  0,  0,  0,-10,
        -10,  0,  5, 10, 10,  5,  0,-10,
        -10,  5,  5, 10, 10,  5,  5,-10,
        -10,  0, 10, 10, 10, 10,  0,-10,
        -10, 10, 10, 10, 10, 10, 10,-10,
        -10,  5,  0,  0,  0,  0,  5,-10,
        -20,-10,-10,-10,-10,-10,-10,-20};
    private static final int[] ROOK_T = {
          0,  0,  0,  0,  0,  0,  0,  0,
          5, 10, 10, 10, 10, 10, 10,  5,
         -5,  0,  0,  0,  0,  0,  0, -5,
         -5,  0,  0,  0,  0,  0,  0, -5,
         -5,  0,  0,  0,  0,  0,  0, -5,
         -5,  0,  0,  0,  0,  0,  0, -5,
         -5,  0,  0,  0,  0,  0,  0, -5,
          0,  0,  0,  5,  5,  0,  0,  0};
    private static final int[] QUEEN_T = {
        -20,-10,-10, -5, -5,-10,-10,-20,
        -10,  0,  0,  0,  0,  0,  0,-10,
        -10,  0,  5,  5,  5,  5,  0,-10,
         -5,  0,  5,  5,  5,  5,  0, -5,
          0,  0,  5,  5,  5,  5,  0, -5,
        -10,  5,  5,  5,  5,  5,  0,-10,
        -10,  0,  5,  0,  0,  0,  0,-10,
        -20,-10,-10, -5, -5,-10,-10,-20};
    private static final int[] KING_MID_T = {
        -30,-40,-40,-50,-50,-40,-40,-30,
        -30,-40,-40,-50,-50,-40,-40,-30,
        -30,-40,-40,-50,-50,-40,-40,-30,
        -30,-40,-40,-50,-50,-40,-40,-30,
        -20,-30,-30,-40,-40,-30,-30,-20,
        -10,-20,-20,-20,-20,-20,-20,-10,
         20, 20,  0,  0,  0,  0, 20, 20,
         20, 30, 10,  0,  0, 10, 30, 20};
    private static final int[] KING_END_T = {
        -50,-40,-30,-20,-20,-30,-40,-50,
        -30,-20,-10,  0,  0,-10,-20,-30,
        -30,-10, 20, 30, 30, 20,-10,-30,
        -30,-10, 30, 40, 40, 30,-10,-30,
        -30,-10, 30, 40, 40, 30,-10,-30,
        -30,-10, 20, 30, 30, 20,-10,-30,
        -30,-30,  0,  0,  0,  0,-30,-30,
        -50,-30,-30,-30,-30,-30,-30,-50};

    private static int[] table(PieceType t) {
        return switch (t) {
            case PAWN -> PAWN_T;
            case KNIGHT -> KNIGHT_T;
            case BISHOP -> BISHOP_T;
            case ROOK -> ROOK_T;
            default -> QUEEN_T;
        };
    }

    // ---------- Évaluation (du point de vue du camp qui doit jouer) ----------

    private int evaluate(Board board) {
        int white = 0;
        int black = 0;
        int nonPawnMaterial = 0;
        int whiteBishops = 0;
        int blackBishops = 0;
        int whiteKing = -1;
        int blackKing = -1;

        for (int i = 0; i < SQ.length; i++) {
            Piece p = board.getPiece(SQ[i]);
            if (p == Piece.NONE) continue;
            PieceType t = p.getPieceType();
            boolean isWhite = p.getPieceSide() == Side.WHITE;
            int idx = isWhite ? (7 - RANK_OF[i]) * 8 + FILE_OF[i] : RANK_OF[i] * 8 + FILE_OF[i];

            if (t == PieceType.KING) {
                if (isWhite) whiteKing = idx; else blackKing = idx;
                continue;
            }
            int v = value(t) + table(t)[idx];
            if (t != PieceType.PAWN) nonPawnMaterial += value(t);
            if (t == PieceType.BISHOP) {
                if (isWhite) whiteBishops++; else blackBishops++;
            }
            if (isWhite) white += v; else black += v;
        }

        int[] kingTable = nonPawnMaterial <= 2600 ? KING_END_T : KING_MID_T;
        if (whiteKing >= 0) white += kingTable[whiteKing];
        if (blackKing >= 0) black += kingTable[blackKing];
        if (whiteBishops >= 2) white += 30; // paire de fous
        if (blackBishops >= 2) black += 30;

        int score = white - black;
        return board.getSideToMove() == Side.WHITE ? score : -score;
    }

    // ---------- Ordre des coups : les prises les plus intéressantes d'abord ----------

    private int orderScore(Board board, Move m) {
        int score = 0;
        Piece victim = board.getPiece(m.getTo());
        if (victim != Piece.NONE) {
            Piece attacker = board.getPiece(m.getFrom());
            score += 10000 + value(victim.getPieceType()) * 10 - value(attacker.getPieceType());
        }
        if (m.getPromotion() != Piece.NONE) score += 9000;
        return score;
    }

    private List<Move> ordered(Board board) {
        List<Move> legal = new ArrayList<>(board.legalMoves());
        if (legal.size() < 2) return legal;
        List<Scored> scored = new ArrayList<>(legal.size());
        for (Move m : legal) scored.add(new Scored(m, orderScore(board, m)));
        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
        List<Move> out = new ArrayList<>(legal.size());
        for (Scored s : scored) out.add(s.move());
        return out;
    }

    // ---------- Recherche ----------

    private boolean outOfTime() {
        if ((++nodes & 1023) == 0 && System.nanoTime() > deadline) {
            stopped = true;
        }
        return stopped;
    }

    private int negamax(Board board, int depth, int alpha, int beta, int ply) {
        if (outOfTime()) return 0;
        if (depth <= 0) return quiesce(board, alpha, beta, 0, ply);

        List<Move> moves = ordered(board);
        if (moves.isEmpty()) {
            return board.isKingAttacked() ? -MATE + ply : 0; // mat ou pat
        }
        int best = -INF;
        for (Move m : moves) {
            board.doMove(m);
            int score = -negamax(board, depth - 1, -beta, -alpha, ply + 1);
            board.undoMove();
            if (stopped) return 0;
            if (score > best) best = score;
            if (score > alpha) alpha = score;
            if (alpha >= beta) break;
        }
        return best;
    }

    /** Suit les prises jusqu'à ce que la position soit calme, pour ne pas se tromper sur un échange à moitié fini. */
    private int quiesce(Board board, int alpha, int beta, int qdepth, int ply) {
        if (outOfTime()) return 0;
        boolean inCheck = board.isKingAttacked();
        int stand = evaluate(board);
        if (!inCheck) {
            if (stand >= beta) return stand;
            if (stand > alpha) alpha = stand;
            if (qdepth >= QUIESCE_DEPTH) return stand;
        } else if (qdepth >= QUIESCE_DEPTH + 2) {
            return stand;
        }

        List<Move> moves = ordered(board);
        if (moves.isEmpty()) {
            return inCheck ? -MATE + ply : 0;
        }
        int best = inCheck ? -INF : stand;
        for (Move m : moves) {
            boolean capture = board.getPiece(m.getTo()) != Piece.NONE || m.getPromotion() != Piece.NONE;
            if (!inCheck && !capture) continue;
            board.doMove(m);
            int score = -quiesce(board, -beta, -alpha, qdepth + 1, ply + 1);
            board.undoMove();
            if (stopped) return 0;
            if (score > best) best = score;
            if (score > alpha) alpha = score;
            if (alpha >= beta) break;
        }
        return best;
    }

    // ---------- Choix du coup ----------

    /** Choisit un coup pour la position donnée (FEN). Renvoie null s'il n'y a aucun coup légal. */
    public Move chooseMove(String fen) {
        Board board = new Board();
        board.loadFromFen(fen);
        List<Move> moves = ordered(board);
        if (moves.isEmpty()) return null;
        if (moves.size() == 1) return moves.get(0);

        deadline = System.nanoTime() + TIME_LIMIT_MS * 1_000_000L;
        stopped = false;
        nodes = 0;

        List<Scored> lastComplete = null;
        for (int depth = 1; depth <= depthLimit; depth++) {
            List<Scored> scores = new ArrayList<>();
            for (Move m : moves) {
                board.doMove(m);
                int s = -negamax(board, depth - 1, -INF, INF, 1);
                board.undoMove();
                if (stopped) break;
                scores.add(new Scored(m, s));
            }
            if (stopped) break; // recherche incomplète : on garde la profondeur précédente
            lastComplete = scores;
            scores.sort((a, b) -> Integer.compare(b.score(), a.score()));
            moves = new ArrayList<>();
            for (Scored s : scores) moves.add(s.move());
            if (scores.get(0).score() > MATE - 100) break; // mat forcé trouvé
        }

        if (lastComplete == null) return moves.get(0);
        return pick(lastComplete);
    }

    /** Joue souvent le meilleur coup, parfois un autre coup presque aussi bon, rarement une petite imprécision. */
    private Move pick(List<Scored> scored) {
        int best = Integer.MIN_VALUE;
        for (Scored s : scored) best = Math.max(best, s.score());

        if (best > MATE - 100) { // mat forcé : on le joue
            for (Scored s : scored) if (s.score() == best) return s.move();
        }

        int margin = random.nextDouble() < mistakeChance ? MISTAKE_MARGIN : GOOD_MARGIN;
        List<Move> candidates = new ArrayList<>();
        for (Scored s : scored) {
            // jamais un coup qui se fait mater, tant qu'une autre option existe
            if (s.score() >= best - margin && s.score() > -MATE + 100) candidates.add(s.move());
        }
        if (candidates.isEmpty()) {
            for (Scored s : scored) if (s.score() == best) return s.move();
        }
        return candidates.get(random.nextInt(candidates.size()));
    }
}
