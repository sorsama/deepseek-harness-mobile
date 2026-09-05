<p align="center">
  <img src="docs/images/banner.jpg" alt="DSH Mobile — le DeepSeek Harness dans votre poche" width="100%">
</p>

<h1 align="center">DSH Mobile — Télécommande du DeepSeek Harness</h1>

<p align="center">
  Une application compagnon Android open source qui met votre <b>DeepSeek Harness</b> dans votre
  poche.<br>
  Pilotez les sessions, consultez les plans et les objectifs, répondez aux approbations et aux
  questions, et soyez averti quand le harness a fini — depuis votre téléphone, sur votre réseau
  local.
</p>

<p align="center">
  <a href="https://dshm.zyphite.com"><img alt="Website" src="https://img.shields.io/badge/website-dshm.zyphite.com-4176E6?style=flat-square"></a>
  <a href="https://github.com/sorsama/deepseek-harness-mobile/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/sorsama/deepseek-harness-mobile?style=flat-square"></a>
  <a href="https://github.com/sorsama/deepseek-harness-mobile/actions/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/sorsama/deepseek-harness-mobile/ci.yml?branch=main&style=flat-square"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square">
  <a href="LICENSE"><img alt="MIT" src="https://img.shields.io/badge/license-MIT-blue?style=flat-square"></a>
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.zh-CN.md">中文</a> ·
  <a href="README.hi.md">हिन्दी</a> ·
  <a href="README.es.md">Español</a> ·
  <b>Français</b> ·
  <a href="README.th.md">ไทย</a>
</p>

DSH Mobile est une **application compagnon non officielle** du
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (MIT) : elle reprend son
interface web fonction par fonction, dans le langage visuel du harness lui-même. Android
uniquement, Kotlin + Jetpack Compose.

