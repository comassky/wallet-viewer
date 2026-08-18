# Wallet Viewer

Visualiseur Bitcoin en lecture seule : backend Java 25 LTS / Quarkus, interface Vue 3 / TypeScript,
et récupération du solde, des transactions et des UTXO via Electrum.

## Développement et vérification

Prérequis : JDK 25 LTS et Maven 3.9+. Vérifier que `JAVA_HOME` pointe vers le JDK 25
et que `mvn -version` utilise bien cette version. Quarkus 3.39.2 et Quinoa 2.9.0 sont
utilisés pour la compatibilité Java 25. Quinoa installe automatiquement Node 22.22.0
et construit l’interface ; aucune installation globale de Node n’est nécessaire.

```sh
mvn quarkus:dev
mvn --batch-mode --no-transfer-progress verify -Dquarkus.quinoa.ci=true
```

La seconde commande exécute les tests Java, installe les dépendances de l’interface avec
`npm ci`, vérifie TypeScript puis construit Vue et l’application Quarkus.
Le verrou npm est versionné. Les tests utilisent des données de test et un serveur TCP local :
ni clé personnelle, ni serveur Electrum externe ne sont nécessaires au build.

## Configuration du portefeuille

Fournir `WALLET_XPUB` **au démarrage**, jamais pendant le build. Il doit s’agir d’une
clé publique étendue au niveau du compte, jamais d’une seed ou d’une clé privée.
Pour le développement, Quarkus accepte aussi `wallet.xpub=...` dans
`config/application.properties`, un fichier local ignoré par Git.

Pour Docker, copier [.env.example](.env.example) vers `.env`, puis renseigner les valeurs.
Ne pas versionner ce fichier. Les variables d’environnement sont prioritaires sur les
valeurs par défaut de [la configuration](src/main/resources/application.properties).

| Variable | Usage |
| --- | --- |
| `WALLET_XPUB` | Clé publique étendue du compte, obligatoire |
| `WALLET_NETWORK` | `mainnet` par défaut ; `testnet` pour un compte testnet |
| `WALLET_SCRIPT_TYPE` | `auto`, `p2pkh`, `p2sh-p2wpkh`, `p2wpkh` ou `p2tr` |
| `WALLET_GAP_LIMIT` | Nombre d’adresses consécutives inutilisées arrêtant le scan, défaut : 20 |
| `WALLET_MAX_ADDRESSES` | Maximum scanné **par chaîne** (réception/change), défaut : 200 |
| `WALLET_CACHE_TTL_SECONDS` | Durée du cache, défaut : 30 secondes ; 0 désactive le cache |
| `ELECTRUM_HOST` / `ELECTRUM_PORT` | Serveur Electrum joignable depuis l’application |
| `ELECTRUM_SSL` | Active TLS avec vérification du certificat et du nom d’hôte |
| `ELECTRUM_REQUEST_TIMEOUT` | Délai maximal **par requête RPC**, défaut : `30s` |

Pour Taproot/BIP86, définir explicitement `WALLET_SCRIPT_TYPE=p2tr` : une xpub ne permet
pas de distinguer BIP44 de BIP86. Le scan ne découvre pas les fonds situés au-delà du
gap-limit ou du maximum configuré ; ajuster ces limites à l’historique du portefeuille.
Les liens vers l’explorateur de transactions utilisent actuellement mempool.space mainnet.

## Image Docker

[Dockerfile](Dockerfile) est autonome et multi-stage : Maven compile et teste le backend,
Quinoa construit le frontend, puis seuls les artefacts Quarkus sont copiés dans une image JRE 25 LTS.
Le conteneur s’exécute sans privilèges root (`10001:10001`) et écoute sur le port 8080.

```sh
docker build -t wallet-viewer:local .
docker run --rm --name wallet-viewer --env-file .env -p 127.0.0.1:8080:8080 wallet-viewer:local
```

Ouvrir <http://localhost:8080>. Ne pas utiliser `localhost` comme hôte Electrum si le serveur
tourne hors du conteneur : employer son nom DNS accessible sur le réseau Docker, ou
`host.docker.internal` avec Docker Desktop pour un serveur sur la machine hôte.

Le contexte Docker utilise une liste d’autorisation : seuls le POM et les sources sont transmis.
Le cache Node local, les dépendances installées, les builds précédents et la configuration
personnelle sont exclus. Ne jamais placer de clé personnelle dans les sources ou un `ARG` Docker.

## Docker Compose

[compose.yaml](compose.yaml) permet de construire et de lancer l’application avec Docker Compose v2.
Copier [.env.example](.env.example) vers `.env`, renseigner `WALLET_XPUB`, puis lancer :

