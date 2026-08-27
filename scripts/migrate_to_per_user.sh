#!/usr/bin/env bash
# Réorganise les médias vers la structure par utilisateur attendue par NicoTV :
#   <WEB_ROOT>/media/Films   ->  <WEB_ROOT>/NicoTV/<USER>/Films
#   <WEB_ROOT>/media/Series  ->  <WEB_ROOT>/NicoTV/<USER>/Series
#
# Usage : ./migrate_to_per_user.sh [USER]   (USER = admin par défaut)
set -euo pipefail

# >>> À ADAPTER : racine locale du partage "Web" sur ton serveur <<<
WEB_ROOT="/var/www/html"

USER="${1:-admin}"
SRC="$WEB_ROOT/media"
DST="$WEB_ROOT/NicoTV/$USER"

echo "Source      : $SRC"
echo "Destination : $DST"
echo "Utilisateur : $USER"
echo

for sub in Films Series; do
  if [ -d "$SRC/$sub" ]; then
    mkdir -p "$DST/$sub"
    echo "→ Déplacement de $sub …"
    # Déplace tout le contenu (.strm, cache.json, etc.)
    cp -a "$SRC/$sub/." "$DST/$sub/"
    echo "  OK : $(ls -1 "$DST/$sub" | wc -l) éléments dans $DST/$sub"
  else
    echo "× $SRC/$sub introuvable, ignoré"
  fi
done

echo
echo "Terminé. Vérifie que les fichiers sont bien dans $DST puis supprime $SRC si tout est bon."
