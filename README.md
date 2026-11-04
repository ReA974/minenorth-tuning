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
| Pont | Court / Long | Accélération +15 % / -12 %, vitesse de pointe -13 % / +14 % (rapport de pont du véhicule) |
| Pneus | Sport / Course | Adhérence de traction de TOUTES les roues +10 % / +20 %, tenue latérale +15 % / +30 % |
| Freins | Sport / Course | Freinage +60 % / +130 % |

Chaque réglage part des valeurs d'usine (aucun cumul en le refaisant) ; un pneu crevé n'est pas modifié.

## Compiler
Java 17. Builder d'abord `minenorth-api` puis `minenorth-entreprises` (jars lus dans leurs `build/libs`), puis `./gradlew build`.

## Diagnostic
**/mnbanc debug** (ou /banc debug ; /mnbanc existe car un plugin Bukkit peut déjà posséder /banc sur Arclight ; OP, à moins de 8 blocs d'un véhicule MTS) : affiche le réglage et, pour les freins, le pont, le moteur et les roues motrices, la valeur d'usine et la valeur réellement en vigueur dans MTS. Sert à vérifier qu'un réglage est bien pris en compte (par ex. « Freins : usine 1.000 -> actuel 1.600 »).

**/mnbanc apply** (OP) : force l'application du réglage mémorisé sur le véhicule le plus proche et affiche le résultat de chaque étape (puissance, régime, limiteur, usure, suralimentation, roues, pont/freins) avec les éventuels échecs ou écarts de valeur ; à lancer puis suivre de **/mnbanc debug** pour voir les valeurs réellement en vigueur.

## Nitro (15/10)
- **Kit au banc** (onglet **Nitro**) : Nitro I / II / III, à facturer au client comme les autres réglages. Pendant l'injection : puissance +40 / +80 / +130 % et adhérence des roues motrices +8 / +16 / +25 %. Flamme des échappements : **orange** (I), **bleue** (II), **violette** (III) (particules courtes + étincelles de feu d'artifice).
- **Injection** : maintenir la touche **Maj gauche** (réassignable : Contrôles > MineNorth > « Injection de nitro ») au volant d'un véhicule équipé. Elle consomme la réserve (1 s de réserve = 1 s d'injection) ; le message d'action affiche le temps restant.
- **Réserve** : 15 s par **bouteille de nitro** (craft : poudre de blaze + 5 lingots de fer + redstone = 2 bouteilles), 90 s au maximum par véhicule. Recharge : clic droit sur le véhicule avec une bouteille, ou **chariot de nitro** (craft : coffre + 3 lingots de fer... + 2 wagonnets) : clic droit = inventaire de 9 bouteilles, **clic droit accroupi** = recharge le véhicule le plus proche (6 blocs) avec les bouteilles du chariot.
- Les sons (wastegate, compresseur) viendront plus tard.
## Mise à jour du 15/10 (soir)
- **Turbo et compresseur supprimés** (onglet Suralim. retiré ; l'ancien réglage mémorisé sur un véhicule est ignoré).
- **Réglage synchronisé avec les clients** : MTS simule aussi la voiture chez le joueur, avec les valeurs d'usine ; le serveur appliquait les valeurs réglées, d'où une voiture qui saccade ou se téléporte. Le serveur envoie maintenant le réglage (et l'état de la nitro) aux joueurs qui voient le véhicule, qui l'appliquent à leur copie. Le serveur ET les clients doivent avoir le même jar (protocole 4).
- **Flammes de nitro** : placées d'après les boîtes de collision du véhicule (arrière réel et largeur), plus de position fixe.
- **Son de la nitro (fait)** : fichier fourni par le propriétaire, converti en OGG mono (11,8 s, extrait d'un effet « nitrous burst »), joué au déclenchement puis relancé toutes les 11,5 s tant que l'injection continue. Pour le changer : remplacer `nitro.ogg` (OGG mono). Événement `minenorthbanc:nitro_boost` est prêt ; déposer un fichier **OGG mono** dans `src/main/resources/assets/minenorthbanc/sounds/nitro.ogg` (puis recompiler). Tant qu'il manque, seuls les sons de secours (lancement + détonations) jouent.
## Correctifs du 15/10 (nuit)
- **Stages = puissance seule** : plus de hausse du régime maximal ni de l'adhérence des roues (la voiture devenait bizarre) ; le stage ne change que la puissance (x1,5 / x2,2 / x3,2) et l'usure du moteur. L'adhérence se règle uniquement avec l'option **Pneus**.
- **Nitro = poussée directe** : MTS plafonne la force motrice à masse x adhérence, une simple hausse de puissance ne se ressentait pas. Pendant l'injection le véhicule reçoit en plus une poussée vers l'avant à chaque tick (I / II / III : +8 / +14 / +22 millièmes de bloc par tick, soit environ +11 / +20 / +32 km/h gagnés chaque seconde en plus de l'accélération normale ; plafond ~216 km/h), calculée identiquement sur le serveur et chez le joueur. La puissance du moteur monte aussi (+40 / +80 / +130 %).
- **Tester la nitro sans la touche** (OP, assis dans le véhicule) : **/mnbanc nitro fill** (réserve pleine), **/mnbanc nitro on** (injection : son, poussée, flammes), **/mnbanc nitro off**. Le message d'action confirme « Nitro I/II/III activée » ; le log serveur note chaque appui de touche (`[Banc] Touche nitro ENFONCEE`). Si `on` marche mais pas la touche Maj, c'est la touche (conflit) : la réassigner dans Contrôles.
## Correctifs du 15/10 (fin de nuit)
- **Plantage client corrigé** : `NitroKeys` envoyait un paquet alors que la connexion venait de se fermer (`mc.getConnection()` nul : déconnexion, kick, véhicule disparu) -> NullPointerException dans le crash-report. Plus aucun envoi sans connexion.
- **Stages = poussée directe, plus de hausse de puissance** : plus de puissance que d'adhérence fait PATINER les roues dans MTS (la force motrice est plafonnée à masse x adhérence), ce qui rendait les voitures plus lentes, « collées au sol ». Le stage ajoute maintenant une poussée vers l'avant tant que le conducteur accélère (moteur en marche, sans frein ni frein à main, pas en marche arrière) : Stage 1 / 2 / 3 = +6 / +12 / +20 km/h par seconde plein gaz, plafond de vitesse 187 km/h ; serveur ET client (la voiture est simulée aux deux endroits). Le stage ne modifie plus que l'usure du moteur.
- **Nitro** : poussée seule (plus de hausse de puissance, qui faisait patiner).
- L'ancien repli par variables est supprimé (il envoyait des paquets de synchronisation depuis le client).
## Néons sous caisse (29/10)
Personnalisation vendue par l'entreprise de modification automobile, depuis le **banc dynamométrique** (onglet **Néons** de son écran, à côté de Nitro).
- **Pose** : personnel de l'entreprise du banc (mêmes contrôles que les réglages : à pied, véhicule à moins de 12 blocs du banc). Consomme un **kit néons**
  (`minenorthbanc:kit_neons`, craft : 3 vitres, 2 poudres de glowstone, 1 redstone, 3 pépites de fer) dans l'inventaire de l'employé. Changer la couleur ou le mode
  de néons déjà posés est gratuit ; **Retirer** rend le kit. Le client est facturé avec les factures du mod Entreprises (aucun paiement ici).
- **Réglages** : couleur libre (nuancier de 12 couleurs + barre de teinte), 4 modes : **Fixe**, **Respiration** (pulsation lente), **Arc-en-ciel** (couleurs qui
  défilent autour de la voiture), **Chenillard** (point lumineux qui fait le tour). Tant que l'écran est ouvert, le véhicule affiche les néons choisis **en direct**
  (aperçu local, rien n'est enregistré avant « Poser » / « Appliquer »).
- **Commande** : le **conducteur** (siège conducteur MTS) allume / éteint avec la touche **J** (voir les correctifs ci-dessous),
  réassignable dans Contrôles > MineNorth > « Néons sous caisse ». Les passagers ne peuvent pas.
- **Rangement** : couleur, mode et état allumé dans les données persistantes du véhicule (clé `mnrp_neons`), donc dans son NBT : ils suivent le véhicule au
  garage, à la fourrière et à la vente. Après une destruction, l'assurance rend l'état de la dernière sortie du garage (néons compris s'ils étaient déjà posés).
- **Rendu** : chez chaque joueur, d'après l'état envoyé par le serveur aux joueurs qui voient le véhicule (au moment où il apparaît, puis à chaque changement ;
  revérifié chaque seconde car le garage restaure les données après l'apparition). Dessiné dans la passe du monde (`RenderLevelStageEvent`, après les blocs
  translucides) avec le type de rendu de la foudre (additif, pleine luminosité) : **tubes** le long du bas de caisse (suivent l'inclinaison), lueur autour des tubes
  et **halo coloré sur le sol** (projeté sous la voiture, s'estompe vers l'extérieur et quand la voiture est en l'air). La forme vient du modèle 3D du véhicule (voir les correctifs ci-dessous). Indépendant du rendu des entités MTS, donc compatible Embeddium ; avec Oculus, à vérifier selon le pack de shaders.
- Protocole réseau passé à **6** (puis **7** avec les correctifs) : serveur et joueurs doivent avoir le même jar.

### Idées étudiées, non faites
- **Vitres teintées, couleur de jantes, kit carrosserie** : il faudrait connaître la géométrie des vitres / jantes / carrosserie de chaque modèle (elle est dans les
  modèles OBJ des packs) : impossible proprement sans modifier les packs. Les **jantes** sont des pièces MTS : en changer reste possible en posant une autre
  roue d'un pack (fonctionnement normal de MTS).
- **Klaxon / échappement personnalisés** : les sons des véhicules sont joués par le moteur audio de MTS, sans point d'accroche pour les remplacer par véhicule ; on
  ne pourrait qu'ajouter un son par-dessus celui du pack. Pas fait.
- **Peinture** : existe déjà (cabine de peinture du mod Entreprises, variantes du pack). **Stage moteur, nitro, pneus, freins, pont** : déjà au banc.

### Correctifs néons (29/10, soir)
- **Taille et position** : les boîtes de collision ne décrivent pas la carrosserie (les voitures New Cars n'en ont que deux, sur le toit, marquées
  intérieures) : les néons prenaient une taille de repli trop petite (~3,7 × 1,8 m au lieu de ~5,5 × 2,2 m). La forme est maintenant lue chez le joueur
  dans le **modèle 3D du véhicule** (le fichier OBJ que MTS affiche, via `getModelLocation`) : bas du modèle (1er centile des hauteurs, pour ignorer
  un échappement qui pend) et emprise des sommets dans les 35 cm au-dessus = contour des bas de caisse et pare-chocs, sans les rétroviseurs. Lu une fois
  par modèle, en tâche de fond. Repli : d'après les roues (position monde ramenée dans le repère du véhicule), puis une voiture moyenne. Les tubes
  sont posés 5 cm sous ce bas de caisse, en retrait de 6 cm sur les côtés et 15 cm à l'avant / arrière. Vérifié hors jeu sur les modèles des 4 packs
  (citadines, SUV, sportives, motos, bus).
- **Réglage par modèle** : onglet Néons du banc, section **Forme** : largeur (des deux côtés), avant, arrière, hauteur, par pas de 5 cm (± 1,5 bloc),
  aperçu en direct ; **Enregistrer la forme** vaut pour tous les exemplaires du modèle (`pack:modele`, toutes peintures), renvoyé aux joueurs
  aussitôt ; **Forme auto (0)** remet à zéro. Fichier `config/minenorth-neons.json` (créé au démarrage, vide = forme automatique, éditable à la main).
- **Touche** : la touche O de la première version est celle d'**Oculus** pour l'écran des packs de shaders ; Minecraft ne donne un appui qu'à une
  seule commande, les néons ne recevaient rien. Nouvelle touche par défaut **J** (libre dans MTS, Oculus et nos mods), sous un nouvel identifiant
  (`key.minenorthbanc.neons_caisse`) pour que le défaut s'applique aussi à ceux qui avaient O enregistré. La touche est lue sur l'événement clavier
  brut : même en conflit avec une autre commande, les néons reçoivent l'appui. Le serveur cherche le véhicule dont un siège porte le joueur (plus le
  véhicule le plus proche) et vérifie le siège conducteur ; chaque appui est noté dans le log serveur (`[Banc] Touche neons : ...`).
- **Commandes (OP)** : `/mnbanc neons info` (véhicule le plus proche : état, modèle, forme, chemin du modèle 3D, conducteur ou non),
  `/mnbanc neons basculer` (comme la touche, assis dans le véhicule), `/mnbanc neons recharger` (relit le fichier de config et renvoie la forme aux joueurs).
- Protocole réseau **7**.

## Néons : écran de réglage de la taille et aperçu 3D (04/11)
- L'onglet Néons s'élargit : à droite, un **aperçu 3D** qui tourne tout seul (ou à la souris, en glissant) : sol quadrillé de 1 m, contour
  du bas de caisse lu dans le modèle 3D du véhicule, silhouette de la caisse, flèche AVANT, et le cadre des néons dans la couleur choisie
  avec son halo au sol. Sous l'aperçu : **dimensions réelles** des néons (longueur x largeur) et hauteur sous caisse.
- Réglage de la taille (largeur, avant, arrière, hauteur, pour tout le modèle) : clic = 5 cm, **Maj + clic = 25 cm**, **molette** sur la case.
  Le calcul du cadre est le même pour le rendu dans le monde et l'aperçu (`ClientNeons.rect`).