À l'autre bout, son compagnon est
[**dsh-relay**](https://github.com/sorsama/deepseek-harness-relay) — un plugin du harness qui
ajoute la couche d'authentification que le harness reconnaît lui-même ne pas avoir, pour que cette
application atteigne un harness avec un vrai identifiant et une clé épinglée plutôt qu'un port
ouvert. Voir [Relay](https://github.com/sorsama/deepseek-harness-mobile/wiki/Relay).

**[dshm.zyphite.com](https://dshm.zyphite.com)** est le site du projet — ce qu'est l'application,
à quoi elle ressemble et comment la faire tourner, sur une seule page.

Le [**wiki**](https://github.com/sorsama/deepseek-harness-mobile/wiki) est le guide destiné aux
utilisateurs :
[premiers pas](https://github.com/sorsama/deepseek-harness-mobile/wiki/Getting-Started),
[connexion](https://github.com/sorsama/deepseek-harness-mobile/wiki/Connecting),
[dépannage](https://github.com/sorsama/deepseek-harness-mobile/wiki/Troubleshooting),
une [visite des fonctionnalités](https://github.com/sorsama/deepseek-harness-mobile/wiki/Feature-Tour)
et une [FAQ](https://github.com/sorsama/deepseek-harness-mobile/wiki/FAQ).

---

## Captures d'écran

| Connexion | Discussion | Trajectoire |
|:--:|:--:|:--:|
| <img src="docs/images/home.png" width="240" alt="Écran de connexion : harnesses récents avec accessibilité en direct, découverte, saisie manuelle et bascules de connexion automatique"> | <img src="docs/images/chat.png" width="240" alt="Discussion : tours diffusés en continu avec une icône par outil, cartes d'outil, dock d'objectif et zone de saisie"> | <img src="docs/images/trajectory.png" width="240" alt="Trajectoire : un journal tour par tour avec les totaux d'utilisation"> |
| Harnesses récents avec accessibilité en direct, découverte sur le LAN, `host:port` manuel, connexion automatique. | Tours diffusés en continu, un glyphe par outil, cartes d'outil dépliables, sélecteur de permissions. | La même session sous forme de journal tour par tour, avec les totaux d'utilisation. |

| Détails de la session | Sous-agents |
|:--:|:--:|
| <img src="docs/images/session-info.png" width="240" alt="Panneau de détails : répartition du contexte, objectif, mode plan, tâches, file d'attente, sous-agents, informations sur l'hôte"> | <img src="docs/images/subagent.png" width="240" alt="Catalogue des sous-agents, avec des enfants que l'on peut relancer"> |
| Répartition du contexte, objectif, mode plan, tâches en arrière-plan, tours en file d'attente, informations sur l'hôte, export du journal de session. | Le catalogue des sous-agents — ouvrez la transcription d'un enfant, relancez-le ou interrompez-le. |

## Fonctionnalités

- **Connexion sans effort** — découverte automatique d'un harness sur votre Wi-Fi (balayage actif
  du sous-réseau + handshake de disponibilité), mémorisation des hôtes et test de leur
  disponibilité à l'ouverture, saisie manuelle de `host:port`, loopback pour une configuration sur
  le même appareil, et bascules de connexion automatique (dernier utilisé / LAN / même appareil).
- **Navigation façon Discord** — balayez vers la droite depuis le bord gauche pour ouvrir la liste
  des discussions groupée par espace de travail, vers la gauche pour la fermer, et depuis le bord
  droit vers la gauche pour le panneau de détails de la session.
- **Expérience de discussion complète** — tours diffusés en continu avec raisonnement dépliable,
  markdown, cartes d'outil terminal/diff/lecture/recherche/web, dock de file d'attente (modifier /
  retirer / réorienter), pagination de l'historique, pièces jointes images et fichiers.
- **Commandes slash et compétences** — la zone de saisie confronte une ligne commençant par `/` au
  catalogue de commandes propre à la session et l'exécute via la passerelle de commandes du
  harness ; ce que le catalogue ne revendique pas part comme un prompt, et c'est ainsi que les
  compétences sont invoquées.
- **Tout ce que fait l'interface web** — objectifs (phases, tours, pause/reprise/édition), mode
  plan et revue de plan, approbations de permissions, questions à l'utilisateur, dock de tâches,
  sous-agents (catalogue, relances, interruption), tâches en arrière-plan, exécutions de flux de
  travail, compétences, choix du modèle, préréglages d'agent, recherche dans la session, journal de
  trajectoire, export de session, retours sur les messages.
- **Notifications** — tour terminé, objectif atteint / bloqué, une revue ou une question qui vous
  attend ; connexion en arrière-plan via un service de premier plan.
- **L'allure du harness** — exactement les mêmes jetons de design que le DeepSeek Harness
  (couleurs, typographie, rayons, lignes dépliables, shimmer, boutons encre) avec les thèmes clair
  / sombre / système.
- **11 langues** — English, 中文, हिन्दी, Español, Français, العربية, বাংলা, Português, Русский,
  اردو, ไทย (prise en charge du RTL).

## Prérequis

- Android 8.0 ou plus (minSdk 26).
- Un [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) en cours d'exécution
  (testé avec `0.1.3-alpha.1`). **0.10.0 requiert le harness 0.1.3** — cette
  version a cessé de journaliser les fragments d’une réponse pour les envoyer dans un
  flux en direct que l’app doit demander, donc l’app et le harness doivent évoluer
  ensemble : une app plus ancienne ne voit jamais une réponse s’écrire sur 0.1.3, et
  cette app ne peut pas exécuter de commandes slash sur 0.1.2.
  Voir [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

## Démarrage rapide

1. Installez le dernier APK depuis les
   [Releases](https://github.com/sorsama/deepseek-harness-mobile/releases/latest).
2. Ouvrez l'application et choisissez le mode de connexion. Ce ne sont pas des variantes d'un même
   réglage — prenez celui qui correspond à ce que vous avez mis en place sur l'ordinateur.

   **Relais** — chiffré, authentifié, et utilisable même hors de votre Wi-Fi. Installez
   [`dsh-relay`](https://github.com/sorsama/deepseek-harness-relay) dans le profil web du harness :

   ```sh
   dsh plugin --profile web add dsh-relay
   dsh web
   ```

   Ouvrez l'URL affichée **sur cet ordinateur**, définissez un mot de passe, puis ouvrez
   `/relay/pair`. Dans l'application : **Relais → Appairer un relais**, puis scannez le QR code.
   Une fois tous vos clients appairés, désactivez `compat.addressGrants` sur le relais — rien ici
   n'en a besoin.

   **Réseau local** — aucune configuration sur le téléphone, et aucune authentification. Appliquez
   le correctif LAN en un seul fichier de [`harness/README.md`](harness/README.md), redémarrez
   `dsh web`, puis touchez **Analyser le réseau**. Uniquement sur des réseaux de confiance.

   **Derrière votre propre reverse proxy HTTPS** — collez l'adresse `https://` dans le mode réseau
   local. Le proxy peut rediriger vers le loopback, le harness n'a donc pas besoin de correctif ;
   mais il chiffre la liaison sans authentifier qui que ce soit. Voir
   [`harness/README.md`](harness/README.md).

   **USB / émulateur** — `dsh web`, puis `adb reverse tcp:3080 tcp:3080`, et connectez-vous à
   `127.0.0.1:3080` en mode réseau local. Aucun correctif nécessaire.
3. Choisissez une session, discutez, et soyez averti quand le harness a terminé.

Si une tentative de connexion échoue, l'application en nomme la cause ; la page
[dépannage](https://github.com/sorsama/deepseek-harness-mobile/wiki/Troubleshooting) du wiki est
organisée autour de cette phrase exacte.

## Compatibilité et sécurité

> **0.1.2:** Depuis le harness 0.1.2, le harness authentifie toute son API : collez une fois le lien qu’il affiche au démarrage lorsque l’app le demande. Cela authentifie le téléphone mais ne chiffre pas la connexion — réservez-le donc aux réseaux de confiance.

- Voir [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) pour la matrice des versions du harness et
  les surfaces accessibles uniquement en loopback.
- **Lisez d'abord [docs/SECURITY.md](docs/SECURITY.md).** Le harness nu n'a aucune
  authentification : le mode réseau local est donc réservé aux réseaux de confiance — l'application
  le rappelle sur l'écran de connexion, pour la même raison. Le mode relais ajoute un véritable
  identifiant et un certificat épinglé, mais s'authentifier donne toujours autant de pouvoir qu'un
  shell sur cet ordinateur, puisque c'est là que l'agent exécute les commandes.

## Compilation

```sh
./gradlew :app:assembleDebug      # APK de débogage
./gradlew :app:assembleRelease    # APK de release (signé si l'environnement du keystore est défini)
```

La version publiée provient du tag git : le workflow de release exporte `DSH_VERSION_NAME` depuis
le nom du tag, et `versionCode` en est dérivé. Une compilation locale retombe sur la valeur écrite
dans `app/build.gradle.kts`.

Voir [CONTRIBUTING.md](CONTRIBUTING.md) pour la boucle de développement face à un vrai harness,
l'organisation des modules et le workflow de release.

## Dépôt

| Chemin | Contenu |
|---|---|
| `core/` | Cœur de protocole en JVM pur : DTO du protocole, client RPC, liaisons descendantes WebSocket, boucle de reconnexion, pliage de session, classifieur de notifications |
| `app/` | Interface Android : écrans, découverte/connexion, service de premier plan, notifications, i18n |
| `mock-harness/` | Simulacre Ktor du serveur `/api` du harness pour les tests |
| `tools/capture/` | Enregistre le trafic d'un vrai harness sous forme de fixtures de conformité |
| `harness/` | Correctif compagnon et guide pour le mode LAN |
| — | Le relais lui-même vit dans [sorsama/deepseek-harness-relay](https://github.com/sorsama/deepseek-harness-relay) |
| `docs/` | [Architecture](docs/ARCHITECTURE.md), [notes de protocole](docs/PROTOCOL.md), [compatibilité](docs/COMPATIBILITY.md), [sécurité](docs/SECURITY.md) |

## Licence

[MIT](LICENSE). Les éléments tiers embarqués sont listés dans
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Le DeepSeek Harness et sa marque appartiennent à
leurs propriétaires respectifs ; ce projet est une télécommande indépendante, construite par la
communauté.
