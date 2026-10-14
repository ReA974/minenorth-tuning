# MineNorth Banc de puissance

Mod Forge 1.20.1 (`minenorthbanc`). Dépend de `minenorth-entreprises` (≥ 1.1.0) et d'Immersive Vehicles (par réflexion, 24.0.0).

Bloc `banc_puissance` (onglet créatif MineNorth, recette fer / piston / diamant / redstone) pour une entreprise de modification automobile.
Comme la cabine de peinture du mod Entreprises, il est utilisé par le **personnel** de l'entreprise (patron, gérants et employés) sur le véhicule d'un
client, qui est ensuite facturé par le système de factures de l'entreprise (aucun paiement dans ce mod).
- **Pose et casse** : patron / gérants d'une entreprise active (et OP) ; le banc est rattaché à leur entreprise.
- **Utilisation** : véhicule garé à moins de 6 blocs, descendu du véhicule, clic droit sur le banc : diagnostic (indice de puissance, régime maximal,
  nombre de rapports) et niveaux de reprogrammation.

| Niveau | Puissance | Régime max | Usure du moteur |
|---|---|---|---|
| Origine | 0 % | 0 % | 0 % |
| Stage 1 | +10 % | +2 % | +30 % |
| Stage 2 | +22 % | +4 % | +70 % |
| Stage 3 | +38 % | +7 % | +130 % |

Dans MTS la puissance d'un moteur est son coefficient « fuelConsumption » : plus de puissance = plus de carburant consommé. Le niveau est gardé sur le
véhicule (donc dans son NBT, même rangé au garage) et réappliqué toutes les 5 s aux véhicules chargés (MTS remet les valeurs d'usine au chargement).
Si un moteur n'accepte pas le réglage, le banc répond « moteur non pris en charge » sans rien modifier.

## Banc dynamométrique (6 × 4)
Objet `banc_dyno` (fer en blocs / pistons / diamant / bloc de redstone) : pose d'un coup une plateforme basse bleue de **4 de large (à droite du joueur) sur 6 de long (devant lui)**
avec quatre rouleaux sous les roues, comme un vrai banc. Il faut 32 cases libres et être patron / gérant d'une entreprise active. On roule dessus ; clic droit sur
n'importe quelle case = diagnostic et reprogrammation (même service que le banc de puissance, véhicule jusqu'à 12 blocs). Casser une case retire tout le banc.
Niveaux renforcés : Stage 1 / 2 / 3 = +50 % / +120 % / +220 % de puissance (x1,5 / x2,2 / x3,2 ; calé sur l'écart d'un échange de moteur : le Hemi 6.2 Hellcat de GVP vaut 6,2, le moteur médian de GVP 2,4), +3 / +6 / +10 % de régime max. Adhérence des roues motrices +15 / +30 / +50 % (MTS plafonne la force motrice à masse × friction : sans grip supplémentaire, la puissance seule ne change rien au départ). L'ancien bloc `banc_puissance` (1 bloc) reste enregistré pour les bancs déjà posés
mais n'est plus dans l'onglet créatif.

## Options (écran du banc, onglets)
Indépendantes des stages et cumulables ; rangées sur le véhicule comme le stage, réappliquées toutes les 5 s.

| Onglet | Choix | Effet |
|---|---|---|
| Suralim. | Compresseur / Turbo | +35 % / +65 % de la puissance d'usine, usure +15 % / +50 %, consommation en hausse (refusé si le moteur est déjà suralimenté d'usine) |
| Pont | Court / Long | Accélération +15 % / -12 %, vitesse de pointe -13 % / +14 % (rapport de pont du véhicule) |
| Pneus | Sport / Course | Adhérence de traction de TOUTES les roues +10 % / +20 %, tenue latérale +15 % / +30 % |
| Freins | Sport / Course | Freinage +60 % / +130 % |

Chaque réglage part des valeurs d'usine (aucun cumul en le refaisant) ; un pneu crevé n'est pas modifié.

## Compiler
Java 17. Builder d'abord `minenorth-api` puis `minenorth-entreprises` (jars lus dans leurs `build/libs`), puis `./gradlew build`.

## Diagnostic
**/mnbanc debug** (ou /banc debug ; /mnbanc existe car un plugin Bukkit peut déjà posséder /banc sur Arclight ; OP, à moins de 8 blocs d'un véhicule MTS) : affiche le réglage et, pour les freins, le pont, le moteur et les roues motrices, la valeur d'usine et la valeur réellement en vigueur dans MTS. Sert à vérifier qu'un réglage est bien pris en compte (par ex. « Freins : usine 1.000 -> actuel 1.600 »).
