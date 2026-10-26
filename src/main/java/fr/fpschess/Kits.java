package fr.fpschess;

import com.github.bhlangonijr.chesslib.PieceType;

import java.util.List;

/** Équipement de combat de chaque pièce : plus la pièce est forte, plus le kit l'est. */
public final class Kits {

    private Kits() {}

    private record Kit(String head, String chest, String legs, String feet,
                       String weapon, String offhand, List<String> gives, List<String> effects) {}

    /** Armure complète dans un matériau. */
    private static Kit full(String material, String weapon, String offhand, List<String> gives, List<String> effects) {
        return new Kit("minecraft:" + material + "_helmet", "minecraft:" + material + "_chestplate",
                "minecraft:" + material + "_leggings", "minecraft:" + material + "_boots",
                "minecraft:" + weapon, offhand, gives, effects);
    }

    private static Kit kit(PieceType type) {
        return switch (type) {
            case PAWN -> full("leather", "wooden_sword", null,
                    List.of("minecraft:bread 2"), List.of());
            case KNIGHT -> full("chainmail", "stone_sword", null,
                    List.of("minecraft:bow 1", "minecraft:arrow 16"), List.of("speed:0"));
            case BISHOP -> new Kit("minecraft:iron_helmet", "minecraft:iron_chestplate", null, null,
                    "minecraft:iron_sword", null,
                    List.of("minecraft:bow 1", "minecraft:arrow 16"), List.of("speed:0"));
            case ROOK -> full("iron", "iron_sword", "minecraft:shield",
                    List.of("minecraft:golden_apple 2"), List.of());
            case QUEEN -> full("diamond", "diamond_sword", null,
                    List.of("minecraft:bow 1", "minecraft:arrow 16", "minecraft:ender_pearl 2",
                            "minecraft:golden_apple 2"), List.of());
            case KING -> full("diamond", "diamond_sword", "minecraft:totem_of_undying",
                    List.of("minecraft:golden_apple 3"), List.of("resistance:0"));
            default -> full("leather", "wooden_sword", null, List.of(), List.of());
        };
    }

    public static String pieceName(PieceType type) {
        return switch (type) {
            case KING -> "Roi";
            case QUEEN -> "Dame";
            case ROOK -> "Tour";
            case BISHOP -> "Fou";
            case KNIGHT -> "Cavalier";
            case PAWN -> "Pion";
            default -> "?";
        };
    }

    /**
     * Équipe une cible (joueur ou mob) via des commandes vanilla.
     * sel = UUID de l'entité. Un mob (dummy) reçoit seulement armure + arme.
     */
    public static void apply(BoardRenderer r, String sel, PieceType type, boolean dummy) {
        Kit k = kit(type);
        slot(r, sel, "armor.head", k.head());
        slot(r, sel, "armor.chest", k.chest());
        slot(r, sel, "armor.legs", k.legs());
        slot(r, sel, "armor.feet", k.feet());
        slot(r, sel, "weapon.mainhand", k.weapon());
        if (dummy) return;
        slot(r, sel, "weapon.offhand", k.offhand());
        for (String give : k.gives()) {
            r.run("give " + sel + " " + give);
        }
        for (String eff : k.effects()) {
            String[] parts = eff.split(":");
            r.run("effect give " + sel + " minecraft:" + parts[0] + " 150 " + parts[1] + " true");
        }
    }

    private static void slot(BoardRenderer r, String sel, String slot, String item) {
        if (item != null) {
            r.run("item replace entity " + sel + " " + slot + " with " + item);
        }
    }
}
