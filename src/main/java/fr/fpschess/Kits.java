package fr.fpschess;

import com.github.bhlangonijr.chesslib.PieceType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;

/** Équipement de combat de chaque pièce : plus la pièce est forte, plus le kit l'est. */
public final class Kits {

    private Kits() {}

    /**
     * Un kit. gives = objets donnés tels quels ("objet quantité"). effects = "effet:niveau:secondes"
     * (niveau 0 = niveau I). extraHealth = points de vie en plus (2 points = 1 cœur).
     */
    /** Un objet donné directement dans l'inventaire (la commande /give refuse les UUID). */
    private record Give(Item item, int count) {}

    private record Kit(String head, String chest, String legs, String feet,
                       String weapon, String offhand, List<Give> gives, List<String> effects,
                       int extraHealth) {}

    private static final String LOYALTY_TRIDENT =
            "minecraft:trident[minecraft:enchantments={levels:{\"minecraft:loyalty\":3}}]";

    private static Kit kit(PieceType type) {
        return switch (type) {

            // Pion : cuir + épée en pierre
            case PAWN -> new Kit("minecraft:leather_helmet", "minecraft:leather_chestplate",
                    "minecraft:leather_leggings", "minecraft:leather_boots",
                    "minecraft:stone_sword", null, List.of(), List.of(), 0);

            // Fou : 4 perles d'Ender (sans dégâts de chute, voir DuelManager) + casque et plastron en mailles + épée en pierre
            case BISHOP -> new Kit("minecraft:chainmail_helmet", "minecraft:chainmail_chestplate", null, null,
                    "minecraft:stone_sword", null,
                    List.of(new Give(Items.ENDER_PEARL, 4)), List.of(), 0);

            // Cavalier : saut 2 pendant 1 min + 8 charges de vent + masse + casque et plastron en mailles
            case KNIGHT -> new Kit("minecraft:chainmail_helmet", "minecraft:chainmail_chestplate", null, null,
                    "minecraft:mace", null,
                    List.of(new Give(Items.WIND_CHARGE, 8)), List.of("jump_boost:1:60"), 0);

            // Tour : trident Loyauté 3 + casque et jambières en fer + plastron en mailles + résistance 1
            case ROOK -> new Kit("minecraft:iron_helmet", "minecraft:chainmail_chestplate",
                    "minecraft:iron_leggings", null,
                    LOYALTY_TRIDENT, null,
                    List.of(), List.of("resistance:0:150"), 0);

            // Dame : fer complet + épée en fer + 1 pomme dorée + bouclier + 5 cœurs en plus
            case QUEEN -> new Kit("minecraft:iron_helmet", "minecraft:iron_chestplate",
                    "minecraft:iron_leggings", "minecraft:iron_boots",
                    "minecraft:iron_sword", "minecraft:shield",
                    List.of(new Give(Items.GOLDEN_APPLE, 1)), List.of(), 10);

            // Roi : totem + 1 pomme dorée enchantée + mailles complètes + épée en pierre
            case KING -> new Kit("minecraft:chainmail_helmet", "minecraft:chainmail_chestplate",
                    "minecraft:chainmail_leggings", "minecraft:chainmail_boots",
                    "minecraft:stone_sword", "minecraft:totem_of_undying",
                    List.of(new Give(Items.ENCHANTED_GOLDEN_APPLE, 1)), List.of(), 0);

            default -> new Kit("minecraft:leather_helmet", "minecraft:leather_chestplate",
                    "minecraft:leather_leggings", "minecraft:leather_boots",
                    "minecraft:wooden_sword", null, List.of(), List.of(), 0);
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

    /** "la dame", "le pion"... (en minuscules, pour les phrases). */
    public static String article(PieceType type) {
        return switch (type) {
            case KING -> "le roi";
            case QUEEN -> "la dame";
            case ROOK -> "la tour";
            case BISHOP -> "le fou";
            case KNIGHT -> "le cavalier";
            case PAWN -> "le pion";
            default -> "la pièce";
        };
    }

    /**
     * Équipe une cible (joueur ou mob) via des commandes vanilla.
     * sel = UUID de l'entité. player = null pour un mob (le zombie) : il reçoit armure + arme, et la vie en plus s'il y a lieu.
     */
    public static void apply(BoardRenderer r, String sel, PieceType type, ServerPlayerEntity player) {
        Kit k = kit(type);
        slot(r, sel, "armor.head", k.head());
        slot(r, sel, "armor.chest", k.chest());
        slot(r, sel, "armor.legs", k.legs());
        slot(r, sel, "armor.feet", k.feet());
        slot(r, sel, "weapon.mainhand", k.weapon());

        int maxHealth = 20 + k.extraHealth();
        if (player == null) { // le zombie du mode solo
            if (k.extraHealth() > 0) {
                r.run("attribute " + sel + " minecraft:generic.max_health base set " + maxHealth);
                r.run("data modify entity " + sel + " Health set value " + maxHealth + "f");
            }
            return;
        }

        // Vie maximale (toujours définie explicitement pour repartir d'une base propre)
        r.run("attribute " + sel + " minecraft:generic.max_health base set " + maxHealth);

        slot(r, sel, "weapon.offhand", k.offhand());
        for (Give give : k.gives()) {
            player.getInventory().insertStack(new ItemStack(give.item(), give.count()));
        }
        for (String eff : k.effects()) {
            String[] parts = eff.split(":"); // effet, niveau, durée en secondes
            r.run("effect give " + sel + " minecraft:" + parts[0] + " " + parts[2] + " " + parts[1] + " true");
        }
    }

    private static void slot(BoardRenderer r, String sel, String slot, String item) {
        if (item != null) {
            r.run("item replace entity " + sel + " " + slot + " with " + item);
        }
    }
}
