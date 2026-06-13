// French (Français): GTP workplace configuration (admin). Keys mirror useT('gtpconfig', ...) calls.
export default {
  eyebrow: 'Configuration',
  title: 'Postes GTP',
  intro:
    'Configurez les postes goods-to-person (stations) : leur topologie de destination, les modes de fonctionnement pris en charge et leurs nœuds STOCK / ORDER. Les postes sont rattachés à l’entrepôt sélectionné.',
  warehouse: 'Entrepôt',
  warehouseTip:
    'L’entrepôt dont vous configurez les postes GTP. Tous les postes et nœuds ci-dessous sont rattachés à ce site.',
  selectWarehouse: 'Sélectionner un entrepôt…',
  selectWarehousePrompt: 'Sélectionnez un entrepôt ci-dessus pour configurer ses postes GTP.',
  cancel: 'Annuler',
  save: 'Enregistrer',
  workplaces: 'Postes',
  newWorkplace: '+ Nouveau poste',
  colCode: 'Code',
  colName: 'Nom',
  colTopology: 'Topologie',
  colOperatingModes: 'Modes de fonctionnement',
  colNodes: 'Nœuds',
  colStatus: 'Statut',
  loading: 'Chargement…',
  noWorkplaces: 'Aucun poste GTP dans cet entrepôt pour l’instant.',
  edit: 'Modifier',
  delete: 'Supprimer',
  deleteWorkplaceTitle: 'Supprimer le poste',
  deleteWorkplacePrefix: 'Supprimer le poste',
  deleteWorkplaceSuffix: 'et tous ses nœuds ? Cette action est irréversible.',
  editWorkplaceTitle: 'Modifier le poste',
  newWorkplaceTitle: 'Nouveau poste',
  code: 'Code',
  name: 'Nom',
  workplaceCodeTip:
    'Identifiant court et unique de ce poste dans l’entrepôt. Utilisé sur les écrans opérateur et sur l’appareil.',
  workplaceNameTip:
    'Description facultative et lisible du poste, affichée à côté du code pour aider les opérateurs à le reconnaître.',
  workplaceNamePlaceholder: 'p. ex. Allée 3 Put-wall',
  destinationTopology: 'Topologie de destination',
  destinationTopologyTip:
    'Comment les destinations de commande sont agencées : ORDER_LOCATION = une cible fixe/convoyeur par commande ; PUT_WALL = plusieurs casiers entre lesquels l’opérateur répartit. Pertinent uniquement pour PICKING.',
  status: 'Statut',
  workplaceStatusTip:
    'État du cycle de vie du poste. Seuls les postes ACTIVE acceptent du travail ; ARCHIVED le masque de l’usage opérationnel.',
  supportedOperatingModes: 'Modes de fonctionnement pris en charge',
  supportedOperatingModesTip:
    'Quels types de tâches l’opérateur peut effectuer ici lorsqu’une HU est présentée. PICKING est toujours activé ; cochez les autres pour les autoriser.',
  nodesFor: 'Nœuds:',
  operatingModes: 'Modes de fonctionnement',
  operatingModesHint:
    'Ce que l’opérateur peut faire à ce poste lorsqu’une HU est présentée. PICKING est toujours disponible.',
  capacity: 'Capacité',
  newNode: '+ Nouveau nœud',
  nodesHint:
    'Les nœuds STOCK présentent une HU de stock à l’opérateur ; les nœuds ORDER sont des destinations de commande (un emplacement fixe/convoyeur en mode ORDER_LOCATION, ou un casier de put-wall en mode PUT_WALL) et portent un put-light optionnel.',
  colPos: 'Pos',
  colRole: 'Rôle',
  colPutLightId: 'Id put-light',
  colLocationId: 'Id emplacement',
  colOrderHuId: 'Id HU de commande',
  noNodes: 'Aucun nœud configuré. Ajoutez des nœuds STOCK et ORDER.',
  remove: 'Retirer',
  removeNodeTitle: 'Retirer le nœud',
  removeNodePrefix: 'Retirer',
  removeNodeSuffix: 'nœud',
  editNodeTitle: 'Modifier le nœud',
  newNodeTitle: 'Nouveau nœud',
  role: 'Rôle',
  roleTip:
    'Un nœud STOCK présente une HU de stock source à l’opérateur ; un nœud ORDER est une destination de commande (emplacement fixe ou casier de put-wall).',
  nodeCodeTip:
    'Identifiant court et unique de ce nœud dans le poste. Affiché à l’opérateur et utilisé pour adresser la position.',
  putLightId: 'Id put-light',
  putLightIdTip:
    'Identifiant du dispositif physique pick/put-to-light ou d’affichage à cette destination, utilisé pour guider l’opérateur. Laissez vide s’il n’y en a pas.',
  putLightIdPlaceholder: 'Id du voyant/afficheur physique',
  orderHuId: 'Id HU de commande',
  orderHuIdTip:
    'UUID de l’unité de manutention de commande (carton/bac) actuellement liée à cette destination. Généralement défini par le système ; laissez vide s’il n’y en a pas.',
  orderHuIdPlaceholder: 'UUID (HU de commande actuellement liée)',
  location: 'Emplacement',
  locationTip:
    'L’emplacement de données de référence auquel ce nœud correspond, lorsqu’il s’agit d’une position fixe/convoyeur, recherché par code d’emplacement. Laissez vide pour les casiers dynamiques de put-wall.',
  searchLocationCode: 'Rechercher un code d’emplacement…',
  position: 'Position',
  positionTip:
    'Index de tri qui détermine où ce nœud apparaît dans la disposition du poste et la liste des nœuds (les plus petits numéros en premier).',
  nodeStatusTip:
    'Si ce nœud est en usage opérationnel. Les nœuds INACTIVE restent sur le poste mais sont ignorés pendant le travail.',
  inTransitCapacity: 'Capacité en transit',
  capacityHint:
    'Combien d’unités de manutention (bacs) peuvent avoir un transport en route vers ce poste en même temps, plafonné séparément par classe de mode. Le picking est le cas à haut débit ; Autre couvre le décantage, le comptage, le QC et la maintenance.',
  maxInTransitPicking: 'HUs max en transit: Picking',
  maxInTransitPickingTip:
    'Plafonne le nombre de HUs pouvant avoir un transport PICKING actif entrant vers ce poste en même temps. Plus haut alimente l’opérateur ; trop haut engorge le tampon d’entrée.',
  maxInTransitOther: 'HUs max en transit: Autre (hors picking)',
  maxInTransitOtherTip:
    'Plafonne le nombre de HUs pouvant avoir un transport hors picking actif (décantage, comptage, QC, maintenance) entrant vers ce poste en même temps.',
  noLocations: 'Aucun emplacement dans cet entrepôt.',
  noMatchingCode: 'Aucun code correspondant.',
}
