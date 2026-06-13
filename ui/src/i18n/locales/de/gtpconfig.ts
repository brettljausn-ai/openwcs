// German (Deutsch): GTP workplace configuration (admin). Keys mirror useT('gtpconfig', ...) calls.
export default {
  eyebrow: 'Konfiguration',
  title: 'GTP-Arbeitsplätze',
  intro:
    'Konfigurieren Sie Goods-to-Person-Arbeitsplätze (Stationen): ihre Zieltopologie, die unterstützten Betriebsarten und ihre STOCK-/ORDER-Knoten. Arbeitsplätze sind dem ausgewählten Lager zugeordnet.',
  warehouse: 'Lager',
  warehouseTip:
    'Das Lager, dessen GTP-Arbeitsplätze Sie konfigurieren. Alle Arbeitsplätze und Knoten unten gehören zu diesem Standort.',
  selectWarehouse: 'Lager auswählen…',
  selectWarehousePrompt: 'Wählen Sie oben ein Lager, um seine GTP-Arbeitsplätze zu konfigurieren.',
  cancel: 'Abbrechen',
  save: 'Speichern',
  workplaces: 'Arbeitsplätze',
  newWorkplace: '+ Neuer Arbeitsplatz',
  colCode: 'Code',
  colName: 'Name',
  colTopology: 'Topologie',
  colOperatingModes: 'Betriebsarten',
  colNodes: 'Knoten',
  colStatus: 'Status',
  loading: 'Wird geladen…',
  noWorkplaces: 'Noch keine GTP-Arbeitsplätze in diesem Lager.',
  edit: 'Bearbeiten',
  delete: 'Löschen',
  deleteWorkplaceTitle: 'Arbeitsplatz löschen',
  deleteWorkplacePrefix: 'Arbeitsplatz löschen',
  deleteWorkplaceSuffix: 'und alle zugehörigen Knoten? Dies kann nicht rückgängig gemacht werden.',
  editWorkplaceTitle: 'Arbeitsplatz bearbeiten',
  newWorkplaceTitle: 'Neuer Arbeitsplatz',
  code: 'Code',
  name: 'Name',
  workplaceCodeTip:
    'Kurze eindeutige Kennung für diesen Arbeitsplatz im Lager. Wird in Bedienoberflächen und am Gerät verwendet.',
  workplaceNameTip:
    'Optionale verständliche Beschreibung des Arbeitsplatzes, die neben dem Code angezeigt wird, damit Bediener ihn leichter erkennen.',
  workplaceNamePlaceholder: 'z. B. Gang 3 Put-Wall',
  destinationTopology: 'Zieltopologie',
  destinationTopologyTip:
    'Wie Auftragsziele angeordnet sind: ORDER_LOCATION = ein festes/Förderband-Ziel pro Auftrag; PUT_WALL = mehrere Fächer, in die der Bediener verteilt. Nur für PICKING relevant.',
  status: 'Status',
  workplaceStatusTip:
    'Lebenszyklusstatus des Arbeitsplatzes. Nur ACTIVE-Arbeitsplätze nehmen Arbeit an; ARCHIVED blendet ihn aus dem operativen Einsatz aus.',
  supportedOperatingModes: 'Unterstützte Betriebsarten',
  supportedOperatingModesTip:
    'Welche Aufgabentypen der Bediener hier ausführen darf, wenn eine HU bereitgestellt wird. PICKING ist immer aktiviert; aktivieren Sie weitere, um sie zuzulassen.',
  nodesFor: 'Knoten:',
  operatingModes: 'Betriebsarten',
  operatingModesHint:
    'Was der Bediener an diesem Arbeitsplatz tun kann, wenn eine HU bereitgestellt wird. PICKING ist immer verfügbar.',
  capacity: 'Kapazität',
  newNode: '+ Neuer Knoten',
  nodesHint:
    'STOCK-Knoten stellen dem Bediener eine Bestands-HU bereit; ORDER-Knoten sind Auftragsziele (eine feste/Förderband-Position im ORDER_LOCATION-Modus oder ein Put-Wall-Fach im PUT_WALL-Modus) und tragen ein optionales Put-Light.',
  colPos: 'Pos',
  colRole: 'Rolle',
  colPutLightId: 'Put-Light-ID',
  colLocationId: 'Lagerplatz-ID',
  colOrderHuId: 'Auftrags-HU-ID',
  noNodes: 'Keine Knoten konfiguriert. Fügen Sie STOCK- und ORDER-Knoten hinzu.',
  remove: 'Entfernen',
  removeNodeTitle: 'Knoten entfernen',
  removeNodePrefix: 'Entfernen',
  removeNodeSuffix: 'Knoten',
  editNodeTitle: 'Knoten bearbeiten',
  newNodeTitle: 'Neuer Knoten',
  role: 'Rolle',
  roleTip:
    'Ein STOCK-Knoten stellt dem Bediener eine Quell-Bestands-HU bereit; ein ORDER-Knoten ist ein Auftragsziel (feste Position oder Put-Wall-Fach).',
  nodeCodeTip:
    'Kurze eindeutige Kennung für diesen Knoten im Arbeitsplatz. Wird dem Bediener angezeigt und zur Adressierung der Position verwendet.',
  putLightId: 'Put-Light-ID',
  putLightIdTip:
    'Kennung des physischen Pick-/Put-to-Light- oder Anzeigegeräts an diesem Ziel, das den Bediener führt. Leer lassen, wenn keines vorhanden ist.',
  putLightIdPlaceholder: 'ID des physischen Lichts/Displays',
  orderHuId: 'Auftrags-HU-ID',
  orderHuIdTip:
    'UUID der Auftrags-Handhabungseinheit (Karton/Behälter), die derzeit diesem Ziel zugeordnet ist. Wird üblicherweise vom System gesetzt; leer lassen, wenn keine vorhanden ist.',
  orderHuIdPlaceholder: 'UUID (derzeit gebundene Auftrags-HU)',
  location: 'Lagerplatz',
  locationTip:
    'Der Stammdaten-Lagerplatz, dem dieser Knoten entspricht, wenn er eine feste/Förderband-Position ist, gesucht über den Lagerplatz-Code. Leer lassen für dynamische Put-Wall-Fächer.',
  searchLocationCode: 'Lagerplatz-Code suchen…',
  position: 'Position',
  positionTip:
    'Sortierindex, der bestimmt, wo dieser Knoten im Arbeitsplatz-Layout und in der Knotenliste erscheint (niedrigere Zahlen zuerst).',
  nodeStatusTip:
    'Ob dieser Knoten im operativen Einsatz ist. INACTIVE-Knoten bleiben am Arbeitsplatz, werden aber während der Arbeit übersprungen.',
  inTransitCapacity: 'Kapazität in Transit',
  capacityHint:
    'Wie viele Handhabungseinheiten (Behälter) gleichzeitig einen Transport zu diesem Arbeitsplatz unterwegs haben dürfen, getrennt je Modusklasse begrenzt. Picking ist der Hochdurchsatzfall; Sonstige umfasst Dekantieren, Zählen, QC und Wartung.',
  maxInTransitPicking: 'Max. HUs in Transit: Picking',
  maxInTransitPickingTip:
    'Begrenzt, wie viele HUs gleichzeitig einen aktiven PICKING-Transport zu diesem Arbeitsplatz unterwegs haben dürfen. Höher hält den Bediener versorgt; zu hoch staut den Eingangspuffer.',
  maxInTransitOther: 'Max. HUs in Transit: Sonstige (kein Picking)',
  maxInTransitOtherTip:
    'Begrenzt, wie viele HUs gleichzeitig einen aktiven Nicht-Picking-Transport (Dekantieren, Zählen, QC, Wartung) zu diesem Arbeitsplatz unterwegs haben dürfen.',
  noLocations: 'Keine Lagerplätze in diesem Lager.',
  noMatchingCode: 'Kein passender Code.',
}
