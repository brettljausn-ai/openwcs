// French (Français) - slotting screen (pick faces + block slotting).
export default {
  pickFacesHeading: 'Emplacements de prélèvement (rangement manuel · min/max)',
  pickFacesIntro:
    "Affectez un article + une unité de mesure à un emplacement de prélèvement fixe. Le réapprovisionnement le remplit jusqu'au max ; les réceptions peuvent y aller directement quand le prélèvement direct est activé.",
  colLocation: 'Emplacement',
  tipLocation:
    "L'emplacement de prélèvement fixe (façade de rayonnage/bac) auquel cet article est affecté. Les préparateurs y vont toujours pour cet article.",
  colSku: 'Article',
  tipSku: "L'article en stock affecté à cette façade de prélèvement.",
  colUom: 'UM',
  tipUom: "Unité de mesure prélevée sur cette façade ; la quantité prélevée est comptée dans ces unités.",
  colMin: 'Min',
  tipMin: "Déclencheur de réapprovisionnement : quand le stock disponible à la façade atteint ce seuil ou descend en dessous, une tâche de réapprovisionnement est créée.",
  colMax: 'Max',
  tipMax: 'Niveau de remplissage cible. Le réapprovisionnement reconstitue la façade jusqu’à cette quantité.',
  colDirect: 'Direct',
  tipDirect:
    "Quand activé, le stock entrant de cet article peut être rangé directement à la façade de prélèvement plutôt qu'en stockage de réserve.",
  phLocation: 'emplacement',
  phSku: 'article',
  phUom: 'um',
  phMin: 'min',
  phMax: 'max',
  directToPick: 'prélèvement direct',
  addPickFace: 'Ajouter une façade de prélèvement',
  blockSlottingHeading: 'Rangement par bloc (ASRS / AutoStore / AMR-GTP automatisés)',
  blockSlottingIntro:
    "Affectez un article à un bloc de stockage (l'ensemble du pool, toutes les allées). Le moteur de rangement choisit l'emplacement réel par unité logistique, en équilibrant la vélocité vers la sortie, la consolidation du même article, la redondance d'allées et l'équilibre de remplissage.",
  tipBlockSku:
    "L'article affecté à un bloc de stockage automatisé (pool ASRS / AutoStore / AMR-GTP).",
  colBlock: 'Bloc',
  tipBlock:
    "Le bloc de stockage (pool entier, toutes les allées) où cet article peut être stocké. Le moteur de rangement choisit l'emplacement exact par unité logistique.",
  colVelocity: 'Vélocité',
  tipVelocity:
    'Classe de rotation déterminant la proximité de stockage par rapport à la sortie/au prélèvement. A = rotation rapide, C = rotation lente.',
  colConsolidate: 'Consolider',
  tipConsolidate:
    "Quand activé, le moteur préfère regrouper le même article (emplacements moins nombreux et plus denses) plutôt que de le disperser.",
  colMinAisles: 'Allées min.',
  tipMinAisles:
    "Nombre minimal d'allées distinctes sur lesquelles cet article doit être réparti, pour la redondance si une allée est hors service.",
  colMaxAislePct: '% allée max.',
  tipMaxAislePct:
    "Plafond de la part d'une allée qu'un seul article peut occuper, pour équilibrer les allées (0 à 1).",
  phBlock: 'bloc…',
  velocityClass: 'Classe de vélocité',
  consolidate: 'consolider',
  phMinAisles: 'allées min.',
  phMaxAislePct: '% allée max.',
  addBlockSlotting: 'Ajouter un rangement par bloc',
}
