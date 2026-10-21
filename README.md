# ZwiftBoard Android

Utiliser les **Zwift Click V2** pour piloter **MyWhoosh sur tablette ou téléphone Android** (prévu pour Xiaomi Pad 7 et Samsung Galaxy S24, Android 8 minimum), sans l'appli Zwift ni abonnement.

Chaque appui sur un bouton du Click simule un **appui tactile** à l'endroit de l'écran que vous avez choisi (par exemple sur les boutons de changement de vitesse de MyWhoosh).

Le protocole Bluetooth du Click V2 (handshake, keepalive, décodage des boutons, gestion des deux pods) est repris de [zwiftboard](https://github.com/thomaschampagne/zwiftboard) de Thomas Champagne (licence MIT).

## Pourquoi des appuis tactiles et pas des touches clavier ?

Sous Windows, zwiftboard envoie de vraies touches clavier. Android interdit à une appli d'envoyer des touches à une autre appli (sauf appareil rooté). La seule méthode autorisée est le **service d'accessibilité**, qui peut simuler des appuis sur l'écran. C'est aussi ce que fait BikeControl.

## Obtenir l'APK

Le dépôt se compile automatiquement avec GitHub Actions à chaque envoi sur `main` :

1. Onglet **Actions** du dépôt : attendre que « Compilation APK » soit vert (environ 3 min).
2. Onglet **Releases** (ou page d'accueil du dépôt, colonne de droite) : télécharger `zwiftboard-android.apk` depuis la tablette.
3. Ouvrir le fichier et autoriser l'installation depuis le navigateur ou le gestionnaire de fichiers.

Les versions suivantes s'installent par-dessus la précédente (même clé de signature) : les points placés sont conservés.

## Mise en route (une seule fois)

1. **Autoriser Bluetooth et notifications** (bouton 1 dans l'appli).
2. **Activer le service d'accessibilité** (bouton 2), puis activer « ZwiftBoard » dans la liste.
   - Si l'interrupteur est **grisé** (« paramètre restreint », Android 13 et plus pour les applis installées hors Play Store) : *Paramètres → Applis → ZwiftBoard → menu ⋮ en haut à droite → Autoriser les paramètres restreints*, puis recommencer.
3. **Ne pas limiter la batterie** (bouton 3).
   - **Xiaomi (HyperOS)** : *Infos de l'appli ZwiftBoard* → activer **Démarrage automatique** et régler **Économiseur de batterie** sur **Aucune restriction**. Sans ça, HyperOS coupe la connexion ou désactive le service d'accessibilité en cours de séance.
   - **Samsung** : *Paramètres → Batterie → Limites d'utilisation en arrière-plan* : ajouter ZwiftBoard aux **Applis jamais en veille**.

## Placer les points (une fois par appareil et par orientation)

1. Dans ZwiftBoard, toucher **Placer les points dans MyWhoosh** : MyWhoosh s'ouvre, puis l'écran de placement apparaît par-dessus.
   (Vous pouvez aussi ouvrir MyWhoosh vous-même et toucher la bulle **ZB**.)
2. Choisir le bouton à placer : toucher sa pastille **ou appuyer dessus sur le Click** (pratique pour vérifier que le Click est bien connecté).
3. Toucher l'écran à l'endroit voulu (par exemple le bouton « vitesse + » de MyWhoosh). Un rond portant le symbole du bouton apparaît.
4. Recommencer pour les autres boutons, puis **Terminer** (ou toucher à nouveau la bulle **ZB**).

Les points sont enregistrés en proportion de l'écran, séparément en **paysage** et en **portrait**.

## En séance

1. Fermer l'appli **Zwift / Zwift Companion** : chaque Click n'accepte qu'une seule connexion Bluetooth à la fois.
2. Ouvrir ZwiftBoard (la connexion démarre toute seule), puis MyWhoosh.
3. Appuyer sur un bouton de chaque Click pour les réveiller.

Couleur de la bulle **ZB** : vert = les 2 pods connectés, orange = 1 seul, rouge = aucun, gris = service arrêté. Elle clignote en bleu à chaque appui envoyé. On peut la déplacer en la faisant glisser, ou la masquer dans les options.

## Limites connues

- **Pod gauche (−, flèches) verrouillé** : il ne répond qu'après un passage par l'appli Zwift, qui le déverrouille pour environ 24 h. Le **pod droit (+, A, B, Y, Z) fonctionne sans déverrouillage**. Associez donc en priorité les boutons du pod droit.
- **Anti-veille** : un pod inactif se met en veille au bout d'environ 65 s. Comme zwiftboard, l'appli le redémarre volontairement après 55 s sans appui ; il se reconnecte en quelques secondes. Un appui fait pendant ces quelques secondes peut être perdu. Option désactivable.
- Si MyWhoosh change la disposition de ses boutons à l'écran, il faut replacer les points.
- Ne pas toucher l'écran au moment précis où vous appuyez sur le Click : Android annule alors l'appui simulé.

## Options

| Option | Effet |
|---|---|
| Bulle flottante « ZB » | Affiche la bulle (ouvre/ferme le placement, montre l'état). |
| Répéter l'appui tant que le bouton est maintenu | Après 0,45 s de maintien, répète l'appui toutes les 0,25 s. |
| N'agir que si MyWhoosh est au premier plan | Évite les appuis parasites dans une autre appli. |
| Anti-veille des pods | Redémarrage préventif après 55 s sans appui (voir ci-dessus). |
| Journal détaillé | Affiche les trames Bluetooth brutes (diagnostic). |

## Structure du code

| Fichier | Rôle |
|---|---|
| `Protocol.java` | UUID, trames, décodage protobuf du bitmap des boutons |
| `ButtonTracker.java` | Fronts d'appui, dédoublonnage des deux pods (testé, `app/src/test`) |
| `ClickService.java` | Service de premier plan : recherche, connexion, handshake, keepalive |
| `TapService.java` | Service d'accessibilité : appuis simulés, bulle flottante |
| `PlacementView.java` | Écran de placement des points |
| `MainActivity.java` | Écran principal |

Compilation locale : Android Studio (ouvrir le dossier), ou `gradle assembleRelease` avec le SDK Android installé.
