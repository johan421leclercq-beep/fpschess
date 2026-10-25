# FPS Chess (Fabric 1.21.1, côté serveur)

## Compiler
Prérequis : Java 21. Puis : `./gradlew build` (ou `gradle build` si le wrapper n'est pas présent).
Le jar est dans `build/libs/fpschess-0.1.0.jar`.

## Tester (étape 2)
Copier le jar + Fabric API dans `mods/` d'un serveur Fabric 1.21.1, puis en jeu (OP) :
- `/fpschess new`
- `/fpschess move e2e4`
- `/fpschess show`