```sh
docker compose up -d --build
docker compose logs -f wallet-viewer
docker compose down
```

Compose charge automatiquement `.env` pour les variables du service et refuse de démarrer si
`WALLET_XPUB` est vide. La configuration locale Java n’est pas montée dans le conteneur.
L’interface est accessible sur <http://localhost:8080> ; `WALLET_VIEWER_PORT` permet de changer le port hôte.
Le conteneur redémarre automatiquement, utilise un système de fichiers en lecture seule avec un
répertoire temporaire en mémoire, et limite la taille des journaux. Aucun volume persistant n’est nécessaire.

Pour utiliser une image déjà publiée, définir `WALLET_VIEWER_IMAGE=ghcr.io/<propriétaire>/<dépôt>:latest`
dans `.env`, puis utiliser ces commandes **à la place du build local** :

```sh
docker compose pull
docker compose up -d --no-build
```

Une authentification préalable au registre est nécessaire si l’image est privée.

## GitHub Actions / GHCR

[Le workflow](.github/workflows/docker.yml) :

- **Pull request** : construit l’image et exécute les vérifications, sans connexion au registre ni publication.
- **Push sur `main`** : construit puis publie `ghcr.io/<propriétaire>/<dépôt>` avec les tags `main`, `latest` et `sha-…`.
- **Tag SemVer**, par exemple `v1.2.3` : publie notamment `1.2.3`, `1.2` et `sha-…`.
- **Déclenchement manuel** : construit la référence sélectionnée ; publication seulement sur `main` ou un tag `v*`.

L’architecture produite est `linux/amd64`. BuildKit réutilise le cache GitHub Actions.
Le build Docker exécute `mvn verify` : une erreur de test ou de compilation empêche la publication.
La publication utilise le `GITHUB_TOKEN` fourni automatiquement avec `packages: write` ;
aucun token personnel et aucune clé de portefeuille ne sont nécessaires dans les secrets GitHub.
L’organisation doit autoriser l’écriture dans GHCR. Pour un package déjà existant, vérifier que
le dépôt dispose des droits d’accès au package. Adapter le filtre `main` si la branche principale change.

```sh
docker pull ghcr.io/<propriétaire>/<dépôt>:latest
docker run --rm --env-file .env -p 127.0.0.1:8080:8080 ghcr.io/<propriétaire>/<dépôt>:latest
```

Un package privé nécessite une authentification au registre côté déploiement.
Le workflow publie l’image, mais ne déploie pas l’application.

## Sécurité et limites

- L’audit npm signale encore deux dépendances de développement vulnérables : Vite (élevée)
  et esbuild (modérée). Leur correction nécessite une migration majeure de Vite, non incluse ici.
  Ne pas exposer le serveur de développement. Ces outils ne sont pas embarqués dans l’image JRE finale.
- L’application n’intègre pas d’authentification : ne pas exposer directement son API sur Internet.
  Utiliser un réseau privé ou un reverse proxy avec authentification et HTTPS.
- Une xpub ne permet pas de dépenser les fonds, mais révèle l’historique et les adresses du compte.
  Le serveur Electrum interrogé peut également corréler ces adresses.
- L’exemple Docker utilise TLS ; la configuration historique de développement reste en TCP.
  Un serveur avec certificat autosigné doit être approuvé via un truststore JVM ; la vérification
  TLS n’est plus désactivée silencieusement.
- Une clé précédemment versionnée reste accessible dans l’historique Git : la retirer du fichier
  courant ne nettoie pas cet historique.
- `.quinoa/` est un cache généré. Si ses fichiers étaient déjà suivis par Git, l’ajout au fichier
  d’exclusion ne les désindexe pas : les retirer de l’index lors d’un nettoyage dédié.

## Nettoyages et couverture de régression

- Reconstruction des accumulateurs à chaque expiration du cache et respect exact du maximum d’adresses.
- Adresse de réception située après la dernière adresse utilisée ; QR lié à l’index affiché.
- Identifiant RPC et connexion résolus à chaque souscription, timeout, nettoyage après annulation,
  fermeture du client TCP et vérification TLS.
- Indices négatifs rejetés avec HTTP 400.
- Formateur BTC réutilisé, délai maximal des requêtes HTTP, temporisateurs nettoyés, erreurs de copie
  visibles et tableaux défilables sur petit écran.
- Tests des vecteurs officiels BIP86 via la classe de production, du cache et du scan,
  des erreurs REST et du protocole Electrum sur serveur local (timeout, annulation, reconnexion, erreurs).